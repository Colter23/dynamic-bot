package top.colter.dynamic.command

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.NoopSystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublisher
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.tools.loggerFor

private val bootstrapLogger = loggerFor<CommandBootstrapService>()

/** 命令系统初始化状态。 */
public enum class CommandBootstrapStatus {
    /** 还没有系统管理员，且验证码仍然有效。 */
    PENDING,

    /** 还没有系统管理员，但验证码已过期，需要后台重新生成或重启。 */
    EXPIRED,

    /** 已经存在系统管理员，不再需要验证码。 */
    CLAIMED,
}

/** 已获得系统管理员权限的账号。 */
public data class CommandAdminAccount(
    val platformId: String,
    val senderId: String,
)

/**
 * 初始化验证码的对外快照。[code] 只在 [CommandBootstrapStatus.PENDING] 时有值。
 */
public data class CommandBootstrapSnapshot(
    val status: CommandBootstrapStatus,
    val code: String?,
    val expiresAtEpochSeconds: Long,
    val remainingSeconds: Long,
    val recentAttempts: Int,
    val lastAttemptAtEpochSeconds: Long,
    val admins: List<CommandAdminAccount>,
) {
    public val pending: Boolean
        get() = status == CommandBootstrapStatus.PENDING
}

/** 认领结果。除 [CLAIMED] 与 [EXPIRED] 外都不应该对用户产生可见回复。 */
public enum class CommandClaimOutcome {
    CLAIMED,
    INVALID,
    EXPIRED,
    RATE_LIMITED,
    ALREADY_INITIALIZED,
    FAILED,
}

/**
 * 命令系统初始化引导：用一次性验证码把第一个系统管理员认领进来。
 *
 * 验证码**只存在内存里**：需求是"重启即重新生成"，因此不需要跨进程持久化，
 * 也不适合放进 `config/`（那是用户希望长期保持的配置）。
 * 系统是否已初始化，由 `command.permissions` 中是否存在 ADMIN 规则推导。
 */
