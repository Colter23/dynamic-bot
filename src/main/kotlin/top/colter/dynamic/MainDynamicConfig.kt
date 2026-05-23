package top.colter.dynamic

import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandRole

public data class MainDynamicConfig(
    val templates: Map<String, String> = mapOf(DEFAULT_TEMPLATE_NAME to DEFAULT_TEMPLATE),
    val command: CommandConfig = CommandConfig(),
) {
    public companion object {
        public const val CONFIG_ID: String = "main"
        public const val DEFAULT_TEMPLATE_NAME: String = "default"
        public const val DEFAULT_TEMPLATE: String = "{publisher.name} 发布了新动态\n{dynamic.text}\n{dynamic.link}"
    }
}

public data class CommandConfig(
    val prefix: String = "/db",
    val permissions: List<CommandPermissionRule> = emptyList(),
)

public data class CommandPermissionRule(
    val platform: String = "*",
    val chatType: ChatType? = null,
    val chatId: String = "*",
    val senderId: String = "*",
    val role: CommandRole = CommandRole.ADMIN,
)
