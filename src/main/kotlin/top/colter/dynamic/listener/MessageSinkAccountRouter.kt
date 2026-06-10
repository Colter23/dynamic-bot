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
import top.colter.dynamic.core.plugin.MessageSinkRoute
import top.colter.dynamic.core.plugin.MessageSinkRouteState
import top.colter.dynamic.core.plugin.MessageSinkRoutingPolicy
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.core.tools.loggerFor

private val accountRouterLogger = loggerFor<MessageSinkAccountRouter>()

internal data class MessageSinkRouteCandidate(
    val pluginId: String,
    val sink: AccountRoutedMessageSinkPlugin,
    val route: MessageSinkRoute,
)

public class MessageSinkAccountRouter(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    internal suspend fun sendMessage(
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
        request: MessageDeliveryRequest,
        prepareRequest: suspend (MessageSinkRouteCandidate) -> MessageDeliveryRequest = { request },
        onRouteFailure: (MessageSinkRouteCandidate) -> Unit = {},
    ): MessageSendResult {
        return sendWithRoute(
            candidates = candidates,
            target = request.target,
            policy = policy,
            actionLabel = "消息发送",
            onRouteFailure = onRouteFailure,
        ) { candidate ->
            candidate.sink.sendMessage(prepareRequest(candidate), candidate.route.routeId)
        }
    }

    internal suspend fun sendCommandResult(
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
        request: CommandResultSendRequest,
        prepareRequest: suspend (MessageSinkRouteCandidate) -> CommandResultSendRequest = { request },
        onRouteFailure: (MessageSinkRouteCandidate) -> Unit = {},
    ): MessageSendResult {
        return sendWithRoute(
            candidates = candidates,
            target = request.target.address,
            policy = policy,
            actionLabel = "命令回复",
            onRouteFailure = onRouteFailure,
        ) { candidate ->
            candidate.sink.sendCommandResult(prepareRequest(candidate), candidate.route.routeId)
        }
    }

    internal suspend fun recallMessage(
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
        request: MessageRecallRequest,
    ): MessageRecallResult {
        val requestedRouteId = request.sinkRouteId.normalized()
        if (requestedRouteId != null) {
            val candidate = candidates.firstOrNull { it.route.routeId == requestedRouteId }
                ?: return MessageRecallResult.failed("消息撤回路线不存在：$requestedRouteId")
            return runCatching { candidate.sink.recallMessage(request, candidate.route.routeId) }
                .getOrElse { MessageRecallResult.failed(it.message ?: "消息撤回失败") }
        }

        val ordered = routeCandidates(
            candidates = candidates,
            target = request.target,
            policy = policy,
            preferredAccountId = request.sinkAccountId.normalized() ?: request.target.accountId.normalized(),
        )
        if (ordered.isEmpty()) {
            return MessageRecallResult.failed("消息撤回无可用发送路线")
        }

        var lastFailure: MessageRecallResult.Failed? = null
        for (candidate in ordered) {
            val result = runCatching { candidate.sink.recallMessage(request, candidate.route.routeId) }
                .getOrElse { MessageRecallResult.failed(it.message ?: "消息撤回失败") }
            when (result) {
                MessageRecallResult.Recalled,
                MessageRecallResult.Unsupported -> return result
                is MessageRecallResult.Failed -> lastFailure = result
            }
        }
        return lastFailure ?: MessageRecallResult.failed("消息撤回失败")
    }

    private suspend fun sendWithRoute(
        candidates: List<MessageSinkRouteCandidate>,
        target: TargetAddress,
        policy: MessageSinkRoutingPolicy,
        actionLabel: String,
        onRouteFailure: (MessageSinkRouteCandidate) -> Unit,
        action: suspend (MessageSinkRouteCandidate) -> MessageSendResult,
    ): MessageSendResult {
        val ordered = routeCandidates(candidates, target, policy, target.accountId.normalized())
        if (ordered.isEmpty()) {
            return MessageSendResult.failed("${actionLabel}无可用发送路线", retryable = true)
        }

        val failures = mutableListOf<String>()
        for (candidate in ordered) {
            val route = candidate.route
            val result = runCatching { action(candidate) }
                .getOrElse { error -> MessageSendResult.failed(error.message ?: "${actionLabel}失败", retryable = true) }
            when (result) {
                is MessageSendResult.Sent -> {
                    clearFailure(route.routeId)
                    return result.copy(
                        sinkRouteId = result.sinkRouteId ?: route.routeId,
                        sinkAccountId = result.sinkAccountId ?: route.accountId,
                    )
                }
                is MessageSendResult.Failed -> {
                    markFailure(route.routeId, policy)
                    onRouteFailure(candidate)
                    failures += "${route.routeId}=${result.reason}"
                    if (result.partialSent) {
                        accountRouterLogger.warn {
                            "$actionLabel 已部分成功，停止路线切换：target=${target.stableValue()}，routeId=${route.routeId}，原因=${result.reason}"
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
                "${actionLabel}无可用发送路线"
            } else {
                "${actionLabel}所有发送路线均失败：${failures.joinToString("；")}"
            },
            retryable = true,
        )
    }

    private fun routeCandidates(
        candidates: List<MessageSinkRouteCandidate>,
        target: TargetAddress,
        policy: MessageSinkRoutingPolicy,
        preferredAccountId: String?,
    ): List<MessageSinkRouteCandidate> {
        val now = nowEpochSeconds()
        val ready = candidates
            .distinctBy { it.route.routeId }
            .filter { it.route.enabled && it.route.state == MessageSinkRouteState.READY }
            .filter { it.route.targetPlatformId == target.platformId }
            .filter { candidate ->
                val until = cooldownUntil[candidate.route.routeId] ?: return@filter true
                if (until <= now) {
                    cooldownUntil.remove(candidate.route.routeId, until)
                    true
                } else {
                    false
                }
            }
        if (ready.isEmpty()) return emptyList()

        val ordered = when (policy.strategy) {
            MessageSinkRoutingStrategy.ROUND_ROBIN -> roundRobinOrder(target, ready, policy)
            MessageSinkRoutingStrategy.PRIMARY_BACKUP -> primaryBackupOrder(ready, policy)
        }
        if (preferredAccountId == null) {
            return ordered
        }

        val preferred = ordered.filter { it.route.accountId == preferredAccountId }
        return if (preferred.isEmpty()) {
            ordered
        } else {
            preferred + ordered.filterNot { it.route.accountId == preferredAccountId }
        }
    }

    private fun roundRobinOrder(
        target: TargetAddress,
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
    ): List<MessageSinkRouteCandidate> {
        if (candidates.size <= 1) return candidates
        val base = primaryFirst(candidates, policy)
        val cursorKey = target.platformId.value
        val cursor = cursors.computeIfAbsent(cursorKey) { AtomicInteger(0) }
        val start = cursor.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 0 else current + 1
        }.floorMod(base.size)
        return base.drop(start) + base.take(start)
    }

    private fun primaryBackupOrder(
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
    ): List<MessageSinkRouteCandidate> = primaryFirst(candidates, policy)

    private fun primaryFirst(
        candidates: List<MessageSinkRouteCandidate>,
        policy: MessageSinkRoutingPolicy,
    ): List<MessageSinkRouteCandidate> {
        val primaryAccountId = policy.primaryAccountId.normalized() ?: candidates.first().route.accountId
        return candidates.filter { it.route.accountId == primaryAccountId } +
            candidates.filterNot { it.route.accountId == primaryAccountId }
    }

    private fun markFailure(routeId: String, policy: MessageSinkRoutingPolicy) {
        val until = nowEpochSeconds() + policy.failureCooldownSeconds.coerceAtLeast(1)
        cooldownUntil[routeId] = until
    }

    private fun clearFailure(routeId: String) {
        cooldownUntil.remove(routeId)
    }

    private fun Int.floorMod(divisor: Int): Int = Math.floorMod(this, divisor)

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotBlank() }
}
