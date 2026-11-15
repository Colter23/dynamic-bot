package top.colter.dynamic.publisher

import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.event.PublisherPersistenceMode
import top.colter.dynamic.core.event.SourceUpdateDeliveryTags
import top.colter.dynamic.core.event.SourceUpdateDeliveryMode
import kotlinx.coroutines.CancellationException
import top.colter.dynamic.core.event.SourceUpdatePublishRequest
import top.colter.dynamic.core.event.SourceUpdatePublishStatus
import top.colter.dynamic.core.event.SourceUpdatePublisher
import top.colter.dynamic.core.plugin.PublisherLatestUpdateProvider
import top.colter.dynamic.core.plugin.PublisherLatestUpdateRequest
import top.colter.dynamic.core.plugin.PublisherLatestUpdateResult

public sealed interface PublisherLatestUpdateDispatchResult {
    public data class Forwarded(
        val publisherName: String,
    ) : PublisherLatestUpdateDispatchResult

    public data class Empty(
        val message: String,
    ) : PublisherLatestUpdateDispatchResult

    public data class Failed(
        val message: String,
    ) : PublisherLatestUpdateDispatchResult
}

/**
 * 将平台查询到的最新更新投递到指定目标。
 *
 * 查询能力由平台插件提供；主项目统一处理按需预览的记录策略、目标路由和关联 ID。
 */
public class PublisherLatestUpdateService(
    private val providerResolver: (String) -> PublisherLatestUpdateProvider?,
    private val sourceUpdatePublisher: SourceUpdatePublisher,
) {
    public suspend fun dispatch(
        platformId: String,
        publisherInput: String,
        target: TargetAddress,
        correlationId: String? = null,
        replyToMessageId: String? = null,
    ): PublisherLatestUpdateDispatchResult {
        val normalizedPlatformId = platformId.trim()
        if (normalizedPlatformId.isBlank()) {
            return PublisherLatestUpdateDispatchResult.Failed("平台标识不能为空")
        }
        val normalizedInput = publisherInput.trim()
        if (normalizedInput.isBlank()) {
            return PublisherLatestUpdateDispatchResult.Failed("发布者标识不能为空")
        }
        val provider = providerResolver(normalizedPlatformId)
            ?: return PublisherLatestUpdateDispatchResult.Failed("未找到平台 $normalizedPlatformId 的最新更新提供者")
        val resolution = runCatching {
            provider.fetchLatestPublisherUpdate(PublisherLatestUpdateRequest(normalizedInput))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return PublisherLatestUpdateDispatchResult.Failed(error.message ?: "查询最新更新失败")
        }
        val update = when (resolution) {
            is PublisherLatestUpdateResult.Found -> resolution.update
            is PublisherLatestUpdateResult.Empty -> return PublisherLatestUpdateDispatchResult.Empty(resolution.message)
            is PublisherLatestUpdateResult.Failed -> return PublisherLatestUpdateDispatchResult.Failed(resolution.message)
        }
        val publishResult = runCatching {
            sourceUpdatePublisher.publish(
                SourceUpdatePublishRequest(
                    sourcePlugin = LATEST_UPDATE_EVENT_SOURCE,
                    update = update,
                    deliveryTargetAddress = target,
                    deliveryTag = SourceUpdateDeliveryTags.MANUAL_QUERY,
                    correlationId = correlationId,
                    replyToMessageId = replyToMessageId,
                    deliveryMode = SourceUpdateDeliveryMode.MANUAL_QUERY,
                    publisherPersistenceMode = PublisherPersistenceMode.READ_ONLY,
                ),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return PublisherLatestUpdateDispatchResult.Failed(error.message ?: "最新更新投递失败")
        }
        return when (publishResult.status) {
            SourceUpdatePublishStatus.ENQUEUED,
            SourceUpdatePublishStatus.DUPLICATE -> PublisherLatestUpdateDispatchResult.Forwarded(update.publisher.name)
            SourceUpdatePublishStatus.IGNORED,
            SourceUpdatePublishStatus.FAILED -> PublisherLatestUpdateDispatchResult.Failed(
                publishResult.message.ifBlank { "最新更新未能投递" },
            )
        }
    }

    private companion object {
        private const val LATEST_UPDATE_EVENT_SOURCE: String = "main-latest-query"
    }
}
