package top.colter.dynamic.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.SubscriptionPolicy

@Serializable
public data class ErrorResponse(
    val message: String,
)

@Serializable
public data class ActionResultResponse(
    val changed: Boolean,
    val message: String,
)

@Serializable
public data class OverviewResponse(
    val commandCount: Int,
    val subscriptionCount: Long,
    val deliveryPending: Long,
    val deliveryFailed: Long,
    val plugins: List<PluginDto>,
)

@Serializable
public data class DashboardResponse(
    val generatedAtEpochMillis: Long,
    val system: SystemStatusDto,
    val commandCount: Int,
    val publisherCount: Int,
    val subscriberCount: Int,
    val subscriptionCount: Long,
    val pluginStateCounts: List<StateCountDto>,
    val deliveryStatusCounts: List<StateCountDto>,
    val plugins: List<PluginDto>,
    val platformLogins: List<PlatformLoginDto>,
    val recentLogs: List<AdminLogEntryDto>,
    val recentDeliveries: List<MessageDeliveryDto>,
)

@Serializable
public data class StateCountDto(
    val state: String,
    val count: Long,
)

@Serializable
public data class SystemStatusDto(
    val startedAtEpochMillis: Long,
    val uptimeMs: Long,
    val javaVersion: String,
    val osName: String,
    val availableProcessors: Int,
    val usedMemoryBytes: Long,
    val freeMemoryBytes: Long,
    val totalMemoryBytes: Long,
    val maxMemoryBytes: Long,
    val databasePath: String? = null,
    val mainConfigPath: String,
    val webAdminEnabled: Boolean,
    val webAdminHost: String,
    val webAdminPort: Int,
)

@Serializable
public data class PluginDto(
    val id: String,
    val name: String,
    val version: String,
    val capabilities: List<String>,
    val state: String,
    val error: String? = null,
    val sourceJarPath: String,
    val loadTime: Long,
)

@Serializable
public data class PluginReloadResponse(
    val changed: Boolean,
    val success: Boolean,
    val pluginId: String,
    val pluginState: String? = null,
    val message: String,
    val error: String? = null,
)

@Serializable
public data class PluginLifecycleResponse(
    val changed: Boolean,
    val success: Boolean,
    val pluginId: String,
    val pluginState: String? = null,
    val message: String,
    val error: String? = null,
)

@Serializable
public data class PlatformLoginDto(
    val platformId: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginState: String,
    val supportedLoginMethods: List<String>,
    val status: String,
    val message: String,
    val account: LoginAccountDto? = null,
)

@Serializable
public data class PublisherDto(
    val id: Int,
    val platformId: String,
    val kind: String,
    val externalId: String,
    val name: String,
    val avatarBadgeKey: String? = null,
    val state: String,
    val avatarUri: String,
    val pendantUri: String? = null,
    val bannerUri: String? = null,
    val createTime: Long,
    val createUser: Int,
    val subscriptionCount: Long = 0,
    val drawTheme: PublisherDrawThemeDto? = null,
    val liveStatuses: List<PublisherLiveStatusDto> = emptyList(),
    val cursors: List<SourceCursorDto> = emptyList(),
)

@Serializable
public data class PublisherPlatformDto(
    val platformId: String,
    val pluginId: String,
    val pluginName: String,
    val pluginState: String,
)

@Serializable
public data class PublisherCandidateDto(
    val platformId: String,
    val kind: String,
    val externalId: String,
    val name: String,
    val avatarUri: String,
    val bannerUri: String? = null,
    val sourcePluginId: String,
    val sourcePluginName: String,
)

@Serializable
public data class PublisherDrawThemeDto(
    val mode: String,
    val backgroundColors: List<String>,
    val primaryColor: String,
    val linkColor: String,
    val textColor: String,
)

@Serializable
public data class SubscriberDto(
    val id: Int,
    val platformId: String,
    val targetKind: String,
    val externalId: String,
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
    val name: String,
    val avatarUri: String? = null,
    val state: String,
    val createTime: Long,
    val createUser: Int,
    val subscriptionCount: Long = 0,
    val linkParseTriggerMode: String? = null,
    val effectiveLinkParseTriggerMode: String = "MENTION_ONLY",
    val linkParseConfigSource: String = "FALLBACK",
)

@Serializable
public data class SubscriberTargetPlatformDto(
    val platformId: String,
    val pluginId: String,
    val pluginName: String,
    val pluginState: String,
    val supportedTypes: List<String>,
)

@Serializable
public data class SubscriberTargetDto(
    val platformId: String,
    val targetKind: String,
    val externalId: String,
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
    val name: String,
    val avatarUri: String? = null,
    val sourcePluginId: String,
    val sourcePluginName: String,
)

@Serializable
public data class SubscriptionDto(
    val id: Int,
    val subscriberId: Int,
    val publisherId: Int,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val state: String,
    val policy: SubscriptionPolicy,
    val subscriber: SubscriberDto? = null,
    val publisher: PublisherDto? = null,
    val filterRuleCount: Int = 0,
    val filterRules: List<DynamicFilterRuleDto> = emptyList(),
)

@Serializable
public data class DynamicFilterRuleDto(
    val id: Int,
    val subscriptionId: Int,
    val condition: FilterCondition,
    val createdAtEpochSeconds: Long,
)

