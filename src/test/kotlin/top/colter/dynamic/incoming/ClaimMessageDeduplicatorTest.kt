package top.colter.dynamic.incoming

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest

class ClaimMessageDeduplicatorTest {
    @Test
    fun `重复去重键和缺省键的消息只处理一次`() {
        for (request in listOf(
            request().copy(dedupeKey = "dedupe"),
            request().copy(sourceEventId = "event"),
            request(),
        )) {
            val deduplicator = ClaimMessageDeduplicator()
            assertTrue(deduplicator.firstObservation("onebot", request, "first-trace"))
            assertFalse(deduplicator.firstObservation("onebot", request, "different-trace"))
        }
        val deduplicator = ClaimMessageDeduplicator()
        val noMessageId = request().let { it.copy(message = it.message.copy(messageId = "")) }
        assertTrue(deduplicator.firstObservation("onebot", noMessageId, "trace"))
        assertFalse(deduplicator.firstObservation("onebot", noMessageId, "trace"))
        assertTrue(deduplicator.firstObservation("onebot", noMessageId, "new-trace"))
    }

    @Test
    fun `去重按来源账号会话和发送者隔离`() {
        val deduplicator = ClaimMessageDeduplicator()
        val request = request()
        assertTrue(deduplicator.firstObservation("onebot", request, "trace"))
        assertTrue(deduplicator.firstObservation("other-plugin", request, "trace"))
        for (message in listOf(
            request.message.copy(botAccountId = "bot-b"),
            request.message.copy(target = request.message.target.copy(externalId = "other-chat")),
            request.message.copy(senderId = "other-sender"),
        )) {
            assertTrue(deduplicator.firstObservation("onebot", request.copy(message = message), "trace"))
        }
    }

    @Test
    fun `过期和容量淘汰无需额外清理任务`() {
        var now = 0L
        val deduplicator = ClaimMessageDeduplicator(clock = { now }, ttlMillis = 1_000L, maxEntries = 2)
        val first = request().copy(dedupeKey = "first")
        val second = request().copy(dedupeKey = "second")
        val third = request().copy(dedupeKey = "third")
        assertTrue(deduplicator.firstObservation("onebot", first, "trace"))
        assertTrue(deduplicator.firstObservation("onebot", second, "trace"))
        assertTrue(deduplicator.firstObservation("onebot", third, "trace"))
        assertFalse(deduplicator.wasSeen("onebot", first, "trace"))
        assertTrue(deduplicator.wasSeen("onebot", second, "trace"))
        now = 1_000L
        assertFalse(deduplicator.wasSeen("onebot", second, "trace"))
        assertTrue(deduplicator.firstObservation("onebot", second, "trace"))
    }

    @Test
    fun `同一事件并发上报只能认领一次处理权`() {
        val deduplicator = ClaimMessageDeduplicator()
        val request = request()
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    deduplicator.firstObservation("onebot", request, "trace")
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun request() = IncomingMessagePublishRequest(message = IncomingMessage(
        platformId = PlatformId.of("onebot"),
        target = TargetAddress.of("onebot", TargetKind.USER, "100"),
        senderId = "sender", botAccountId = "bot-a", messageId = "message", text = "123456",
    ))
}
