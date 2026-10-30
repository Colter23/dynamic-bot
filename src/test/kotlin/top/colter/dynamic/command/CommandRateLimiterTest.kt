package top.colter.dynamic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.TargetKind

class CommandRateLimiterTest {

    private var nowMillis: Long = 1_000_000L

    private fun handler(perSender: Int = 2, perTarget: Int = 10): RateLimitedCommandHandler {
        val delegate = object : CommandHandler {
            override val spec: CommandSpec = CommandSpec(
                path = listOf("parse"),
                description = "解析链接",
                usage = "parse <link>",
                requiredRole = CommandRole.USER,
            )

            override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
                return CommandExecutionResult.success("ok")
            }
        }
        return RateLimitedCommandHandler(
            delegate = delegate,
            limiter = CommandRateLimiter { nowMillis },
            perSenderLimit = perSender,
            perTargetLimit = perTarget,
            clock = { nowMillis },
        )
    }

    private fun invocation(senderId: String = "s1", chatId: String = "100"): CommandInvocation {
        return CommandInvocation(
            sourcePlugin = "main",
            context = CommandContext.of("onebot", TargetKind.GROUP, chatId, senderId),
            rawText = "/db parse https://example.com",
            traceId = "trace",
            replyToMessageId = "",
            tokens = listOf("parse"),
            matchedPath = listOf("parse"),
            args = emptyList(),
            role = CommandRole.USER,
        )
    }

    @Test
    fun shouldRejectAfterSenderLimitAndNotifyOnlyOnce(): Unit = runBlocking {
        val handler = handler(perSender = 2)

        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation()).status)
        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation()).status)

        val limited = handler.handle(invocation())
        assertEquals(CommandStatus.FAILED, limited.status)
        assertTrue(limited.reply.isNotEmpty())

        val again = handler.handle(invocation())
        assertTrue(again.reply.isEmpty(), "同一会话 5 分钟内只提示一次")
    }

    @Test
    fun otherSenderIsNotAffected(): Unit = runBlocking {
        val handler = handler(perSender = 1)

        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation(senderId = "a")).status)
        assertEquals(CommandStatus.FAILED, handler.handle(invocation(senderId = "a")).status)
        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation(senderId = "b")).status)
    }

    @Test
    fun windowExpiryRestoresQuota(): Unit = runBlocking {
        val handler = handler(perSender = 1)

        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation()).status)
        assertEquals(CommandStatus.FAILED, handler.handle(invocation()).status)

        nowMillis += RateLimitedCommandHandler.DEFAULT_WINDOW_MILLIS + 1_000
        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation()).status)
    }

    @Test
    fun targetLimitAppliesToWholeChat(): Unit = runBlocking {
        val handler = handler(perSender = 10, perTarget = 2)

        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation(senderId = "a")).status)
        assertEquals(CommandStatus.SUCCESS, handler.handle(invocation(senderId = "b")).status)
        assertEquals(CommandStatus.FAILED, handler.handle(invocation(senderId = "c")).status)
    }
}
