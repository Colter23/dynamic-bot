package top.colter.dynamic.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.data.DynamicFilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.ParsedLink

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
public data class AdminTestPreviewRequest(
    val mode: String = "MOCK",
    val mockEventType: String = "DYNAMIC",
    val presetId: String? = null,
    val presetOptions: AdminTestPresetOptions = AdminTestPresetOptions(),
    val customUpdate: SourceUpdate? = null,
    val link: String? = null,
    val template: String? = null,
)

@Serializable
public data class AdminTestPresetsResponse(
    val generatedAtEpochMillis: Long,
    val defaultPresetId: String,
    val presets: List<AdminTestPresetDto>,
)

@Serializable
public data class AdminTestPresetDto(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val eventType: String,
    val recommended: Boolean = false,
    val tags: List<String> = emptyList(),
    val defaultOptions: AdminTestPresetOptions = AdminTestPresetOptions(),
)

@Serializable
public data class AdminTestPresetOptions(
    val textVariant: String? = null,
    val imageCount: Int? = null,
    val imageRatio: String? = null,
    val includeVideoCard: Boolean? = null,
    val includeArticleCard: Boolean? = null,
    val includeAdditionalCard: Boolean? = null,
    val includeRepost: Boolean? = null,
    val themeColors: String? = null,
)

