package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testPublisher

class SourceCursorRepositoryTest {

    @Test
    fun shouldPersistAndDeduplicatePerSourceAndEventType() {
        initTestDatabase("source-cursor-test")
        seedPublisher(1)

        assertFalse(SourceCursorRepository.hasSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d,1"))
        SourceCursorRepository.markSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d,1", 1L)
        SourceCursorRepository.markSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d2", 2L)
        SourceCursorRepository.markSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d,1", 3L)
        SourceCursorRepository.markSeen(1, "live", SourceEventType.LIVE_STARTED, "room-1", 4L)

        assertTrue(SourceCursorRepository.hasSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d,1"))
        assertTrue(SourceCursorRepository.hasSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "d2"))
        assertFalse(SourceCursorRepository.hasSeen(1, "dynamic", SourceEventType.LIVE_STARTED, "room-1"))

        val state = SourceCursorRepository.find(1, "dynamic", SourceEventType.DYNAMIC_CREATED)
        assertNotNull(state)
        assertEquals("d,1", state.lastSeenUpdateKey)
        assertEquals(listOf("d,1", "d2"), state.recentUpdateKeys)
    }

    @Test
    fun shouldCreateBaselineOnlyWhenCursorIsMissing() {
        initTestDatabase("source-cursor-baseline-test")
        seedPublisher(1)

        val baseline = SourceCursorRepository.ensureBaseline(1, "dynamic", SourceEventType.DYNAMIC_CREATED, 100L)
        assertEquals(100L, baseline.lastSeenAtEpochSeconds)
        assertTrue(SourceCursorRepository.hasSeen(1, "dynamic", SourceEventType.DYNAMIC_CREATED, "__baseline__100"))

        val existing = SourceCursorRepository.ensureBaseline(1, "dynamic", SourceEventType.DYNAMIC_CREATED, 200L)
        assertEquals(baseline, existing)
        assertEquals(
            100L,
            SourceCursorRepository.find(1, "dynamic", SourceEventType.DYNAMIC_CREATED)?.lastSeenAtEpochSeconds,
        )
    }

    @Test
    fun shouldHandleConcurrentMarkSeenForSameCursor() = runBlocking {
        initTestDatabase("source-cursor-concurrent-test")
        seedPublisher(1)
        val keys = (1..8).map { "d-$it" }

        keys.mapIndexed { index, key ->
            async(Dispatchers.Default) {
                SourceCursorRepository.markSeen(
                    publisherId = 1,
                    sourceKey = "dynamic",
                    eventType = SourceEventType.DYNAMIC_CREATED,
                    updateKey = key,
                    timestamp = index.toLong(),
                )
            }
        }.awaitAll()

        val state = assertNotNull(SourceCursorRepository.find(1, "dynamic", SourceEventType.DYNAMIC_CREATED))
        assertTrue(state.lastSeenUpdateKey in keys)
        assertTrue(state.recentUpdateKeys.isNotEmpty())
    }

    private fun seedPublisher(id: Int) {
        PublisherRepository.create(testPublisher(id = id))
    }
}
