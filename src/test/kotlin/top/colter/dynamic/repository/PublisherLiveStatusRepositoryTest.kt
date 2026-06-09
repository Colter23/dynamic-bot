package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PublisherLiveStatus
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher

class PublisherLiveStatusRepositoryTest {
    @Test
    fun shouldPersistAndUpdateSingleLiveStatusByPublisher() {
        initTestDatabase("dynamic-bot-core-live-status-db")
        PublisherRepository.create(testPublisher(id = 1))

        PublisherLiveStatusRepository.upsert(
            PublisherLiveStatus(
                publisherId = 1,
                roomId = "456",
                status = LiveStatus.CLOSE,
                title = "room",
                cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                area = "games",
                startedAtEpochSeconds = null,
                lastObservedAtEpochSeconds = 100,
            ),
        )
        PublisherLiveStatusRepository.upsert(
            PublisherLiveStatus(
                publisherId = 1,
                roomId = "789",
                status = LiveStatus.OPEN,
                title = "now live",
                cover = testMedia("https://example.com/live.png", MediaKind.COVER),
                area = "chat",
                startedAtEpochSeconds = 120,
                lastObservedAtEpochSeconds = 121,
            ),
        )

        val status = PublisherLiveStatusRepository.findByPublisherId(1)!!
        assertEquals("789", status.roomId)
        assertEquals(LiveStatus.OPEN, status.status)
        assertEquals("now live", status.title)
        assertEquals("https://example.com/live.png", status.cover?.uri)
        assertEquals(120, status.startedAtEpochSeconds)
        assertEquals(1, PublisherLiveStatusRepository.findAllByPublisherId(1).size)
        assertEquals(1, PublisherLiveStatusRepository.findAll().size)
    }
}
