package top.colter.dynamic.admin

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
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
)

public object AdminLogBuffer {
    public const val DEFAULT_CAPACITY: Int = 1_000

    private val sequence = AtomicLong(0)
    private val entries = ArrayDeque<AdminLogRecord>()

    @Synchronized
    public fun append(record: AdminLogRecord) {
        entries.addLast(record)
        while (entries.size > DEFAULT_CAPACITY) {
            entries.removeFirst()
        }
    }

    @Synchronized
    public fun snapshot(
        since: Long? = null,
        level: String? = null,
        logger: String? = null,
        query: String? = null,
        limit: Int = 300,
    ): AdminLogSnapshot {
        val normalizedLevel = level?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val normalizedLogger = logger?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val safeLimit = limit.coerceIn(1, DEFAULT_CAPACITY)
        val filtered = entries.asSequence()
            .filter { record -> since == null || record.seq > since }
            .filter { record -> normalizedLevel == null || record.level == normalizedLevel }
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
            capacity = DEFAULT_CAPACITY,
        )
    }

    public fun nextSequence(): Long = sequence.incrementAndGet()

    @Synchronized
    public fun clearForTest() {
        entries.clear()
        sequence.set(0)
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
                val appender = AdminLogAppender().apply {
                    name = APPENDER_NAME
                    this.context = context
                    start()
                }
                rootLogger.addAppender(appender)
            }
            installed = true
        }
    }
}
