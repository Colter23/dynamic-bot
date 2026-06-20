package top.colter.dynamic.listener

import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import top.colter.dynamic.DeliveryConfig
import top.colter.dynamic.MessageRoutingConfig
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.AccountRoutedMessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageDeliveryRequest
import top.colter.dynamic.core.plugin.MessageRecallRequest
import top.colter.dynamic.core.plugin.MessageRecallResult
import top.colter.dynamic.core.plugin.MessageSendRequest
import top.colter.dynamic.core.plugin.MessageSendResult
import top.colter.dynamic.core.plugin.MessageSinkFeature
import top.colter.dynamic.core.plugin.MessageSinkMediaDeliveryAdvisor
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.media.OutboundMediaRouteContext
import top.colter.dynamic.media.OutboundMediaService
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.MessageSinkReceiptRepository

private val logger = loggerFor<DeliveryDispatcher>()

public data class DeliveryDispatchStats(
    val claimed: Int,
    val sent: Int,
    val partiallySent: Int,
    val retried: Int,
    val failed: Int,
)

public class DeliveryDispatcher(
    private val sinkProvider: () -> List<PluginHandle<MessageSinkPlugin>>,
    private val configProvider: () -> DeliveryConfig,
    private val routingConfigProvider: () -> MessageRoutingConfig = { MessageRoutingConfig() },
    private val accountRouter: MessageSinkAccountRouter = MessageSinkAccountRouter(),
    private val outboundMediaService: OutboundMediaService? = null,
) {
    private val dispatchMutex = Mutex()

    public suspend fun dispatchDue(): DeliveryDispatchStats = dispatchMutex.withLock {
        dispatchDueLocked()
    }

    private suspend fun dispatchDueLocked(): DeliveryDispatchStats = coroutineScope {
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
        if (requests.isEmpty()) return@coroutineScope DeliveryDispatchStats(0, 0, 0, 0, 0)

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
            partiallySent = results.count { it == DeliveryOutcome.PARTIALLY_SENT },
            retried = results.count { it == DeliveryOutcome.RETRIED },
            failed = results.count { it == DeliveryOutcome.FAILED },
        )
        logger.info { "投递批次完成：领取=${stats.claimed}，成功=${stats.sent}，部分成功=${stats.partiallySent}，重试=${stats.retried}，失败=${stats.failed}" }
        stats
    }

    public suspend fun sendNow(request: MessageSendRequest): MessageSendResult {
        val routed = resolveRoutedSinks(request.target)
        if (routed.routedSinkIds.isNotEmpty()) {
            val (sendRequest, candidates) = prepareRoutedSendRequest(request, routed.candidates)
            return accountRouter.sendMessage(
                candidates = candidates,
                policy = routingConfigProvider().policyFor(request.target.platformId.value),
                request = sendRequest,
                prepareRequest = { candidate ->
                    rewriteSendRequest(
                        sendRequest,
                        candidate.route.mediaDeliveryProfileId,
                        mediaRouteContext(candidate),
                    )
                },
                onRouteFailure = { candidate -> invalidateMediaRoute(candidate) },
            )
        }

        return when (val direct = resolveDirectSink(request.target)) {
            is DirectSinkResolveResult.Found -> sendWithDirectSink(
                prepareDirectSendRequest(request, direct.sink),
                direct.sink,
            )
            DirectSinkResolveResult.NotFound -> {
                logger.warn { "消息发送失败：未找到消息出口插件，messageId=${request.message.id}，target=${request.target.stableValue()}" }
                MessageSendResult.failed(
                    "未找到消息出口插件：platform=${request.target.platformId.value}，kind=${request.target.kind.name}",
                    retryable = false,
                )
            }
            is DirectSinkResolveResult.Ambiguous -> {
                logger.warn {
                    "消息发送失败：消息出口插件不唯一，messageId=${request.message.id}，target=${request.target.stableValue()}，plugins=${direct.pluginIds}"
                }
                MessageSendResult.failed(
                    "消息出口插件不唯一：platform=${request.target.platformId.value}，kind=${request.target.kind.name}，plugins=${direct.pluginIds.joinToString(",")}",
                    retryable = false,
                )
            }
        }
    }

    public suspend fun recallMessage(
        target: TargetAddress,
        sinkMessageId: String,
        sinkRouteId: String? = null,
        sinkAccountId: String? = null,
    ): MessageRecallResult {
        val request = MessageRecallRequest(
            target = target,
            sinkMessageId = sinkMessageId,
            sinkRouteId = sinkRouteId,
            sinkAccountId = sinkAccountId,
        )
        val routed = resolveRoutedSinks(target)
        if (routed.routedSinkIds.isNotEmpty()) {
            return accountRouter.recallMessage(
                candidates = routed.candidates,
                policy = routingConfigProvider().policyFor(target.platformId.value),
                request = request,
            )
        }

        return when (val direct = resolveDirectSink(target)) {
            is DirectSinkResolveResult.Found -> runCatching { direct.sink.recallMessage(request) }
                .getOrElse { MessageRecallResult.failed(it.message ?: "消息撤回失败") }
            DirectSinkResolveResult.NotFound -> {
                logger.warn { "消息撤回无可用消息出口：target=${target.stableValue()}，sinkMessageId=$sinkMessageId" }
                MessageRecallResult.failed("消息撤回无可用消息出口")
            }
            is DirectSinkResolveResult.Ambiguous -> {
                logger.warn {
                    "消息撤回消息出口不唯一：target=${target.stableValue()}，sinkMessageId=$sinkMessageId，plugins=${direct.pluginIds}"
                }
                MessageRecallResult.failed("消息撤回消息出口不唯一：plugins=${direct.pluginIds.joinToString(",")}")
            }
        }
    }

    private suspend fun sendDelivery(request: MessageDeliveryRequest, config: DeliveryConfig): DeliveryOutcome {
        if (request.message.deliveryPolicy.isExpired(nowEpochSeconds())) {
            MessageDeliveryRepository.markFailed(request.delivery.id, "消息已过期，未继续投递")
            logger.warn {
                "消息投递已跳过：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，原因=消息已过期"
            }
            return DeliveryOutcome.FAILED
        }

        logger.debug {
            "正在发送消息：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，attempt=${request.delivery.attempts}"
        }
        val result = sendNow(request.toSendRequest())
        return handleSendResult(request, result, config)
    }

    private suspend fun rewriteSendRequest(
        request: MessageSendRequest,
        profileId: String,
        routeContext: OutboundMediaRouteContext,
    ): MessageSendRequest {
        val service = outboundMediaService ?: return request
        val message = service.rewriteMessage(request.message, profileId, routeContext)
        return if (message == request.message) request else request.copy(message = message)
    }

    private fun prepareRoutedSendRequest(
        request: MessageSendRequest,
        candidates: List<MessageSinkRouteCandidate>,
    ): Pair<MessageSendRequest, List<MessageSinkRouteCandidate>> {
        if (!request.message.batches.containsMergedForward()) return request to candidates
        val supported = candidates.filter { candidate ->
            MessageSinkFeature.MERGED_FORWARD in candidate.sink.supportedMessageFeatures
        }
        return if (supported.isNotEmpty()) {
            request to supported
        } else {
            request.withMergedForwardFallback() to candidates
        }
    }

    private suspend fun prepareDirectSendRequest(
        request: MessageSendRequest,
        sink: MessageSinkPlugin,
    ): MessageSendRequest {
        val prepared = if (!request.message.batches.containsMergedForward() ||
            MessageSinkFeature.MERGED_FORWARD in sink.supportedMessageFeatures
        ) {
            request
        } else {
            request.withMergedForwardFallback()
        }
        return rewriteSendRequest(prepared, sink.mediaDeliveryProfileId, mediaRouteContext(sink))
    }

    private suspend fun sendWithDirectSink(
        request: MessageSendRequest,
        sink: MessageSinkPlugin,
    ): MessageSendResult {
        val result = runCatching { sink.sendMessage(request) }
            .getOrElse { error -> MessageSendResult.failed(error.message ?: "消息发送失败", retryable = true) }
            .withTransportId(sink.transportId)
        if (result is MessageSendResult.Failed || result is MessageSendResult.PartiallySent) {
            outboundMediaService?.invalidateRoute(mediaRouteContext(sink))
        }
        return result
    }

    private fun mediaRouteContext(candidate: MessageSinkRouteCandidate): OutboundMediaRouteContext {
        return OutboundMediaRouteContext(
            transportId = candidate.route.transportId,
            routeId = candidate.route.routeId,
            accountId = candidate.route.accountId,
            advisor = candidate.sink as? MessageSinkMediaDeliveryAdvisor,
        )
    }

    private fun mediaRouteContext(sink: MessageSinkPlugin): OutboundMediaRouteContext {
        return OutboundMediaRouteContext(
            transportId = sink.transportId,
            advisor = sink as? MessageSinkMediaDeliveryAdvisor,
        )
    }

    private fun invalidateMediaRoute(candidate: MessageSinkRouteCandidate) {
        outboundMediaService?.invalidateRoute(mediaRouteContext(candidate))
    }

    private fun handleSendResult(
        request: MessageDeliveryRequest,
        result: MessageSendResult,
        config: DeliveryConfig,
    ): DeliveryOutcome {
        return when (result) {
            is MessageSendResult.Sent -> handleSendSuccess(request, result)
            is MessageSendResult.PartiallySent -> handlePartialSend(request, result)
            is MessageSendResult.Failed -> handleSendFailure(request, result, config)
        }
    }

    private fun handleSendSuccess(
        request: MessageDeliveryRequest,
        result: MessageSendResult.Sent,
    ): DeliveryOutcome {
        MessageDeliveryRepository.markSent(
            deliveryId = request.delivery.id,
            sinkMessageId = result.sinkMessageId,
            sinkRouteId = result.sinkRouteId,
            sinkAccountId = result.sinkAccountId,
        )
        recordReceipts(request, result.receipts)
        logger.debug {
            "消息发送成功：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，sinkMessageId=${result.sinkMessageId ?: "-"}，sinkRouteId=${result.sinkRouteId ?: "-"}，sinkAccountId=${result.sinkAccountId ?: "-"}"
        }
        return DeliveryOutcome.SENT
    }

    private fun handlePartialSend(
        request: MessageDeliveryRequest,
        result: MessageSendResult.PartiallySent,
    ): DeliveryOutcome {
        MessageDeliveryRepository.markPartiallySent(
            deliveryId = request.delivery.id,
            error = result.reason.withFailurePolicySuffix("已部分发送，不再重试"),
            sinkMessageId = result.receipts.firstOrNull()?.sinkMessageId,
            sinkRouteId = result.receipts.firstOrNull()?.sinkRouteId,
            sinkAccountId = result.receipts.firstOrNull()?.sinkAccountId,
        )
        recordReceipts(request, result.receipts)
        logger.warn {
            "消息部分发送后失败：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，原因=${result.reason}"
        }
        return DeliveryOutcome.PARTIALLY_SENT
    }

    private fun recordReceipts(
        request: MessageDeliveryRequest,
        receipts: List<top.colter.dynamic.core.plugin.MessageSinkSendReceipt>,
    ) {
        runCatching {
            MessageSinkReceiptRepository.recordSent(
                delivery = request.delivery,
                message = request.message,
                receipts = receipts,
            )
        }.onFailure { error ->
            logger.warn(error) {
                "消息发送回执记录失败：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}"
            }
        }
    }

    private fun handleSendFailure(
        request: MessageDeliveryRequest,
        result: MessageSendResult.Failed,
        config: DeliveryConfig,
    ): DeliveryOutcome {
        val policy = request.message.deliveryPolicy
        val expired = policy.isExpired(nowEpochSeconds())
        val shouldRetry = !expired &&
            policy.retry &&
            result.retryable &&
            request.delivery.attempts < config.maxAttempts.coerceAtLeast(1)
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
            val reason = when {
                expired -> result.reason.withFailurePolicySuffix("消息已过期，不再重试")
                !policy.retry && result.retryable -> result.reason.withFailurePolicySuffix("消息已禁用重试")
                else -> result.reason
            }
            MessageDeliveryRepository.markFailed(request.delivery.id, reason)
            logger.warn {
                "消息发送失败，已标记失败：deliveryId=${request.delivery.id}，messageId=${request.delivery.messageId}，target=${request.target.stableValue()}，原因=${result.reason}"
            }
            DeliveryOutcome.FAILED
        }
    }

    private suspend fun resolveRoutedSinks(target: TargetAddress): RoutedSinkResolveResult {
        val routedSinkIds = mutableListOf<String>()
        val candidates = mutableListOf<MessageSinkRouteCandidate>()
        sinkProvider().forEach { handle ->
            val sink = handle.instance
            if (sink !is AccountRoutedMessageSinkPlugin || !sink.supportsTarget(target)) return@forEach
            val pluginId = handle.info.descriptor.id
            routedSinkIds += pluginId
            val routes = runCatching { sink.listMessageSinkRoutes(target) }
                .getOrElse { error ->
                    logger.warn(error) { "消息出口路线读取失败：pluginId=$pluginId，target=${target.stableValue()}" }
                    emptyList()
                }
            routes
                .filter { it.targetPlatformId == target.platformId }
                .mapTo(candidates) { route ->
                    MessageSinkRouteCandidate(pluginId = pluginId, sink = sink, route = route)
                }
        }
        return RoutedSinkResolveResult(routedSinkIds = routedSinkIds.distinct(), candidates = candidates)
    }

    private fun resolveDirectSink(target: TargetAddress): DirectSinkResolveResult {
        val matches = sinkProvider()
            .filter { handle ->
                val sink = handle.instance
                sink !is AccountRoutedMessageSinkPlugin && sink.supportsTarget(target)
            }
        return when (matches.size) {
            0 -> DirectSinkResolveResult.NotFound
            1 -> DirectSinkResolveResult.Found(matches.single().instance)
            else -> DirectSinkResolveResult.Ambiguous(matches.map { it.info.descriptor.id }.sorted())
        }
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    private fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
        if (seconds <= 0.0 && minimumMillis <= 0) return 0
        return (seconds * 1_000.0).roundToLong().coerceAtLeast(minimumMillis)
    }

    private fun wholeSeconds(seconds: Double): Long = ceil(seconds).toLong().coerceAtLeast(1)

    private fun String.withFailurePolicySuffix(suffix: String): String =
        if (isBlank()) suffix else "$this（$suffix）"

    private fun MessageSendResult.withTransportId(transportId: String): MessageSendResult {
        return when (this) {
            is MessageSendResult.Sent -> copy(
                receipts = receipts.map { receipt ->
                    receipt.copy(sinkTransportId = receipt.sinkTransportId ?: transportId)
                },
            )
            is MessageSendResult.PartiallySent -> copy(
                receipts = receipts.map { receipt ->
                    receipt.copy(sinkTransportId = receipt.sinkTransportId ?: transportId)
                },
            )
            is MessageSendResult.Failed -> this
        }
    }

    private data class RoutedSinkResolveResult(
        val routedSinkIds: List<String>,
        val candidates: List<MessageSinkRouteCandidate>,
    )

    private enum class DeliveryOutcome {
        SENT,
        PARTIALLY_SENT,
        RETRIED,
        FAILED,
    }

    private sealed interface DirectSinkResolveResult {
        data class Found(val sink: MessageSinkPlugin) : DirectSinkResolveResult
        data object NotFound : DirectSinkResolveResult
        data class Ambiguous(val pluginIds: List<String>) : DirectSinkResolveResult
    }
}
