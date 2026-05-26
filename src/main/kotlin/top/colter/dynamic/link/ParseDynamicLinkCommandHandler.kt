package top.colter.dynamic.link

import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandExecutionStatus
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole

internal class ParseDynamicLinkCommandHandler(
    private val forwarder: DynamicLinkForwarder,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("parse"),
        description = "parse and forward a dynamic link",
        usage = "parse <link>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val input = invocation.args.joinToString(" ").trim()
        if (input.isBlank()) {
            return failed("usage: $commandPrefix ${spec.usage}")
        }

        return when (val result = forwarder.forwardFirst(input, invocation.context, maxLinks = 1)) {
            is DynamicLinkForwardResult.Forwarded -> success("已提交转发")
            is DynamicLinkForwardResult.Duplicate -> success("已提交转发")
            is DynamicLinkForwardResult.Failed -> failed("链接解析失败: ${result.reason}")
            DynamicLinkForwardResult.NoSupportedLink -> failed("未找到支持的动态链接")
        }
    }

    private fun success(message: String): CommandExecutionResult {
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, message)
    }

    private fun failed(message: String): CommandExecutionResult {
        return CommandExecutionResult(CommandExecutionStatus.FAILED, message)
    }
}
