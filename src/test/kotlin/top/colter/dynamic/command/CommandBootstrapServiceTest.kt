package top.colter.dynamic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.TargetKind

class CommandBootstrapServiceTest {

    private var config: MainDynamicConfig = MainDynamicConfig()
    private var nowMillis: Long = 1_000_000L
    private var nextCode: String = "123456"

    private fun service(): CommandBootstrapService {
        return CommandBootstrapService(
            configProvider = { config },
            configUpdater = { transform ->
                config = transform(config)
                ConfigApplyResult(changed = true)
            },
            clock = { nowMillis },
            codeGenerator = { nextCode },
        )
    }

    private fun context(
        kind: TargetKind = TargetKind.USER,
        senderId: String = "2709800974",
    ): CommandContext {
        return CommandContext.of(
            platform = "onebot",
            kind = kind,
            externalId = if (kind == TargetKind.USER) senderId else "806819101",
            senderId = senderId,
        )
    }

    @Test
    fun snapshotGeneratesSixDigitCode(): Unit = runBlocking {
        val random = CommandBootstrapService(
            configProvider = { config },
            configUpdater = { ConfigApplyResult(changed = false) },
            clock = { nowMillis },
        )
        val snapshot = random.snapshot()
        assertEquals(CommandBootstrapStatus.PENDING, snapshot.status)
        assertTrue(snapshot.code.orEmpty().matches(Regex("\\d{6}")), "code=${snapshot.code}")
        assertEquals(CommandBootstrapService.CODE_TTL_MILLIS / 1000, snapshot.remainingSeconds)
    }

    @Test
    fun normalizeAcceptsSpacesDashesAndFullWidthDigits() {
        assertEquals("123456", CommandBootstrapService.normalizeCode(" 12-34 56 "))
        assertEquals("123456", CommandBootstrapService.normalizeCode("１２３４５６"))
        assertNull(CommandBootstrapService.normalizeCode("12345"))
        assertNull(CommandBootstrapService.normalizeCode("1234567"))
        assertNull(CommandBootstrapService.normalizeCode("abc123456"))
    }

    @Test
    fun claimWritesAdminRuleAndInvalidatesCode(): Unit = runBlocking {
        val service = service()
        assertEquals(CommandBootstrapStatus.PENDING, service.snapshot().status)

        val outcome = service.claim(context(), "123456")
        assertEquals(CommandClaimOutcome.CLAIMED, outcome)

        val rule = config.command.permissions.singleOrNull()
        assertNotNull(rule)
        assertEquals(CommandRole.ADMIN, rule.role)
        assertEquals("2709800974", rule.senderId)
        assertEquals("onebot", rule.platformId)
        assertEquals("*", rule.commandPath)

        val after = service.snapshot()
        assertEquals(CommandBootstrapStatus.CLAIMED, after.status)
        assertNull(after.code)
    }

    @Test
    fun secondClaimIsRejectedAfterInitialized(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        assertEquals(CommandClaimOutcome.CLAIMED, service.claim(context(senderId = "s1"), "123456"))
        val outcome = service.claim(context(senderId = "s2"), "123456")
        assertEquals(CommandClaimOutcome.ALREADY_INITIALIZED, outcome)
        assertEquals(1, config.command.permissions.size)
    }

    @Test
    fun expiredCodeIsRejectedAndReported(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        nowMillis += CommandBootstrapService.CODE_TTL_MILLIS + 1

        assertEquals(CommandBootstrapStatus.EXPIRED, service.snapshot().status)
        assertEquals(CommandClaimOutcome.EXPIRED, service.claim(context(), "123456"))
        assertTrue(config.command.permissions.isEmpty())
    }

    @Test
    fun wrongCodeDoesNotInvalidatePendingCode(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        assertEquals(CommandClaimOutcome.INVALID, service.claim(context(), "000000"))
        assertEquals(CommandClaimOutcome.CLAIMED, service.claim(context(), "123456"))
    }

    @Test
    fun attemptsAreRateLimitedPerSender(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        repeat(CommandBootstrapService.PER_SENDER_ATTEMPT_LIMIT) {
            assertEquals(CommandClaimOutcome.INVALID, service.claim(context(), "000000"))
        }
        assertEquals(CommandClaimOutcome.RATE_LIMITED, service.claim(context(), "123456"))
        assertTrue(config.command.permissions.isEmpty())
    }

    @Test
    fun groupChatCannotClaim(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        val outcome = service.claim(context(kind = TargetKind.GROUP), "123456")
        assertEquals(CommandClaimOutcome.INVALID, outcome)
        assertTrue(config.command.permissions.isEmpty())
    }

    @Test
    fun rotateReplacesCode(): Unit = runBlocking {
        val service = service()
        assertEquals("123456", service.snapshot().code)

        nextCode = "654321"
        nowMillis += 60_000
        val rotated = service.rotate()
        assertEquals("654321", rotated.code)
        assertEquals(CommandBootstrapService.CODE_TTL_MILLIS / 1000, rotated.remainingSeconds)

        assertEquals(CommandClaimOutcome.INVALID, service.claim(context(), "123456"))
        assertEquals(CommandClaimOutcome.CLAIMED, service.claim(context(), "654321"))
    }

    @Test
    fun rotateResetsAttemptRateLimit(): Unit = runBlocking {
        val service = service()
        service.snapshot()
        repeat(CommandBootstrapService.PER_SENDER_ATTEMPT_LIMIT) {
            assertEquals(CommandClaimOutcome.INVALID, service.claim(context(), "000000"))
        }
        assertEquals(CommandClaimOutcome.RATE_LIMITED, service.claim(context(), "123456"))

        nextCode = "654321"
        val rotated = service.rotate()
        assertEquals("654321", rotated.code)
        assertEquals(0, rotated.recentAttempts)

        // 重新生成后不应再被旧码的失败次数挡住
        assertEquals(CommandClaimOutcome.CLAIMED, service.claim(context(), "654321"))
    }

    @Test
    fun existingAdminRuleCountsAsInitialized(): Unit = runBlocking {
        config = config.copy(
            command = config.command.copy(
                permissions = listOf(
                    CommandPermissionRule(platformId = "onebot", senderId = "42", role = CommandRole.ADMIN),
                ),
            ),
        )
        val service = service()
        val snapshot = service.snapshot()
        assertEquals(CommandBootstrapStatus.CLAIMED, snapshot.status)
        assertNull(snapshot.code)
        assertEquals(1, snapshot.admins.size)

        assertEquals(CommandClaimOutcome.ALREADY_INITIALIZED, service.claim(context(), "123456"))
    }
}
