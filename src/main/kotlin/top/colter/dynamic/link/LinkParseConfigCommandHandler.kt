package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.repository.LinkParseTargetConfigRepository

internal object LinkParseConfigCommandHandler {
    fun handlers(
        configProvider: () -> MainDynamicConfig,
        commandPrefixProvider: () -> String,
    ): List<CommandHandler> = listOf(
        LinkParseStatusCommandHandler(configProvider, commandPrefixProvider),
        LinkParseSetCommandHandler(commandPrefixProvider),
        LinkParseClearCommandHandler(commandPrefixProvider),
    )
}

private class LinkParseStatusCommandHandler(
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("link"),
        aliases = listOf(listOf("link", "status")),
        description = "查看当前会话的链接解析触发方式",
        usage = "link",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.isNotEmpty()) return failedUsage()
        val config = configProvider().linkParsing
        val targetConfig = LinkParseTargetConfigRepository.findEffectiveByAddress(invocation.context.target)
        val activeMode = targetConfig?.triggerMode ?: config.fallbackTriggerMode
        val source = if (targetConfig == null) "全局回退" else "当前会话配置"
        val progress = config.progressReply
        val lines = listOf(
            "自动链接解析：${enabledText(config.autoParseEnabled)}",
            "当前会话触发方式：${activeMode.label()}（$source）",
            "全局回退触发方式：${config.fallbackTriggerMode.label()}",
            "解析中提示：${progress.text.ifBlank { "关闭" }}",
            "完成后撤回提示：${enabledText(progress.recallOnComplete)}",
            "当前会话目标：${invocation.context.target.stableValue()}",
        )
        return CommandExecutionResult.success(lines.joinToString("\n"))
    }

    private fun failedUsage(): CommandExecutionResult {
        return CommandExecutionResult.failed("用法：${commandPrefixProvider()} ${spec.usage}")
    }
}

private class LinkParseSetCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("link", "set"),
        description = "设置当前会话的链接解析触发方式",
        usage = "link set <off|mention|always>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 1) return failedUsage()
        val mode = parseMode(invocation.args.single())
            ?: return CommandExecutionResult.failed("未知触发方式：${invocation.args.single()}，可用：off、mention、always")
        val result = LinkParseTargetConfigRepository.upsert(
            address = invocation.context.target,
            triggerMode = mode,
            updatedBy = invocation.context.senderId,
        )
        val state = when {
            result.created -> "已创建"
            result.updated -> "已更新"
            else -> "未变化"
        }
        return CommandExecutionResult.success("链接解析触发方式$state：${mode.label()}")
    }

    private fun failedUsage(): CommandExecutionResult {
        return CommandExecutionResult.failed("用法：${commandPrefixProvider()} ${spec.usage}")
    }
}

private class LinkParseClearCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("link", "clear"),
        description = "清除当前会话的链接解析配置",
        usage = "link clear",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.isNotEmpty()) return failedUsage()
        val removed = LinkParseTargetConfigRepository.deleteByAddress(invocation.context.target)
        val message = if (removed) {
            "已清除当前会话链接解析配置，后续使用全局回退触发方式"
        } else {
            "当前会话没有单独的链接解析配置"
        }
        return CommandExecutionResult.success(message)
    }

    private fun failedUsage(): CommandExecutionResult {
        return CommandExecutionResult.failed("用法：${commandPrefixProvider()} ${spec.usage}")
    }
}

private fun parseMode(value: String): LinkParseTriggerMode? {
    return when (value.lowercase()) {
        "off", "disabled", "disable", "none" -> LinkParseTriggerMode.DISABLED
        "mention", "at" -> LinkParseTriggerMode.MENTION_ONLY
        "always", "on" -> LinkParseTriggerMode.ALWAYS
        else -> null
    }
}

private fun LinkParseTriggerMode.label(): String {
    return when (this) {
        LinkParseTriggerMode.DISABLED -> "不解析"
        LinkParseTriggerMode.MENTION_ONLY -> "必须 @bot 才解析"
        LinkParseTriggerMode.ALWAYS -> "匹配到链接就解析"
    }
}

private fun enabledText(enabled: Boolean): String = if (enabled) "开启" else "关闭"
