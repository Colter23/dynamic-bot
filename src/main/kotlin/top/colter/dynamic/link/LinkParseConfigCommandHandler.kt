package top.colter.dynamic.link

import top.colter.dynamic.LinkParseTriggerMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.repository.LinkParseTargetConfigRepository

internal class LinkParseConfigCommandHandler(
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("link"),
        description = "查看或设置当前会话的链接解析触发方式",
        usage = "link <status|set|clear> [off|mention|always]",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val action = invocation.args.firstOrNull()?.lowercase() ?: "status"
        return when (action) {
            "status" -> status(invocation)
            "set" -> set(invocation)
            "clear" -> clear(invocation)
            else -> failedUsage()
        }
    }

    private fun status(invocation: CommandInvocation): CommandExecutionResult {
        val config = configProvider().linkParsing
        val targetConfig = LinkParseTargetConfigRepository.findByAddress(invocation.context.target)
        val activeMode = targetConfig?.triggerMode ?: config.fallbackTriggerMode
        val source = if (targetConfig == null) "全局回退" else "当前会话配置"
        val progress = config.progressReply
        val lines = listOf(
            "自动链接解析：${enabledText(config.autoParseEnabled)}",
            "当前会话触发方式：${activeMode.label()}（$source）",
            "全局回退触发方式：${config.fallbackTriggerMode.label()}",
            "解析中提示：${enabledText(progress.enabled)}",
            "解析中提示文字：${progress.text}",
            "完成后撤回提示：${enabledText(progress.recallOnComplete)}",
            "当前会话目标：${invocation.context.target.stableValue()}",
        )
        return CommandExecutionResult.success(lines.joinToString("\n"))
    }

    private fun set(invocation: CommandInvocation): CommandExecutionResult {
        if (!invocation.role.satisfies(CommandRole.ADMIN)) {
            return CommandExecutionResult.rejected("权限不足")
        }
        if (invocation.args.size != 2) return failedUsage()
        val mode = parseMode(invocation.args[1])
            ?: return CommandExecutionResult.failed("未知触发方式：${invocation.args[1]}，可用：off、mention、always")
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

    private fun clear(invocation: CommandInvocation): CommandExecutionResult {
        if (!invocation.role.satisfies(CommandRole.ADMIN)) {
            return CommandExecutionResult.rejected("权限不足")
        }
        if (invocation.args.size != 1) return failedUsage()
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
}