public class CommandBootstrapService(
    private val configProvider: () -> MainDynamicConfig,
    private val configUpdater: ((MainDynamicConfig) -> MainDynamicConfig) -> ConfigApplyResult,
    private val notificationPublisher: SystemNotificationPublisher = NoopSystemNotificationPublisher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeGenerator: () -> String = ::randomCode,
    private val ttlMillis: Long = CODE_TTL_MILLIS,
    private val attemptWindowMillis: Long = CODE_TTL_MILLIS,
    private val perSenderAttemptLimit: Int = PER_SENDER_ATTEMPT_LIMIT,
    private val globalAttemptLimit: Int = GLOBAL_ATTEMPT_LIMIT,
) {
    private val lock: Any = Any()
    private val senderAttempts: MutableMap<String, MutableList<Long>> = HashMap()
    private val globalAttempts: ArrayDeque<Long> = ArrayDeque()

    private var activeCode: String? = null
    private var expiresAtMillis: Long = 0L
    private var lastAttemptAtMillis: Long = 0L

    public fun snapshot(): CommandBootstrapSnapshot = synchronized(lock) {
        if (activeCode == null && adminAccountsLocked().isEmpty()) {
            generateLocked(clock())
        }
        snapshotLocked()
    }

    /** 后台手动重新生成；已初始化时无效果。生成新码时清空限流计数，避免管理员被旧码的失败次数挡住。 */
    public fun rotate(): CommandBootstrapSnapshot = synchronized(lock) {
        if (adminAccountsLocked().isEmpty()) {
            generateLocked(clock())
            senderAttempts.clear()
            globalAttempts.clear()
            lastAttemptAtMillis = 0L
            bootstrapLogger.info { "命令系统验证码已重新生成，尝试次数已重置" }
        }
        snapshotLocked()
    }

    /** 是否是值得拦截的验证码格式（6 位数字，允许空格、连字符与全角数字）。 */
    public fun isCandidate(rawText: String): Boolean = normalizeCode(rawText) != null

    /**
     * 尝试认领。仅在单聊会话有效。
     *
     * 调用方对 [CommandClaimOutcome.CLAIMED] 回复成功、对 [CommandClaimOutcome.EXPIRED] 提示过期，
     * 其余结果一律静默，避免向试探者暴露进度。
     */
    public suspend fun claim(context: CommandContext, rawText: String): CommandClaimOutcome {
        val normalized = normalizeCode(rawText) ?: return CommandClaimOutcome.INVALID
        val outcome = synchronized(lock) {
            claimLocked(context, normalized)
        }
        if (outcome == CommandClaimOutcome.CLAIMED) {
            notifyClaimed(context)
        }
        return outcome
    }

    private suspend fun notifyClaimed(context: CommandContext) {
        runCatching {
            notificationPublisher.publish(
                SystemNotificationPublishRequest(
                    type = "command.admin_claimed",
                    severity = SystemNotificationSeverity.INFO,
                    title = "命令系统已初始化",
                    content = "已通过验证码认领系统管理员：${context.platform} ${context.senderId}。",
                    dedupeKey = "command.admin_claimed:${context.platform}:${context.senderId}",
                    details = mapOf(
                        "platformId" to context.platform,
                        "senderId" to context.senderId,
                        "chatId" to context.chatId,
                    ),
                ),
            )
        }.onFailure {
            bootstrapLogger.warn(it) { "命令管理员认领通知发送失败：platform=${context.platform}" }
        }
    }

    private fun claimLocked(context: CommandContext, normalized: String): CommandClaimOutcome {
        if (context.chatType != TargetKind.USER) return CommandClaimOutcome.INVALID
        if (adminAccountsLocked().isNotEmpty()) return CommandClaimOutcome.ALREADY_INITIALIZED

        val now = clock()
        if (!recordAttemptLocked(context.senderId, now)) {
            bootstrapLogger.debug { "命令验证码尝试被限流：senderId=${context.senderId}" }
            return CommandClaimOutcome.RATE_LIMITED
        }

        val current = activeCode ?: run {
            // 还没有验证码时先生成再比对：正常启动流程会先生成并打印，
            // 这里只保证"未生成就收到消息"不会白白吞掉一次尝试。
            generateLocked(now)
            activeCode
        } ?: return CommandClaimOutcome.INVALID
        if (now >= expiresAtMillis) return CommandClaimOutcome.EXPIRED
        if (!codesEqual(current, normalized)) return CommandClaimOutcome.INVALID

        return runCatching {
            grantAdminLocked(context)
            CommandClaimOutcome.CLAIMED
        }.getOrElse { error ->
            bootstrapLogger.warn(error) {
                "命令管理员认领失败：写入权限规则异常，platform=${context.platform}，senderId=${context.senderId}"
            }
            CommandClaimOutcome.FAILED
        }.also { outcome ->
            if (outcome == CommandClaimOutcome.CLAIMED) {
                activeCode = null
                expiresAtMillis = 0L
                bootstrapLogger.info {
                    "命令管理员已认领：platform=${context.platform}，senderId=${context.senderId}，chatId=${context.chatId}"
                }
            }
        }
    }

    private fun grantAdminLocked(context: CommandContext) {
        val platformId = context.platform
        val rule = CommandPermissionRule(
            commandPath = "*",
            platformId = platformId,
            targetKind = null,
            targetId = "*",
            scopeId = "*",
            threadId = "*",
            botAccountId = "*",
            senderId = context.senderId,
            role = CommandRole.ADMIN,
        )
        // 只改 permissions 一个字段并原子写回，避免与后台保存配置互相覆盖。
        configUpdater { current ->
            val alreadyGranted = current.command.permissions.any { it == rule }
            if (alreadyGranted) {
                current
            } else {
                current.copy(command = current.command.copy(permissions = current.command.permissions + rule))
            }
        }
    }

    private fun generateLocked(now: Long) {
        activeCode = codeGenerator()
        expiresAtMillis = now + ttlMillis
    }

    private fun snapshotLocked(): CommandBootstrapSnapshot {
        val admins = adminAccountsLocked()
        val now = clock()
        if (admins.isNotEmpty()) {
            return CommandBootstrapSnapshot(
                status = CommandBootstrapStatus.CLAIMED,
                code = null,
                expiresAtEpochSeconds = 0L,
                remainingSeconds = 0L,
                recentAttempts = attemptsInWindowLocked(now),
                lastAttemptAtEpochSeconds = lastAttemptAtMillis / 1000,
                admins = admins,
            )
        }

        val remainingMillis = expiresAtMillis - now
        val status = if (activeCode != null && remainingMillis > 0) {
            CommandBootstrapStatus.PENDING
        } else {
            CommandBootstrapStatus.EXPIRED
        }
        return CommandBootstrapSnapshot(
            status = status,
            code = if (status == CommandBootstrapStatus.PENDING) activeCode else null,
            expiresAtEpochSeconds = expiresAtMillis / 1000,
            remainingSeconds = if (status == CommandBootstrapStatus.PENDING) remainingMillis / 1000 else 0L,
            recentAttempts = attemptsInWindowLocked(now),
            lastAttemptAtEpochSeconds = lastAttemptAtMillis / 1000,
            admins = admins,
        )
    }

    private fun adminAccountsLocked(): List<CommandAdminAccount> {
        return configProvider().command.permissions
            .asSequence()
            .filter { it.role == CommandRole.ADMIN }
            .map { CommandAdminAccount(platformId = it.platformId, senderId = it.senderId) }
            .distinct()
            .toList()
    }

    private fun recordAttemptLocked(senderId: String, now: Long): Boolean {
        pruneAttemptsLocked(now)
        val attempts = senderAttempts.getOrPut(senderId) { mutableListOf() }
        if (attempts.size >= perSenderAttemptLimit) return false
        if (globalAttempts.size >= globalAttemptLimit) return false

        attempts += now
        globalAttempts.addLast(now)
        lastAttemptAtMillis = now
        return true
    }

    private fun attemptsInWindowLocked(now: Long): Int {
        pruneAttemptsLocked(now)
        return globalAttempts.size
    }

    private fun pruneAttemptsLocked(now: Long) {
        val threshold = now - attemptWindowMillis
        while (globalAttempts.isNotEmpty() && globalAttempts.first() < threshold) {
            globalAttempts.removeFirst()
        }
        val iterator = senderAttempts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { it < threshold }
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private fun codesEqual(expected: String, actual: String): Boolean {
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            actual.toByteArray(StandardCharsets.UTF_8),
        )
    }

    public companion object {
        public const val CODE_LENGTH: Int = 6
        public const val CODE_TTL_MILLIS: Long = 10 * 60 * 1000L
        public const val PER_SENDER_ATTEMPT_LIMIT: Int = 3
        public const val GLOBAL_ATTEMPT_LIMIT: Int = 20

        private val RANDOM: SecureRandom = SecureRandom()

        public fun randomCode(): String {
            return RANDOM.nextInt(1_000_000).toString().padStart(CODE_LENGTH, '0')
        }

        /**
         * 归一化用户输入：去掉空白与连字符，全角数字转半角。
         * 只有"整段消息恰好是 6 位数字"时才认为是验证码，避免吞掉正常聊天。
         */
        public fun normalizeCode(rawText: String): String? {
            val builder = StringBuilder(rawText.length)
            rawText.forEach { ch ->
                when {
                    ch in '0'..'9' -> builder.append(ch)
                    ch in '０'..'９' -> builder.append('0' + (ch - '０'))
                    ch.isWhitespace() || ch == '-' || ch == '－' || ch == '—' -> Unit
                    else -> return null
                }
            }
            val normalized = builder.toString()
            return normalized.takeIf { it.length == CODE_LENGTH }
        }
    }
}
