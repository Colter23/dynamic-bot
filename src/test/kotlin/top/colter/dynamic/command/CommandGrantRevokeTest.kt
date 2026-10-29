package top.colter.dynamic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.TargetKind

class CommandGrantRevokeTest {

    private var config: MainDynamicConfig = MainDynamicConfig()

    private fun updater(): ((MainDynamicConfig) -> MainDynamicConfig) -> ConfigApplyResult {
        return { transform ->
            val next = transform(config)
            val changed = next != config
            config = next
            ConfigApplyResult(changed = changed)
        }
    }

    private fun invocation(args: List<String>): CommandInvocation {
        return CommandInvocation(
            sourcePlugin = "main",
            context = CommandContext.of("onebot", TargetKind.GROUP, "100", "sender"),
            rawText = "/db grant",
            traceId = "trace",
            replyToMessageId = "",
            tokens = listOf("grant") + args,
            matchedPath = listOf("grant"),
            args = args,
            role = CommandRole.ADMIN,
        )
    }

    @Test
    fun grantAddsRuleAndRevokeRemovesIt(): Unit = runBlocking {
        val grant = GrantCommandHandler(updater(), { "/db" })
        val revoke = RevokeCommandHandler(updater(), { "/db" })

        val granted = grant.handle(invocation(listOf("onebot", "42", "MANAGER")))
        assertEquals(CommandStatus.SUCCESS, granted.status)
        val rule = config.command.permissions.single()
        assertEquals(CommandRole.MANAGER, rule.role)
        assertEquals("42", rule.senderId)
        assertEquals("onebot", rule.platformId)

        // 重复授予不应新增规则
        val again = grant.handle(invocation(listOf("onebot", "42", "MANAGER")))
        assertEquals(CommandStatus.SUCCESS, again.status)
        assertEquals(1, config.command.permissions.size)

        val revoked = revoke.handle(invocation(listOf("onebot", "42")))
        assertEquals(CommandStatus.SUCCESS, revoked.status)
        assertTrue(config.command.permissions.isEmpty())
    }

    @Test
    fun grantRejectsWildcardSenderAndInvalidRole(): Unit = runBlocking {
        val grant = GrantCommandHandler(updater(), { "/db" })

        assertEquals(CommandStatus.FAILED, grant.handle(invocation(listOf("onebot", "*", "ADMIN"))).status)
        assertEquals(CommandStatus.FAILED, grant.handle(invocation(listOf("onebot", "42", "SUPERUSER"))).status)
        assertEquals(CommandStatus.FAILED, grant.handle(invocation(listOf("onebot", "42", "NONE"))).status)
        assertTrue(config.command.permissions.isEmpty())
    }

    @Test
    fun revokeReportsMissingRuleWithoutTouchingConfig(): Unit = runBlocking {
        val revoke = RevokeCommandHandler(updater(), { "/db" })

        val result = revoke.handle(invocation(listOf("onebot", "nobody")))

        assertEquals(CommandStatus.SUCCESS, result.status)
        assertTrue(config.command.permissions.isEmpty())
    }
}