@Serializable
public data class SourceCursorDto(
    val publisherId: Int,
    val sourceKey: String,
    val eventType: String,
    val lastSeenUpdateKey: String,
    val lastSeenAtEpochSeconds: Long,
)

@Serializable
public data class PublisherLiveStatusDto(
    val publisherId: Int,
    val roomId: String,
    val status: String,
    val title: String,
    val coverUri: String? = null,
    val area: String? = null,
    val startedAtEpochSeconds: Long? = null,
    val lastObservedAtEpochSeconds: Long,
)

@Serializable
public data class MessageDeliveryDto(
    val id: Int,
    val messageId: String,
    val sourceUpdateKey: String? = null,
    val renderVariant: String? = null,
    val platformId: String,
    val targetKind: String,
    val targetId: String,
    val targetKey: String,
    val status: String,
    val attempts: Int,
    val sinkMessageId: String? = null,
    val lastError: String? = null,
    val nextAttemptAtEpochSeconds: Long? = null,
    val lockedUntilEpochSeconds: Long? = null,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

@Serializable
public data class AdminLogEntryDto(
    val seq: Long,
    val timestampEpochMillis: Long,
    val level: String,
    val loggerName: String,
    val threadName: String,
    val message: String,
    val throwable: String? = null,
)

@Serializable
public data class AdminLogResponse(
    val entries: List<AdminLogEntryDto>,
    val nextSince: Long,
    val capacity: Int,
)

@Serializable
public data class ConfigSummaryDto(
    val id: String,
    val name: String,
    val description: String = "",
    val pluginId: String? = null,
    val pluginName: String? = null,
    val pluginState: String? = null,
    val sourcePath: String,
)

@Serializable
public data class ConfigDetailDto(
    val id: String,
    val name: String,
    val description: String = "",
    val pluginId: String? = null,
    val pluginName: String? = null,
    val pluginState: String? = null,
    val sourcePath: String,
    val schema: ConfigFormSpec,
    val values: JsonObject,
    val secretStates: Map<String, Boolean> = emptyMap(),
)

@Serializable
public data class ConfigSecretValueResponse(
    val path: String,
    val value: JsonElement,
)

@Serializable
public data class UpdateConfigRequest(
    val values: JsonObject,
)

@Serializable
public data class UpdateConfigResponse(
    val changed: Boolean,
    val restartRequired: Boolean,
    val restartTargets: List<String> = emptyList(),
    val message: String,
    val pluginId: String? = null,
    val values: JsonObject,
    val secretStates: Map<String, Boolean> = emptyMap(),
)

@Serializable
public data class UpdatePublisherRequest(
    val name: String? = null,
    val headerUri: String? = null,
    val state: String? = null,
    val themeColors: String? = null,
    val clearTheme: Boolean = false,
)

@Serializable
public data class CreatePublisherRequest(
    val platformId: String,
    val externalId: String,
)

@Serializable
public data class UpdateSubscriberRequest(
    val name: String? = null,
    val state: String? = null,
    val linkParseTriggerMode: String? = null,
    val clearLinkParseTrigger: Boolean = false,
)

@Serializable
public data class CreateSubscriberRequest(
    val platformId: String,
    val targetKind: String,
    val externalId: String,
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
    val name: String? = null,
    val state: String? = null,
    val linkParseTriggerMode: String? = null,
)

@Serializable
public data class CreateSubscriptionRequest(
    val subscriberPlatform: String? = null,
    val targetKind: String? = null,
    val subscriberTargetId: String? = null,
    val subscriberId: Int? = null,
    val subscriberScopeId: String? = null,
    val subscriberThreadId: String? = null,
    val subscriberAccountId: String? = null,
    val subscriberLinkParseTriggerMode: String? = null,
    val publisherPlatform: String? = null,
    val publisherExternalId: String? = null,
    val publisherId: Int? = null,
    val autoFollow: Boolean = true,
    val policy: SubscriptionPolicy = SubscriptionPolicy.default(),
)

@Serializable
public data class CreateSubscriptionResponse(
    val subscription: SubscriptionDto,
    val publisherCreated: Boolean,
    val publisherUpdated: Boolean,
    val subscriberCreated: Boolean,
    val subscriberUpdated: Boolean,
    val subscriptionCreated: Boolean,
    val autoFollowed: Boolean,
)

@Serializable
public data class UpdateSubscriptionRequest(
    val policy: SubscriptionPolicy = SubscriptionPolicy.default(),
)

@Serializable
public data class CreateFilterRuleRequest(
    val condition: FilterCondition,
)

@Serializable
public data class CookieLoginRequest(
    val cookie: String,
)

@Serializable
public data class LoginAccountDto(
    val userId: String? = null,
    val name: String? = null,
)

@Serializable
public data class LoginResultDto(
    val status: String,
    val message: String,
    val account: LoginAccountDto? = null,
)

@Serializable
public data class QrLoginStartResponse(
    val loginId: String,
    val imageUrl: String,
    val status: String,
    val message: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)

@Serializable
public data class QrLoginStatusResponse(
    val loginId: String,
    val platform: String,
    val status: String,
    val message: String,
    val account: LoginAccountDto? = null,
    val expiresAtEpochSeconds: Long? = null,
)
