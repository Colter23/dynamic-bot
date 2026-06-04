package top.colter.dynamic.listener

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkAccount
import top.colter.dynamic.core.plugin.MessageSinkAccountRole
import top.colter.dynamic.core.plugin.MessageSinkAccountState
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.core.tools.loggerFor

private val accountRouterLogger = loggerFor<MessageSinkAccountRouter>()

public class MessageSinkAccountRouter(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val cooldownUntil = ConcurrentHashMap<AccountKey, Long>()
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    suspend fun sendMessage(
        sink: AccountRoutedMessageSinkPlugin,
        request: MessageDeliveryRequest,
    ): MessageSendResult {
        return sendWithAccount(
            sink = sink,
            target = request.target,
            actionLabel = "消息发送",
        ) { accountId ->
            sink.sendMessage(request, accountId)
        }
    }

    suspend fun sendCommandResult(
        sink: AccountRoutedMessageSinkPlugin,
        request: CommandResultSendRequest,
    ): MessageSendResult {
        return sendWithAccount(
            sink = sink,
            target = request.target.address,
            actionLabel = "命令回复",
        ) { accountId ->
            sink.sendCommandResult(request, accountId)
        }
    }

    suspend fun recallMessage(
        sink: AccountRoutedMessageSinkPlugin,
        request: MessageRecallRequest,
    ): MessageRecallResult {
        val requestedAccountId = request.sinkAccountId?.normalizedAccountId()
        if (requestedAccountId != null) {
            return runCatching { sink.recallMessage(request, requestedAccountId) }
                .getOrElse { MessageRecallResult.failed(it.message ?: "消息撤回失败") }
        }

        val candidates = routeAccounts(sink, request.target)
            .getOrElse { error -> return MessageRecallResult.failed(error.message ?: "消息撤回账号选择失败") }
        if (candidates.isEmpty()) {
            return MessageRecallResult.failed("消息撤回无可用账号")
        }

        var lastFailure: MessageRecallResult.Failed? = null
        for (account in candidates) {
            val result = runCatching { sink.recallMessage(request, account.accountId) }
                .getOrElse { MessageRecallResult.failed(it.message ?: "消息撤回失败") }
            when (result) {
                MessageRecallResult.Recalled,
                MessageRecallResult.Unsupported -> return result
                is MessageRecallResult.Failed -> lastFailure = result
            }
        }
        return lastFailure ?: MessageRecallResult.failed("消息撤回失败")
    }

    private suspend fun sendWithAccount(
        sink: AccountRoutedMessageSinkPlugin,
        target: TargetAddress,
        actionLabel: String,
        action: suspend (String) -> MessageSendResult,
    ): MessageSendResult {
        val policy = runCatching { sink.routingPolicy() }
            .getOrElse { error ->
                return MessageSendResult.failed("${actionLabel}账号路由策略无效：${error.message}", retryable = true)
            }
        val candidates = routeAccounts(sink, target, policy)
            .getOrElse { error ->
                return MessageSendResult.failed("${actionLabel}账号选择失败：${error.message}", retryable = true)
            }
        if (candidates.isEmpty()) {
            return MessageSendResult.failed("${actionLabel}无可用账号", retryable = true)
        }

        val failures = mutableListOf<String>()
        for (account in candidates) {
            val accountId = account.accountId
            val result = runCatching { action(accountId) }
                .getOrElse { error ->
                    MessageSendResult.failed(error.message ?: "${actionLabel}失败", retryable = true)
                }
            when (result) {
                is MessageSendResult.Sent -> {
                    clearFailure(sink, accountId)
                    result.sinkAccountId?.let { clearFailure(sink, it) }
                    return result.copy(sinkAccountId = result.sinkAccountId ?: accountId)
                }
                is MessageSendResult.Failed -> {
                    markFailure(sink, accountId, policy)
                    failures += "$accountId=${result.reason}"
                    if (result.partialSent) {
                        accountRouterLogger.warn {
                            "$actionLabel 已部分成功，停止账号切换：target=${target.stableValue()}，accountId=$accountId，原因=${result.reason}"
                        }
                        return result.copy(retryable = false, partialSent = true)
                    }
                    if (!result.retryable) {
                        return result
                    }
                }
            }
        }

        return MessageSendResult.failed(
            reason = if (failures.isEmpty()) {
                "${actionLabel}无可用账号"
            } else {
                "${actionLabel}所有账号均失败：${failures.joinToString("；")}"
            },
            retryable = true,
        )
    }

    private suspend fun routeAccounts(
        sink: AccountRoutedMessageSinkPlugin,
        target: TargetAddress,
        policy: MessageSinkRoutingPolicy = sink.routingPolicy(),
    ): Result<List<MessageSinkAccount>> = runCatching {
        val now = nowEpochSeconds()
        val accounts = sink.listMessageSinkAccounts(target)
            .distinctBy { it.accountId }
            .filter { it.enabled && it.state == MessageSinkAccountState.READY }
            .filter { account ->
                val until = cooldownUntil[AccountKey(sink.routeKey(), account.accountId)] ?: return@filter true
                if (until <= now) {
                    cooldownUntil.remove(AccountKey(sink.routeKey(), account.accountId), until)
                    true
                } else {
                    false
                }
            }

        val preferredAccountId = target.accountId.normalizedAccountId()
        val ordered = when (policy.strategy) {
            MessageSinkRoutingStrategy.ROUND_ROBIN -> roundRobinOrder(sink, accounts)
            MessageSinkRoutingStrategy.PRIMARY_BACKUP -> primaryBackupOrder(accounts)
        }
        if (preferredAccountId == null) {
            ordered
        } else {
            val preferred = accounts.firstOrNull { it.accountId == preferredAccountId }
            if (preferred == null) {
                ordered
            } else {
                listOf(preferred) + ordered.filterNot { it.accountId == preferredAccountId }
            }
        }
    }

    private fun roundRobinOrder(
        sink: AccountRoutedMessageSinkPlugin,
        accounts: List<MessageSinkAccount>,
    ): List<MessageSinkAccount> {
        if (accounts.size <= 1) return accounts
        val cursor = cursors.computeIfAbsent(sink.routeKey()) { AtomicInteger(0) }
        val start = cursor.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 0 else current + 1
        }.floorMod(accounts.size)
        return accounts.drop(start) + accounts.take(start)
    }

    private fun primaryBackupOrder(accounts: List<MessageSinkAccount>): List<MessageSinkAccount> {
        return accounts.filter { it.role == MessageSinkAccountRole.PRIMARY } +
            accounts.filter { it.role == MessageSinkAccountRole.BACKUP }
    }

    private fun markFailure(
        sink: AccountRoutedMessageSinkPlugin,
        accountId: String,
        policy: MessageSinkRoutingPolicy,
    ) {
        val until = nowEpochSeconds() + policy.failureCooldownSeconds.coerceAtLeast(1)
        cooldownUntil[AccountKey(sink.routeKey(), accountId)] = until
    }

    private fun clearFailure(sink: AccountRoutedMessageSinkPlugin, accountId: String) {
        cooldownUntil.remove(AccountKey(sink.routeKey(), accountId))
    }

    private fun AccountRoutedMessageSinkPlugin.routeKey(): String {
        return "${this::class.qualifiedName}:${platformId.value}"
    }

    private fun Int.floorMod(divisor: Int): Int = Math.floorMod(this, divisor)

    private fun String?.normalizedAccountId(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private data class AccountKey(
        val sinkKey: String,
        val accountId: String,
    )
}
