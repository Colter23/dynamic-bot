package top.colter.dynamic.command

import top.colter.dynamic.CommandPermissionRule
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole

internal class CommandPermissionResolver(
    private val rules: List<CommandPermissionRule>,
) {
    fun resolve(context: CommandContext): CommandRole {
        return rules
            .asSequence()
            .filter { it.matches(context) }
            .map { it.role }
            .maxByOrNull { it.ordinal }
            ?: CommandRole.USER
    }

    private fun CommandPermissionRule.matches(context: CommandContext): Boolean {
        return matchesValue(platform, context.platform, ignoreCase = true) &&
            (chatType == null || chatType == context.chatType) &&
            matchesValue(chatId, context.chatId) &&
            matchesValue(senderId, context.senderId)
    }

    private fun matchesValue(pattern: String, value: String, ignoreCase: Boolean = false): Boolean {
        return pattern.isBlank() || pattern == "*" || pattern.equals(value, ignoreCase = ignoreCase)
    }
}
