package top.colter.dynamic.command

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandStatus

/** 按 key 的滑动窗口限流器，用于给会访问来源平台的命令节流。 */
internal class CommandRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val windows: MutableMap<String, ArrayDeque<Long>> = ConcurrentHashMap()

    fun tryAcquire(key: String, limit: Int, windowMillis: Long): Boolean {
        if (limit <= 0 || windowMillis <= 0) return true
        val now = clock()
        sweepStale(now)
        val threshold = now - windowMillis
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && window.first() <= threshold) {
                window.removeFirst()
            }
            if (window.size >= limit) return false
            window.addLast(now)
            return true
        }
    }

    /** 清理长期不活跃的桶，避免按"群×用户"增长后一直占着内存。 */
    private fun sweepStale(now: Long) {
        if (windows.size <= SWEEP_THRESHOLD) return
        val staleBefore = now - STALE_BUCKET_MILLIS
        windows.entries.removeIf { (_, window) ->
            synchronized(window) {
                window.isEmpty() || window.last() < staleBefore
            }
        }
    }

    internal companion object {
        internal const val SWEEP_THRESHOLD: Int = 2_048
        internal const val STALE_BUCKET_MILLIS: Long = 10 * 60 * 1000L
    }
}

/**
 * 给命令加"每发送者 / 每会话"双重限流。
 * 超限时同一会话 5 分钟内只提示一次，其余情况静默，避免被用来刷屏。
 */
internal class RateLimitedCommandHandler(
    private val delegate: CommandHandler,
    private val limiter: CommandRateLimiter,
    private val perSenderLimit: Int,
    private val perTargetLimit: Int,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandHandler {
    override val spec: CommandSpec
        get() = delegate.spec

    private val noticedAt: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val address = invocation.context.target
        val path = spec.path.joinToString(" ")
        val targetKey = "$path|target|${address.stableValue()}"
        val senderKey = "$path|sender|${address.stableValue()}|${invocation.context.senderId}"

        // 先看会话总配额，再看个人配额；超限即拒绝。
        val targetAllowed = limiter.tryAcquire(targetKey, perTargetLimit, windowMillis)
        if (targetAllowed && limiter.tryAcquire(senderKey, perSenderLimit, windowMillis)) {
            return delegate.handle(invocation)
        }

        val noticeKey = "${address.stableValue()}|$path"
        val now = clock()
        val previous = noticedAt[noticeKey]
        if (previous != null && now - previous < NOTICE_INTERVAL_MILLIS) {
            return CommandExecutionResult(status = CommandStatus.SUCCESS, reply = emptyList())
        }
        noticedAt[noticeKey] = now
        return CommandExecutionResult.failed("操作过于频繁，请稍后再试。")
    }

    internal companion object {
        internal const val DEFAULT_WINDOW_MILLIS: Long = 60_000L
        internal const val NOTICE_INTERVAL_MILLIS: Long = 5 * 60 * 1000L
    }
}
