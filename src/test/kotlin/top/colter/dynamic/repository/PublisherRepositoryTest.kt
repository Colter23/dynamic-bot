package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher
import top.colter.dynamic.testPublisherInfo
import top.colter.dynamic.testPublisherKey

class PublisherRepositoryTest {
    @Test
    fun createAndFindAllShouldPersistCompletePublisher() {
        initTestDatabase("dynamic-bot-core-publisher-create-db")

        PublisherRepository.create(
            testPublisher(
                id = 1,
                key = testPublisherKey(platformId = "bilibili", externalId = "123456"),
                name = "test-up",
                avatar = testMedia("https://example.com/face.png", MediaKind.AVATAR),
            ),
        )

        val all = PublisherRepository.findAll()

        assertEquals(1, all.size)
        assertEquals("123456", all.first().externalId)
        assertEquals("bilibili", all.first().platformId.value)
    }

    @Test
    fun upsertInfoShouldPersistKeyAndClearNullableFields() {
        initTestDatabase("dynamic-bot-core-publisher-upsert-db")

        val created = PublisherRepository.upsertInfo(
            testPublisherInfo(
                key = testPublisherKey(platformId = "bilibili", externalId = "123456"),
                name = "test-up",
                avatar = testMedia("https://example.com/face.png", MediaKind.AVATAR),
                banner = testMedia("https://example.com/header.png", MediaKind.COVER),
                pendant = testMedia("https://example.com/pendant.png", MediaKind.AVATAR),
            ).copy(official = "official"),
        )
        val updated = PublisherRepository.upsertInfo(
            PublisherInfo(
                key = testPublisherKey(platformId = "bilibili", externalId = "123456"),
                name = "test-up-updated",
                official = null,
                state = EntityState.ACTIVE,
                avatar = testMedia("https://example.com/face2.png", MediaKind.AVATAR),
                banner = null,
                pendant = null,
            ),
        )
        val secondPlatform = PublisherRepository.upsertInfo(
            testPublisherInfo(
                key = testPublisherKey(platformId = "x", externalId = "123456"),
                name = "x-up",
                avatar = testMedia("https://example.com/x.png", MediaKind.AVATAR),
            ),
        )

        assertTrue(created.created)
        assertTrue(updated.updated)
        assertTrue(secondPlatform.created)
        assertEquals(created.value.id, updated.value.id)

        val found = PublisherRepository.findByKey(PublisherKey.of("bilibili", externalId = "123456"))
        assertNotNull(found)
        assertEquals("test-up-updated", found.name)
        assertNull(found.official)
        assertNull(found.banner)
        assertNull(found.pendant)
        assertNotNull(PublisherRepository.findByKey(PublisherKey.of("x", externalId = "123456")))
    }
}
