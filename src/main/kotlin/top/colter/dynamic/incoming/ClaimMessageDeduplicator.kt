package top.colter.dynamic.incoming

import top.colter.dynamic.command.CommandBootstrapService
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest

/** 只保留认领消息的来源标识，不保存验证码，也不为去重访问数据库。 */
internal class ClaimMessageDeduplicator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = CommandBootstrapService.CODE_TTL_MILLIS,
    private val maxEntries: Int = 1_024,
) {
    private data class Key(
        val sourcePlugin: String,
        val target: String,
        val botAccountId: String?,
        val senderId: String,
        val idKind: String,
        val eventId: String,
    )

    private val received = LinkedHashMap<Key, Long>()

    init {
        require(ttlMillis > 0)
        require(maxEntries > 0)
    }

    @Synchronized
    fun wasSeen(sourcePlugin: String, request: IncomingMessagePublishRequest, traceId: String): Boolean {
        pruneExpired()
        return key(sourcePlugin, request, traceId) in received
    }

    @Synchronized
    fun firstObservation(sourcePlugin: String, request: IncomingMessagePublishRequest, traceId: String): Boolean {
        pruneExpired()
        val key = key(sourcePlugin, request, traceId)
        if (key in received) return false
        if (received.size >= maxEntries) {
            val oldest = received.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        received[key] = clock()
        return true
    }

    private fun key(sourcePlugin: String, request: IncomingMessagePublishRequest, traceId: String): Key {
        val message = request.message
        val (kind, id) = sequenceOf(
            "dedupe" to request.dedupeKey,
            "event" to request.sourceEventId,
            "message" to message.messageId,
            "trace" to traceId,
        ).mapNotNull { (kind, value) -> value?.trim()?.takeIf(String::isNotBlank)?.let { kind to it } }
            .first()
        return Key(
            sourcePlugin = sourcePlugin,
            target = message.target.stableValue(),
            botAccountId = message.botAccountId?.trim()?.takeIf(String::isNotBlank)
                ?: message.target.accountId?.trim()?.takeIf(String::isNotBlank),
            senderId = message.senderId,
            idKind = kind,
            eventId = id,
        )
    }

    private fun pruneExpired() {
        val threshold = clock() - ttlMillis
        val iterator = received.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value > threshold) break
            iterator.remove()
        }
    }
}
