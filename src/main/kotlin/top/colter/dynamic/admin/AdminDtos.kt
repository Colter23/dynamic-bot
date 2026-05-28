package top.colter.dynamic.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.config.ConfigFormSpec

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
    val type: String,
    val externalId: String,
    val name: String,
    val official: String? = null,
    val state: String,
    val faceUri: String,
    val pendantUri: String? = null,
    val headerUri: String? = null,
    val createTime: Long,
    val createUser: Int,
)

@Serializable
public data class SubscriberDto(
    val id: Int,
    val platformId: String,
    val type: String,
    val targetId: String,
    val name: String,
    val state: String,
    val createTime: Long,
    val createUser: Int,
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
    val type: String,
    val targetId: String,
    val name: String,
    val sourcePluginId: String,
    val sourcePluginName: String,
)

@Serializable
public data class SubscriptionDto(
    val id: Int,
    val subscriberId: Int,
    val publisherId: Int,
    val createdAtEpochSeconds: Long,
    val subscriber: SubscriberDto? = null,
    val publisher: PublisherDto? = null,
)

@Serializable
public data class DynamicFilterRuleDto(
    val id: Int,
    val subscriptionId: Int,
    val ruleType: String,
    val matcher: String,
    val value: String,
    val enabled: Boolean,
    val createdAtEpochSeconds: Long,
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
)

@Serializable
public data class UpdateSubscriberRequest(
    val name: String? = null,
    val state: String? = null,
)

@Serializable
public data class CreateSubscriptionRequest(
    val subscriberPlatform: String,
    val subscriberType: String,
    val subscriberTargetId: String,
    val subscriberName: String? = null,
    val publisherPlatform: String,
    val publisherExternalId: String,
    val autoFollow: Boolean = true,
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
public data class CreateFilterRuleRequest(
    val subscriptionId: Int,
    val ruleType: String,
    val matcher: String,
    val value: String,
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
