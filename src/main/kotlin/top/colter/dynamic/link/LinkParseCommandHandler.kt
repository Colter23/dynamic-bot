package top.colter.dynamic.link

import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole

internal class LinkParseCommandHandler(
    private val linkParseService: LinkParseService,
    private val commandPrefixProvider: () -> String,
    private val maxLinksProvider: () -> Int = { 1 },
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("parse"),
        description = "解析并转发链接",
        usage = "parse <link...>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val input = invocation.args.joinToString(" ").trim()
        if (input.isBlank()) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }

        val result = linkParseService.parseAndDispatch(
            text = input,
            context = invocation.context,
            maxLinks = maxLinksProvider(),
            inReplyTo = invocation.replyToMessageId,
        )
        result.disabledReason?.let { return failed("链接解析失败：$it") }
        if (!result.hasSupportedLinks) return failed("未找到支持的链接")

        val successCount = result.forwarded.size + result.duplicates.size
        val failureCount = result.failures.size
        return when {
            failureCount == 0 -> success("已提交链接解析：成功 $successCount 个")
            successCount > 0 -> success(
                "已提交链接解析：成功 $successCount 个，失败 $failureCount 个\n${result.failureSummary}",
            )
            else -> failed("链接解析失败：${result.failureSummary.ifBlank { "没有链接成功解析" }}")
        }
    }

    private fun success(message: String): CommandExecutionResult {
        return CommandExecutionResult.success(message)
    }

    private fun failed(message: String): CommandExecutionResult {
        return CommandExecutionResult.failed(message)
    }
}
