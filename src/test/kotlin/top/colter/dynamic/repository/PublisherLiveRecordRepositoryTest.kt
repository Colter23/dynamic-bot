package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher

class PublisherLiveRecordRepositoryTest {
    @Test
    fun shouldCreateOpenRecordAndIgnoreDuplicateStart() {
        val publisher = preparePublisher("publisher-live-record-start")

        val first = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 100, startedAt = 90),
            )
        )
        val duplicate = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 100, startedAt = 90),
            )
        )

        assertEquals(first.id, duplicate.id)
        assertEquals("100", duplicate.roomId)
        assertEquals(90, duplicate.startedAtEpochSeconds)
        assertNull(duplicate.endedAtEpochSeconds)
        assertEquals(1, PublisherLiveRecordRepository.findAll().size)
    }

    @Test
    fun shouldCloseOpenRecordWhenLiveEnded() {
        val publisher = preparePublisher("publisher-live-record-end")

        PublisherLiveRecordRepository.recordLiveEvent(
            publisher.id,
            liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 100, startedAt = 90),
        )
        val ended = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_ENDED, eventTime = 160, startedAt = 90, endedAt = 160),
            )
        )

        assertEquals(90, ended.startedAtEpochSeconds)
        assertEquals(160, ended.endedAtEpochSeconds)
        assertEquals(70, ended.durationSeconds)
        assertEquals(1, PublisherLiveRecordRepository.findAll().size)
    }

    @Test
    fun shouldCloseStaleOpenRecordWhenNewLiveStarts() {
        val publisher = preparePublisher("publisher-live-record-stale")

        val stale = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 100, startedAt = 90, title = "上一场"),
            )
        )
        val current = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 300, startedAt = 300, title = "新一场"),
            )
        )

        val records = PublisherLiveRecordRepository.findRecentByPublisherId(publisher.id)
        val closedStale = assertNotNull(records.firstOrNull { it.id == stale.id })
        assertEquals(300, closedStale.endedAtEpochSeconds)
        assertEquals(current.id, records.first().id)
        assertNull(current.endedAtEpochSeconds)
        assertEquals(2, records.size)
    }

    @Test
    fun shouldCloseExactOldRecordWhenDelayedEndArrivesAfterNewStart() {
        val publisher = preparePublisher("publisher-live-record-delayed-end")

        val first = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 100, startedAt = 90),
            )
        )
        val second = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_STARTED, eventTime = 300, startedAt = 300),
            )
        )
        val delayedEnd = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_ENDED, eventTime = 160, startedAt = 90, endedAt = 160),
            )
        )

        val records = PublisherLiveRecordRepository.findRecentByPublisherId(publisher.id)
        val firstRecord = assertNotNull(records.firstOrNull { it.id == first.id })
        val secondRecord = assertNotNull(records.firstOrNull { it.id == second.id })
        assertEquals(first.id, delayedEnd.id)
        assertEquals(160, firstRecord.endedAtEpochSeconds)
        assertNull(secondRecord.endedAtEpochSeconds)
        assertEquals(2, records.size)
    }

    @Test
    fun shouldCreateClosedRecordWhenEndArrivesWithoutOpenRecord() {
        val publisher = preparePublisher("publisher-live-record-end-only")

        val record = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(publisher, SourceEventType.LIVE_ENDED, eventTime = 180, startedAt = 100, endedAt = 180),
            )
        )

        assertEquals(100, record.startedAtEpochSeconds)
        assertEquals(180, record.endedAtEpochSeconds)
        assertEquals(80, record.durationSeconds)
        assertEquals(1, PublisherLiveRecordRepository.findAll().size)
    }

    @Test
    fun shouldReuseOpenRecordWhenRoomIdBecomesAvailableOnEnd() {
        val publisher = preparePublisher("publisher-live-record-room-late")

        val open = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(
                    publisher,
                    SourceEventType.LIVE_STARTED,
                    eventTime = 100,
                    startedAt = 90,
                    roomId = "",
                ),
            )
        )
        val closed = assertNotNull(
            PublisherLiveRecordRepository.recordLiveEvent(
                publisher.id,
                liveUpdate(
                    publisher,
                    SourceEventType.LIVE_ENDED,
                    eventTime = 160,
                    startedAt = 90,
                    endedAt = 160,
                    roomId = "100",
                ),
            )
        )

        assertEquals(open.id, closed.id)
        assertEquals("100", closed.roomId)
        assertEquals(160, closed.endedAtEpochSeconds)
        assertEquals(1, PublisherLiveRecordRepository.findAll().size)
    }

    private fun preparePublisher(databaseName: String): Publisher {
        initTestDatabase(databaseName)
        val publisher = testPublisher(id = 1)
        PublisherRepository.create(publisher)
        return publisher
    }

    private fun liveUpdate(
        publisher: Publisher,
        eventType: SourceEventType,
        eventTime: Long,
        startedAt: Long? = null,
        endedAt: Long? = null,
        roomId: String = "100",
        title: String = "直播标题",
    ): SourceUpdate = SourceUpdate(
        key = UpdateKey.of(
            publisherKey = publisher.key,
            eventType = eventType,
            externalId = "$roomId:$eventTime",
        ),
        publisher = publisher.toInfo(),
        occurredAtEpochSeconds = eventTime,
        observedAtEpochSeconds = eventTime + 1,
        link = "https://live.bilibili.com/$roomId",
        payload = LivePayload(
            roomId = roomId,
            title = title,
            area = "分区",
            cover = testMedia("https://example.com/live.png"),
            status = if (eventType == SourceEventType.LIVE_STARTED) LiveStatus.OPEN else LiveStatus.CLOSE,
            previousStatus = if (eventType == SourceEventType.LIVE_STARTED) LiveStatus.CLOSE else LiveStatus.OPEN,
            startedAtEpochSeconds = startedAt,
            endedAtEpochSeconds = endedAt,
        ),
    )
}
