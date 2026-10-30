package top.colter.dynamic.command

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandPermissionRule
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.TargetKind

/** 认领结果的统一文案。裸验证码与 `/db claim` 共用。 */
internal fun claimOutcomeResult(outcome: CommandClaimOutcome, prefix: String): CommandExecutionResult {
    return when (outcome) {
        CommandClaimOutcome.CLAIMED ->
            CommandExecutionResult.success("已认领系统管理员，现在可以使用管理命令。")
        CommandClaimOutcome.EXPIRED ->
            CommandExecutionResult.failed("验证码已失效，请在后台「系统」页重新生成，或重启主程序。")
        CommandClaimOutcome.ALREADY_INITIALIZED ->
            CommandExecutionResult.failed("命令系统已初始化；如需授权其他用户，请使用 $prefix grant 或后台权限规则。")
        CommandClaimOutcome.INVALID ->
            CommandExecutionResult.failed("验证码不正确或已失效。")
        CommandClaimOutcome.RATE_LIMITED ->
            CommandExecutionResult.failed("尝试过于频繁，请稍后再试。")
        CommandClaimOutcome.FAILED ->
            CommandExecutionResult.failed("认领失败，请查看后台日志。")
    }
}

/** 显示当前会话的标识，用于配置权限规则、转发目标和排查问题。 */
internal class WhoamiCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("whoami"),
        description = "显示当前会话的标识",
        usage = "whoami",
        requiredRole = CommandRole.USER,
        category = "系统",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val context = invocation.context
        val target = context.target
        return CommandExecutionResult.success(
            listOf(
                "平台：${context.platform}",
                "会话：${target.kind} ${target.externalId}",
                "Scope：${target.scopeId ?: "-"}",
                "Thread：${target.threadId ?: "-"}",
                "发送者：${context.senderId}",
                "Bot账号：${context.botAccountId ?: "-"}",
            ).joinToString("\n"),
        )
    }
}

/** `/db claim <验证码>`：与裸验证码等价，方便习惯加前缀的用户。 */
internal class ClaimCommandHandler(
    private val bootstrapService: CommandBootstrapService,
    private val commandPrefixProvider: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("claim"),
        description = "使用验证码认领系统管理员",
        usage = "claim <验证码>",
        requiredRole = CommandRole.USER,
        category = "管理",
    )

    /** 已初始化后的重复提示去重，避免这条免权限命令被用来刷屏。 */
    private val initializedNotifiedAt = ConcurrentHashMap<String, Long>()

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val input = invocation.args.joinToString(" ").trim()
        if (input.isBlank()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val outcome = bootstrapService.claim(invocation.context, input)
        if (outcome == CommandClaimOutcome.ALREADY_INITIALIZED && !shouldNotifyInitialized(invocation)) {
            // 重复提示直接静默，避免这条免权限命令被用来刷屏。
            return CommandExecutionResult(status = CommandStatus.SUCCESS, reply = emptyList())
        }
        return claimOutcomeResult(outcome, commandPrefix)
    }

    private fun shouldNotifyInitialized(invocation: CommandInvocation): Boolean {
        val key = "${invocation.context.target.stableValue()}|${invocation.context.senderId}"
        val now = clock()
        val previous = initializedNotifiedAt[key]
        if (previous != null && now - previous < INITIALIZED_NOTIFY_INTERVAL_MILLIS) return false
        initializedNotifiedAt[key] = now
        return true
    }

    private companion object {
        private const val INITIALIZED_NOTIFY_INTERVAL_MILLIS: Long = 10 * 60 * 1000L
    }
}

/** 授予命令权限，直接写入主配置的 `command.permissions`。 */
internal class GrantCommandHandler(
    private val configUpdater: ((MainDynamicConfig) -> MainDynamicConfig) -> ConfigApplyResult,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("grant"),
        description = "授予指定用户命令权限",
        usage = "grant <platform> <senderId> <角色> [会话类型] [目标ID]",
        requiredRole = CommandRole.ADMIN,
        category = "管理",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 3) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].trim().lowercase()
        val senderId = invocation.args[1].trim()
        if (platform.isBlank() || senderId.isBlank()) {
            return CommandExecutionResult.failed("平台与发送者不能为空")
        }
        if (senderId == "*") {
            return CommandExecutionResult.failed("不能用 * 授予管理员权限，请填写具体的发送者 ID")
        }
        val role = CommandRole.entries.firstOrNull { it.name.equals(invocation.args[2], ignoreCase = true) }
            ?: return CommandExecutionResult.failed("角色无效：${invocation.args[2]}")
        if (role == CommandRole.NONE) {
            return CommandExecutionResult.failed("角色不能为 NONE，如需取消权限请使用 $commandPrefix revoke")
        }
        val targetKind = invocation.args.getOrNull(3)?.takeIf { it.isNotBlank() }?.let { raw ->
            TargetKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return CommandExecutionResult.failed("会话类型无效：$raw")
        }
        val targetId = invocation.args.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() } ?: "*"

        val rule = CommandPermissionRule(
            commandPath = "*",
            platformId = platform,
            targetKind = targetKind,
            targetId = targetId,
            senderId = senderId,
            role = role,
        )
        // 只改 permissions 字段并原子写回，避免与后台保存配置互相覆盖。
        val result = runCatching {
            configUpdater { current ->
                if (current.command.permissions.any { it == rule }) {
                    current
                } else {
                    current.copy(command = current.command.copy(permissions = current.command.permissions + rule))
                }
            }
        }.getOrElse { error ->
            return CommandExecutionResult.failed("保存权限规则失败：${error.message ?: "未知错误"}")
        }
        return if (result.changed) {
            CommandExecutionResult.success("已授予权限：$platform $senderId → ${role.name}")
        } else {
            CommandExecutionResult.success("规则已存在：$platform $senderId ${role.name}")
        }
    }
}

/** 撤销命令权限。 */
internal class RevokeCommandHandler(
    private val configUpdater: ((MainDynamicConfig) -> MainDynamicConfig) -> ConfigApplyResult,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("revoke"),
        description = "撤销指定用户的命令权限",
        usage = "revoke <platform> <senderId> [命令路径]",
        requiredRole = CommandRole.ADMIN,
        category = "管理",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].trim().lowercase()
        val senderId = invocation.args[1].trim()
        val commandPath = invocation.args.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }

        var removed = 0
        val result = runCatching {
            configUpdater { current ->
                val remaining = current.command.permissions.filterNot { rule ->
                    rule.platformId.equals(platform, ignoreCase = true) &&
                        rule.senderId == senderId &&
                        (commandPath == null || rule.commandPath.equals(commandPath, ignoreCase = true))
                }
                removed = current.command.permissions.size - remaining.size
                if (removed == 0) {
                    current
                } else {
                    current.copy(command = current.command.copy(permissions = remaining))
                }
            }
        }.getOrElse { error ->
            return CommandExecutionResult.failed("保存权限规则失败：${error.message ?: "未知错误"}")
        }

        return if (result.changed) {
            CommandExecutionResult.success("已撤销 $removed 条权限规则：$platform $senderId")
        } else {
            CommandExecutionResult.success("未找到匹配的权限规则：$platform $senderId")
        }
    }
}
