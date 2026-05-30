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
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.core.tools.loggerFor

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
            lockTtlMs = config.lockTtlMs.coerceAtLeast(1),
        )
        if (requests.isEmpty()) return@coroutineScope DeliveryDispatchStats(0, 0, 0, 0)

        val semaphore = Semaphore(config.dispatchConcurrency.coerceAtLeast(1))
        val results = requests.map { request ->
            async {
                semaphore.withPermit {
                    sendDelivery(request, config)
                }
            }
        }.awaitAll()

        DeliveryDispatchStats(
            claimed = requests.size,
            sent = results.count { it == DeliveryOutcome.SENT },
            retried = results.count { it == DeliveryOutcome.RETRIED },
            failed = results.count { it == DeliveryOutcome.FAILED },
        )
    }

    public suspend fun dispatchCommandResult(event: CommandResultEvent) {
        when (val resolved = resolveSink(event.target.address)) {
            is SinkResolveResult.Found -> {
                val result = runCatching {
                    resolved.sink.sendCommandResult(
                        CommandResultSendRequest(
                            target = event.target,
                            chain = event.chain,
                            inReplyTo = event.inReplyTo,
                        ),
                    )
                }.getOrElse { error ->
                    MessageSendResult.failed(error.message ?: "命令回复发送失败", retryable = true)
                }
                if (result is MessageSendResult.Failed) {
                    logger.warn {
                        "命令回复发送失败：traceId=${event.inReplyTo}，target=${event.target.address.stableValue()}，原因=${result.reason}"
                    }
                }
            }
            is SinkResolveResult.NotFound -> {
                logger.warn { "命令回复无可用消息出口：traceId=${event.inReplyTo}，target=${event.target.address.stableValue()}" }
            }
            is SinkResolveResult.Ambiguous -> {
                logger.warn {
                    "命令回复消息出口不唯一：traceId=${event.inReplyTo}，target=${event.target.address.stableValue()}，plugins=${resolved.pluginIds}"
                }
            }
        }
    }

    private suspend fun sendDelivery(request: MessageDeliveryRequest, config: DeliveryConfig): DeliveryOutcome {
        return when (val resolved = resolveSink(request.target)) {
            is SinkResolveResult.Found -> sendWithSink(request, resolved.sink, config)
            is SinkResolveResult.NotFound -> {
                MessageDeliveryRepository.markFailed(
                    request.delivery.id,
                    "未找到消息出口插件：platform=${request.target.platformId.value}，kind=${request.target.kind.name}",
                )
                DeliveryOutcome.FAILED
            }
            is SinkResolveResult.Ambiguous -> {
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
        val result = runCatching { sink.sendMessage(request) }
            .getOrElse { error ->
                MessageSendResult.failed(error.message ?: "消息发送失败", retryable = true)
            }
        return when (result) {
            is MessageSendResult.Sent -> {
                MessageDeliveryRepository.markSent(request.delivery.id, result.sinkMessageId)
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
            MessageDeliveryRepository.markRetry(
                deliveryId = request.delivery.id,
                error = result.reason,
                nextAttemptAtEpochSeconds = nowEpochSeconds() + ((config.retryDelayMs.coerceAtLeast(1) + 999) / 1000),
            )
            DeliveryOutcome.RETRIED
        } else {
            MessageDeliveryRepository.markFailed(request.delivery.id, result.reason)
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
