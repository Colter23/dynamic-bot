package top.colter.dynamic.command

import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.publisher.PublisherLatestUpdateDispatchResult
import top.colter.dynamic.publisher.PublisherLatestUpdateService

internal class PublisherLatestUpdateCommandHandler(
    private val latestUpdateService: PublisherLatestUpdateService,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("latest"),
        aliases = listOf(listOf("new"), listOf("最新")),
        description = "获取发布者最新动态",
        usage = "latest <platform> <发布者ID或链接>",
        requiredRole = CommandRole.USER,
        category = "内容",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val platformId = invocation.args.firstOrNull()?.trim().orEmpty()
        val publisherInput = invocation.args.drop(1).joinToString(" ").trim()
        if (platformId.isBlank() || publisherInput.isBlank()) {
            return CommandExecutionResult.failed("用法：${commandPrefixProvider()} ${spec.usage}")
        }
        return when (
            val result = latestUpdateService.dispatch(
                platformId = platformId,
                publisherInput = publisherInput,
                target = invocation.context.target,
                correlationId = invocation.traceId,
            )
        ) {
            is PublisherLatestUpdateDispatchResult.Forwarded -> CommandExecutionResult.success(
                "已提交 ${result.publisherName} 的最新动态",
            )
            is PublisherLatestUpdateDispatchResult.Empty -> CommandExecutionResult.failed(result.message)
            is PublisherLatestUpdateDispatchResult.Failed -> CommandExecutionResult.failed(
                "获取最新动态失败：${result.message}",
            )
        }
    }
}