@Serializable
public data class AdminTestPreviewResponse(
    val mode: String,
    val status: String,
    val message: String,
    val elapsedMillis: Long,
    val parsedLink: ParsedLink? = null,
    val resolutionType: String? = null,
    val template: String = "",
    val templateSource: String = "",
    val batches: List<MessageBatch> = emptyList(),
    val drawImage: MediaRef? = null,
    val media: List<AdminTestMediaDto> = emptyList(),
    val update: SourceUpdate? = null,
    val preview: LinkPreview? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
public data class AdminTestMediaDto(
    val kind: String,
    val uri: String,
    val source: String,
    val alt: String? = null,
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
public data class TaskListResponse(
    val generatedAtEpochMillis: Long,
    val tasks: List<TaskDto>,
    val statusCounts: List<StateCountDto>,
)

@Serializable
public data class TaskDto(
    val key: String,
    val ownerType: String,
    val ownerId: String,
    val ownerName: String,
    val pluginVersion: String? = null,
    val pluginState: String? = null,
    val id: String,
    val name: String,
    val description: String = "",
    val status: String,
    val scheduleType: String,
    val scheduleText: String,
    val runImmediately: Boolean? = null,
    val retryBackoffMillis: Long? = null,
    val nextRunAtMillis: Long? = null,
    val lastRunAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val runCount: Long,
    val lastErrorSummary: String? = null,
    val canStart: Boolean,
    val canStop: Boolean,
    val canRestart: Boolean,
)

@Serializable
public data class TaskOperationResponse(
    val changed: Boolean,
    val ownerType: String,
    val ownerId: String,
    val taskId: String,
    val status: String? = null,
    val message: String,
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
    val catalogVersion: String? = null,
    val updateAvailable: Boolean = false,
    val catalogStatus: String? = null,
)

@Serializable
public data class PluginCatalogResponse(
    val schemaVersion: Int,
    val fetchedAtEpochMillis: Long,
    val cacheExpiresAtEpochMillis: Long,
    val source: String,
    val sourceUrl: String? = null,
    val warning: String? = null,
    val plugins: List<PluginCatalogEntryDto>,
)

@Serializable
public data class PluginCatalogEntryDto(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val apiVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val homepageUrl: String? = null,
    val releaseNotesUrl: String? = null,
    val capabilities: List<String> = emptyList(),
    val installedVersion: String? = null,
    val installedState: String? = null,
    val catalogStatus: String,
    val updateAvailable: Boolean = false,
    val error: String? = null,
)

@Serializable
public data class PluginCatalogOperationResponse(
    val changed: Boolean,
    val success: Boolean,
    val pluginId: String,
    val pluginState: String? = null,
    val message: String,
    val installedVersion: String? = null,
    val catalogVersion: String? = null,
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
public data class PluginAdminPagesResponse(
    val pages: List<PluginAdminPageDto>,
)

@Serializable
public data class PluginAdminPageDto(
    val key: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginState: String,
    val pageId: String,
    val title: String,
    val subtitle: String = "",
    val navGroup: String,
    val navIcon: String,
    val htmlPath: String,
    val scriptPath: String,
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
    val checkedAtEpochMillis: Long,
    val actions: List<PlatformLoginActionDto>,
    val account: LoginAccountDto? = null,
)

@Serializable
public data class TargetPlatformAccountDto(
    val platformId: String,
    val transportId: String,
    val transportName: String,
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val pluginState: String,
    val routeId: String,
    val accountId: String,
    val accountName: String,
    val avatarUri: String? = null,
    val enabled: Boolean,
    val state: String,
    val checkedAtEpochMillis: Long,
)

@Serializable
public data class CommandDto(
    val path: List<String>,
    val pathText: String,
    val aliases: List<List<String>> = emptyList(),
    val description: String = "",
    val usage: String = "",
    val requiredRole: String,
)

@Serializable
public data class PlatformLoginActionDto(
    val key: String,
    val label: String,
    val description: String = "",
    val enabled: Boolean,
    val reason: String? = null,
    val sensitive: Boolean = false,
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
    val liveSubscriptionCount: Long = 0,
    val drawTheme: PublisherDrawThemeDto? = null,
    val liveStatuses: List<PublisherLiveStatusDto> = emptyList(),
    val liveRecords: List<PublisherLiveRecordDto> = emptyList(),
    val cursors: List<SourceCursorDto> = emptyList(),
)

@Serializable
public data class PublisherPlatformDto(
    val platformId: String,
    val pluginId: String,
    val pluginName: String,
    val pluginState: String,
    val capabilities: List<String> = emptyList(),
    val supportsLive: Boolean = false,
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
    val readableAccentColor: String = primaryColor,
    val onPrimaryColor: String = "#FFFFFF",
    val qrPointColor: String = primaryColor,
    val cardColor: String = "#C2FFFFFF",
    val borderColor: String = "#E0FFFFFF",
    val linkColor: String,
    val textColor: String,
    val secondaryTextColor: String = textColor,
    val mutedTextColor: String = secondaryTextColor,
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
    val transportIds: List<String> = emptyList(),
    val transportCount: Int = transportIds.size,
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
    val sourceCount: Int = 0,
    val sources: List<SubscriberTargetSourceDto> = emptyList(),
)

@Serializable
public data class SubscriberTargetSourceDto(
    val routeId: String,
    val transportId: String,
    val transportName: String,
    val accountId: String,
    val accountName: String,
    val state: String,
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
    val action: DynamicFilterAction = DynamicFilterAction.BLOCK,
    val condition: FilterCondition,
    val createdAtEpochSeconds: Long,
)

@Serializable
public data class SubscriptionExportRequest(
    val subscriptionIds: List<Int>? = null,
)

@Serializable
public data class SubscriptionExportDocument(
    val schemaVersion: Int = 2,
    val exportedAtEpochSeconds: Long,
    val subscriptions: List<SubscriptionExportItem>,
    val importOptions: SubscriptionImportOptions? = null,
)

@Serializable
public data class SubscriptionImportOptions(
    val fetchProfiles: Boolean = true,
    val autoFollowPublishers: Boolean = true,
)

@Serializable
public data class SubscriptionExportItem(
    val publisher: SubscriptionExportPublisher,
    val target: SubscriptionExportTarget,
    val policy: SubscriptionPolicy = SubscriptionPolicy.default(),
    val filterRules: List<SubscriptionExportFilterRule> = emptyList(),
    val linkParseTriggerMode: String? = null,
    val publisherLookupMode: String? = null,
)

@Serializable
public data class SubscriptionExportPublisher(
    val platformId: String,
    val kind: String = "USER",
    val externalId: String,
    val name: String? = null,
    val themeColors: String? = null,
)

@Serializable
public data class SubscriptionExportTarget(
    val platformId: String,
    val targetKind: String,
    val externalId: String,
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
)

@Serializable
public data class SubscriptionExportFilterRule(
    val action: DynamicFilterAction = DynamicFilterAction.BLOCK,
    val condition: FilterCondition,
)

@Serializable
public data class SubscriptionImportResponse(
    val total: Int,
    val created: Int,
    val updated: Int,
    val failed: Int,
    val skipped: Int = 0,
    val duplicateCount: Int = 0,
    val items: List<SubscriptionImportItemResult>,
    val warnings: List<String> = emptyList(),
)

@Serializable
public data class LegacyDynamicSubscriptionImportRequest(
    val content: String,
    val importOptions: SubscriptionImportOptions? = null,
)

@Serializable
public data class SubscriptionImportItemResult(
    val index: Int,
    val status: String,
    val message: String,
    val publisherKey: String,
    val targetKey: String,
    val filterRuleCount: Int = 0,
    val subscription: SubscriptionDto? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
public data class SourceCursorDto(
    val publisherId: Int,
    val sourceKey: String,
    val eventType: String,
    val lastSeenUpdateKey: String,
    val lastSeenAtEpochSeconds: Long,
    val recentUpdateKeys: List<String> = emptyList(),
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
public data class PublisherLiveRecordDto(
    val id: Int,
    val publisherId: Int,
    val roomId: String,
    val title: String,
    val coverUri: String? = null,
    val area: String? = null,
    val startedAtEpochSeconds: Long,
    val endedAtEpochSeconds: Long? = null,
    val durationSeconds: Long? = null,
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
    val targetScopeId: String? = null,
    val targetThreadId: String? = null,
    val targetAccountId: String? = null,
    val targetKey: String,
    val status: String,
    val attempts: Int,
    val sinkMessageId: String? = null,
    val sinkRouteId: String? = null,
    val sinkAccountId: String? = null,
    val lastError: String? = null,
    val nextAttemptAtEpochSeconds: Long? = null,
    val lockedUntilEpochSeconds: Long? = null,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

@Serializable
public data class MessageDeliveryDetailDto(
    val delivery: MessageDeliveryDto,
    val message: JsonElement? = null,
)

@Serializable
public data class ForwardTargetRequest(
    val platformId: String,
    val targetKind: String,
    val externalId: String,
    val scopeId: String? = null,
    val threadId: String? = null,
    val accountId: String? = null,
)

@Serializable
public data class CreateMessageForwardRequest(
    val targets: List<ForwardTargetRequest>,
    val text: String? = null,
    val batches: List<MessageBatch>? = null,
)

@Serializable
public data class MessageForwardResponse(
    val messageId: String,
    val targetCount: Int,
    val newDeliveryCount: Int,
    val existingDeliveryCount: Int,
    val deliveries: List<MessageForwardDeliveryDto>,
)

@Serializable
public data class MessageForwardDeliveryDto(
    val deliveryId: Int,
    val targetKey: String,
    val platformId: String,
    val targetKind: String,
    val targetId: String,
    val accountId: String? = null,
    val status: String,
    val newDelivery: Boolean,
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
    val retainedCount: Int,
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
    val autoFollow: Boolean = false,
)

@Serializable
public data class CreatePublisherResponse(
    val publisher: PublisherDto,
    val autoFollowed: Boolean = false,
    val warnings: List<String> = emptyList(),
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
    val publisherKind: String? = null,
    val publisherExternalId: String? = null,
    val publisherName: String? = null,
    val publisherThemeColors: String? = null,
    val publisherId: Int? = null,
    val policy: SubscriptionPolicy = SubscriptionPolicy.default(),
    val publisherLookupMode: String? = null,
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
    val warnings: List<String> = emptyList(),
)

@Serializable
public data class UpdateSubscriptionRequest(
    val policy: SubscriptionPolicy = SubscriptionPolicy.default(),
)

@Serializable
public data class CreateFilterRuleRequest(
    val action: DynamicFilterAction = DynamicFilterAction.BLOCK,
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
    val avatarUri: String? = null,
)

@Serializable
public data class CookieExportResponse(
    val platformId: String,
    val cookie: String,
    val format: String = "JSON",
    val message: String = "Cookie 已导出",
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
    val instruction: String? = null,
    val validityHint: String? = null,
    val statusPollIntervalMillis: Long? = null,
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
