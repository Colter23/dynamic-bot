package top.colter.dynamic.listener

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.colter.dynamic.DeliveryConfig
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.core.plugin.CommandResultSendRequest
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.core.tools.loggerFor
import kotlin.math.ceil
import kotlin.math.roundToLong

private val logger = loggerFor<DeliveryDispatcher>()

public data class DeliveryDispatchStats(
    val claimed: Int,
    val sent: Int,
    val retried: Int,
    val failed: Int,
)

public class DeliveryDispatcher(
    private val sinkProvider: () -> List<PluginHandle<MessageSinkPlugin>>,
    private val configProvider: () -> DeliveryConfig,
) {
    public suspend fun dispatchDue(): DeliveryDispatchStats = coroutineScope {
        val config = configProvider()
        val now = nowEpochSeconds()
        val expired = MessageDeliveryRepository.markSendingExpired(now)
        if (expired > 0) {
            logger.warn { "已恢复超时投递任务：数量=$expired" }
        }

        val requests = MessageDeliveryRepository.claimDue(
            nowEpochSeconds = now,
            limit = config.dispatchConcurrency.coerceAtLeast(1) * 4,
            lockTtlMs = secondsToMillis(config.lockTtlSeconds, minimumMillis = 1),
        )
        if (requests.isEmpty()) return@coroutineScope DeliveryDispatchStats(0, 0, 0, 0)

        val concurrency = config.dispatchConcurrency.coerceAtLeast(1)
        logger.debug { "开始投递消息：领取=${requests.size}，并发=$concurrency" }

        val semaphore = Semaphore(concurrency)
        val results = requests.map { request ->
            async {
                semaphore.withPermit {
                    sendDelivery(request, config)
                }
            }
        }.awaitAll()

        val stats = DeliveryDispatchStats(
            claimed = requests.size,
            sent = results.count { it == DeliveryOutcome.SENT },
            retried = results.count { it == DeliveryOutcome.RETRIED },
            failed = results.count { it == DeliveryOutcome.FAILED },
        )
        logger.info { "投递批次完成：领取=${stats.claimed}，成功=${stats.sent}，重试=${stats.retried}，失败=${stats.failed}" }
        stats
    }

    public suspend fun dispatchCommandResult(event: CommandResultEvent): MessageSendResult {
        val result = sendCommandResult(
            CommandResultSendRequest(
                target = event.target,
                chain = event.chain,
                inReplyTo = event.inReplyTo,
            ),
        )
        if (result is MessageSendResult.Failed) {
            logger.warn {
                "命令回复发送失败：traceId=${event.inReplyTo}，target=${event.target.address.stableValue()}，原因=${result.reason}"
            }
        }
        return result
    }

    public suspend fun sendCommandResult(request: CommandResultSendRequest): MessageSendResult {
        return when (val resolved = resolveSink(request.target.address)) {
            is SinkResolveResult.Found -> {
                runCatching { resolved.sink.sendCommandResult(request) }
                    .getOrElse { error ->
                        MessageSendResult.failed(error.message ?: "命令回复发送失败", retryable = true)
                    }
            }
            is SinkResolveResult.NotFound -> {
                logger.warn { "命令回复无可用消息出口：traceId=${request.inReplyTo}，target=${request.target.address.stableValue()}" }
                MessageSendResult.failed("命令回复无可用消息出口", retryable = false)
            }
            is SinkResolveResult.Ambiguous -> {
                logger.warn {
                    "命令回复消息出口不唯一：traceId=${request.inReplyTo}，target=${request.target.address.stableValue()}，plugins=${resolved.pluginIds}"
                }
                MessageSendResult.failed("命令回复消息出口不唯一：plugins=${resolved.pluginIds.joinToString(",")}", retryable = false)
            }
        }
    }

    public suspend fun recallMessage(target: TargetAddress, sinkMessageId: String): MessageRecallResult {
        return when (val resolved = resolveSink(target)) {
            is SinkResolveResult.Found -> {
                runCatching {
                    resolved.sink.recallMessage(MessageRecallRequest(target = target, sinkMessageId = sinkMessageId))
                }.getOrElse { error ->
                    MessageRecallResult.failed(error.message ?: "消息撤回失败")
                }
            }
            is SinkResolveResult.NotFound -> {
                logger.warn { "消息撤回无可用消息出口：target=${target.stableValue()}，sinkMessageId=$sinkMessageId" }
                MessageRecallResult.failed("消息撤回无可用消息出口")
            }
            is SinkResolveResult.Ambiguous -> {
                logger.warn {
                    "消息撤回消息出口不唯一：target=${target.stableValue()}，sinkMessageId=$sinkMessageId，plugins=${resolved.pluginIds}"
                }
                MessageRecallResult.failed("消息撤回消息出口不唯一：plugins=${resolved.pluginIds.joinToString(",")}")
            }
        }
    }

    private suspend fun sendDelivery(request: MessageDeliveryRequest, config: DeliveryConfig): DeliveryOutcome {
        return when (val resolved = resolveSink(request.target)) {
            is SinkResolveResult.Found -> sendWithSink(request, resolved.sink, config)
            is SinkResolveResult.NotFound -> {
                logger.warn {
                    "消息投递失败：未找到消息出口插件，deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}"
                }
                MessageDeliveryRepository.markFailed(
                    request.delivery.id,
                    "未找到消息出口插件：platform=${request.target.platformId.value}，kind=${request.target.kind.name}",
                )
                DeliveryOutcome.FAILED
            }
            is SinkResolveResult.Ambiguous -> {
                logger.warn {
                    "消息投递失败：消息出口插件不唯一，deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，plugins=${resolved.pluginIds}"
                }
                MessageDeliveryRepository.markFailed(
                    request.delivery.id,
                    "消息出口插件不唯一：platform=${request.target.platformId.value}，kind=${request.target.kind.name}，plugins=${resolved.pluginIds.joinToString(",")}",
                )
                DeliveryOutcome.FAILED
            }
        }
    }

    private suspend fun sendWithSink(
        request: MessageDeliveryRequest,
        sink: MessageSinkPlugin,
        config: DeliveryConfig,
    ): DeliveryOutcome {
        logger.debug {
            "正在发送消息：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，attempt=${request.delivery.attempts}"
        }
        val result = runCatching { sink.sendMessage(request) }
            .getOrElse { error ->
                MessageSendResult.failed(error.message ?: "消息发送失败", retryable = true)
            }
        return when (result) {
            is MessageSendResult.Sent -> {
                MessageDeliveryRepository.markSent(request.delivery.id, result.sinkMessageId)
                logger.debug {
                    "消息发送成功：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，sinkMessageId=${result.sinkMessageId ?: "-"}"
                }
                DeliveryOutcome.SENT
            }
            is MessageSendResult.Failed -> handleSendFailure(request, result, config)
        }
    }

    private fun handleSendFailure(
        request: MessageDeliveryRequest,
        result: MessageSendResult.Failed,
        config: DeliveryConfig,
    ): DeliveryOutcome {
        val shouldRetry = result.retryable && request.delivery.attempts < config.maxAttempts.coerceAtLeast(1)
        return if (shouldRetry) {
            val nextAttemptAt = nowEpochSeconds() + wholeSeconds(config.retryDelaySeconds)
            MessageDeliveryRepository.markRetry(
                deliveryId = request.delivery.id,
                error = result.reason,
                nextAttemptAtEpochSeconds = nextAttemptAt,
            )
            logger.warn {
                "消息发送失败，已安排重试：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，nextAttemptAt=$nextAttemptAt，原因=${result.reason}"
            }
            DeliveryOutcome.RETRIED
        } else {
            MessageDeliveryRepository.markFailed(request.delivery.id, result.reason)
            logger.warn {
                "消息发送失败，已标记失败：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，原因=${result.reason}"
            }
            DeliveryOutcome.FAILED
        }
    }

    private fun resolveSink(target: TargetAddress): SinkResolveResult {
        val matches = sinkProvider()
            .filter { handle ->
                val sink = handle.instance
                sink.platformId == target.platformId &&
                    (sink.supportedTargetKinds.isEmpty() || target.kind in sink.supportedTargetKinds)
            }
        return when (matches.size) {
            0 -> SinkResolveResult.NotFound
            1 -> SinkResolveResult.Found(matches.single().instance)
            else -> SinkResolveResult.Ambiguous(matches.map { it.info.descriptor.id }.sorted())
        }
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private fun wholeSeconds(seconds: Double): Long = ceil(seconds).toLong().coerceAtLeast(1)

    private enum class DeliveryOutcome {
        SENT,
        RETRIED,
        FAILED,
    }

    private sealed interface SinkResolveResult {
        data class Found(val sink: MessageSinkPlugin) : SinkResolveResult
        data object NotFound : SinkResolveResult
        data class Ambiguous(val pluginIds: List<String>) : SinkResolveResult
    }
}
