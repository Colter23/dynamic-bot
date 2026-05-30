package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.testTargetAddress

class SubscriberRepositoryTest {
    @Test
    fun upsertShouldUseStableTargetAddressKey() {
        initTestDatabase("dynamic-bot-core-subscriber-db")

        val group = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")
        val created = SubscriberRepository.upsert(address = group, name = "group-a")
        val updated = SubscriberRepository.upsert(address = group, name = "group-a-renamed")
        val privateTargetWithSameId = SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "onebot", kind = TargetKind.USER, externalId = "10001"),
            name = "private-a",
        )
        val threadTargetWithSameNullableFields = SubscriberRepository.upsert(
            address = testTargetAddress(
                platformId = "discord",
                kind = TargetKind.THREAD,
                externalId = "channel-1",
                threadId = "thread-1",
            ),
            name = "thread-a",
        )
        val threadTargetUpdated = SubscriberRepository.upsert(
            address = testTargetAddress(
                platformId = "discord",
                kind = TargetKind.THREAD,
                externalId = "channel-1",
                threadId = "thread-1",
            ),
            name = "thread-a-renamed",
        )

        assertTrue(created.created)
        assertTrue(updated.updated)
        assertTrue(privateTargetWithSameId.created)
        assertTrue(threadTargetWithSameNullableFields.created)
        assertTrue(threadTargetUpdated.updated)
        assertEquals(created.value.id, updated.value.id)
        assertEquals(threadTargetWithSameNullableFields.value.id, threadTargetUpdated.value.id)
        assertEquals("group-a-renamed", SubscriberRepository.findById(created.value.id)?.name)
        assertNotNull(
            SubscriberRepository.findByAddress(
                testTargetAddress(platformId = "onebot", kind = TargetKind.USER, externalId = "10001"),
            ),
        )
    }

    @Test
    fun findAllShouldReturnPersistedSubscribers() {
        initTestDatabase("dynamic-bot-core-subscriber-find-all-db")

        SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001"),
            name = "group-a",
        )
        SubscriberRepository.upsert(
            address = testTargetAddress(platformId = "discord", kind = TargetKind.CHANNEL, externalId = "20001"),
            name = "channel-a",
        )

        val subscribers = SubscriberRepository.findAll()

        assertEquals(listOf("discord", "onebot"), subscribers.map { it.platformId.value }.sorted())
    }
}
