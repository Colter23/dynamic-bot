package top.colter.dynamic.admin

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.bridge.SLF4JBridgeHandler
import org.slf4j.LoggerFactory

public data class AdminLogRecord(
    val seq: Long,
    val timestampEpochMillis: Long,
    val level: String,
    val loggerName: String,
    val threadName: String,
    val message: String,
    val throwable: String? = null,
)

public data class AdminLogSnapshot(
    val entries: List<AdminLogRecord>,
    val nextSince: Long,
    val capacity: Int,
    val retainedCount: Int,
)

public object AdminLogBuffer {
    public const val DEFAULT_CAPACITY: Int = 2_000
    public const val MIN_CAPACITY: Int = 1
    public const val MAX_CAPACITY: Int = 100_000
    public const val MAX_MESSAGE_CHARS: Int = 4_000
    public const val MAX_THROWABLE_CHARS: Int = 16_000

    private const val TRUNCATED_SUFFIX: String = "\n... 已截断，完整内容请查看控制台日志"

    private val sequence = AtomicLong(0)
    private val entries = ArrayDeque<AdminLogRecord>()

    @Volatile
    private var capacity: Int = DEFAULT_CAPACITY

    @Synchronized
    public fun configureCapacity(nextCapacity: Int) {
        capacity = nextCapacity.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
        trimToCapacity()
    }

    @Synchronized
    public fun append(record: AdminLogRecord) {
        entries.addLast(record.truncated())
        trimToCapacity()
    }

    @Synchronized
    public fun snapshot(
        since: Long? = null,
        level: String? = null,
        levels: Set<String>? = null,
        logger: String? = null,
        query: String? = null,
        limit: Int = 300,
    ): AdminLogSnapshot {
        val normalizedLevels = normalizeLevels(levels) ?: parseLevels(level)
        val normalizedLogger = logger?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val currentCapacity = capacity
        val safeLimit = limit.coerceIn(1, currentCapacity)
        val filtered = entries.asSequence()
            .filter { record -> since == null || record.seq > since }
            .filter { record -> normalizedLevels == null || record.level.uppercase() in normalizedLevels }
            .filter { record -> normalizedLogger == null || record.loggerName.lowercase().contains(normalizedLogger) }
            .filter { record ->
                normalizedQuery == null ||
                    record.message.lowercase().contains(normalizedQuery) ||
                    record.throwable?.lowercase()?.contains(normalizedQuery) == true
            }
            .toList()
            .takeLast(safeLimit)
        return AdminLogSnapshot(
            entries = filtered,
            nextSince = entries.lastOrNull()?.seq ?: (since ?: 0),
            capacity = currentCapacity,
            retainedCount = entries.size,
        )
    }

    public fun nextSequence(): Long = sequence.incrementAndGet()

    @Synchronized
    public fun clearForTest(capacity: Int = DEFAULT_CAPACITY) {
        entries.clear()
        sequence.set(0)
        configureCapacity(capacity)
    }

    @Synchronized
    private fun trimToCapacity() {
        while (entries.size > capacity) {
            entries.removeFirst()
        }
    }

    private fun parseLevels(level: String?): Set<String>? {
        val raw = level?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return normalizeLevels(raw.split(Regex("[,，\\s]+")).toSet())
    }

    private fun normalizeLevels(levels: Set<String>?): Set<String>? {
        val normalized = levels
            ?.mapNotNull { it.trim().uppercase().takeIf(String::isNotBlank) }
            ?.toSet()
            ?: return null
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun AdminLogRecord.truncated(): AdminLogRecord {
        return copy(
            message = message.truncateLogText(MAX_MESSAGE_CHARS),
            throwable = throwable?.truncateLogText(MAX_THROWABLE_CHARS),
        )
    }

    private fun String.truncateLogText(maxChars: Int): String {
        if (length <= maxChars) return this
        val keepChars = (maxChars - TRUNCATED_SUFFIX.length).coerceAtLeast(1)
        return take(keepChars) + TRUNCATED_SUFFIX
    }
}

public class AdminLogAppender : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) {
        AdminLogBuffer.append(
            AdminLogRecord(
                seq = AdminLogBuffer.nextSequence(),
                timestampEpochMillis = eventObject.timeStamp,
                level = eventObject.level.levelStr,
                loggerName = eventObject.loggerName,
                threadName = eventObject.threadName,
                message = eventObject.formattedMessage,
                throwable = eventObject.throwableProxy?.let(ThrowableProxyUtil::asString),
            )
        )
    }
}

public object AdminLogging {
    private const val APPENDER_NAME: String = "dynamic-admin-log-buffer"

    @Volatile
    private var installed: Boolean = false

    public fun install() {
        if (installed) return
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        synchronized(this) {
            if (installed) return
            val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
            if (rootLogger.getAppender(APPENDER_NAME) == null) {
                val noiseFilter = OneBotClientNoiseFilter().apply {
                    this.context = context
                    start()
                }
                val appender = AdminLogAppender().apply {
                    name = APPENDER_NAME
                    this.context = context
                    addFilter(noiseFilter)
                    start()
                }
                rootLogger.addAppender(appender)
            }
            installJavaUtilLoggingBridge()
            installed = true
        }
    }

    private fun installJavaUtilLoggingBridge() {
        if (SLF4JBridgeHandler.isInstalled()) return
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()
    }
}
