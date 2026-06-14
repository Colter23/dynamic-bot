package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.dynamic.core.data.Message
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testTargetAddress

class SourceUpdateSnapshotRepositoryTest {
    @Test
    fun shouldUpsertAndFindSourceUpdateSnapshot() {
        initTestDatabase("dynamic-bot-source-update-snapshot")
        val update = testDynamicUpdate(externalId = "dynamic-1")

        SourceUpdateSnapshotRepository.upsert("bilibili", update)
        val stored = assertNotNull(SourceUpdateSnapshotRepository.findByUpdateKey(update.key))

        assertEquals("bilibili", stored.sourcePlugin)
        assertEquals(update.key, stored.updateKey)
        assertEquals(update.link, stored.update.link)

        val changed = update.copy(link = "https://example.com/changed")
        SourceUpdateSnapshotRepository.upsert("bilibili", changed)
        assertEquals("https://example.com/changed", SourceUpdateSnapshotRepository.findUpdate(update.key)?.link)
    }

    @Test
    fun shouldCleanupOnlySnapshotsWithoutDeliveryReference() {
        initTestDatabase("dynamic-bot-source-update-snapshot-cleanup")
        val referenced = testDynamicUpdate(externalId = "a-referenced")
        val orphaned = testDynamicUpdate(externalId = "z-orphaned")
        SourceUpdateSnapshotRepository.upsert("bilibili", referenced)
        SourceUpdateSnapshotRepository.upsert("bilibili", orphaned)

        val target = testTargetAddress(platformId = "qq", kind = TargetKind.GROUP, externalId = "10001")
        MessageDeliveryRepository.enqueue(
            Message(
                id = "message-referenced",
                time = 1L,
                sourceUpdateKey = referenced.key,
                targets = listOf(target),
                batches = listOf(MessageBatch(listOf(MessageContent.Text("hello")))),
            ),
        )

        val deleted = SourceUpdateSnapshotRepository.cleanupOrphaned(
            cutoffEpochSeconds = System.currentTimeMillis() / 1000 + 60,
            batchSize = 1,
            maxBatches = 2,
        )

        assertEquals(1, deleted)
        assertNotNull(SourceUpdateSnapshotRepository.findByUpdateKey(referenced.key))
        assertNull(SourceUpdateSnapshotRepository.findByUpdateKey(orphaned.key))
    }
}
