package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.SubscriberState
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

    @Test
    fun upsertShouldPersistRefreshAndPreserveAvatar() {
        initTestDatabase("dynamic-bot-core-subscriber-avatar-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")
        val firstAvatar = MediaRef("https://example.com/group-a.png", MediaKind.AVATAR)
        val refreshedAvatar = MediaRef("https://example.com/group-b.png", MediaKind.AVATAR)

        val created = SubscriberRepository.upsert(address = address, name = "group-a", avatar = firstAvatar)
        val refreshed = SubscriberRepository.upsert(address = address, name = "group-b", avatar = refreshedAvatar)
        val preserved = SubscriberRepository.upsert(address = address, name = "group-c")

        assertEquals(firstAvatar, created.value.avatar)
        assertEquals(refreshedAvatar, refreshed.value.avatar)
        assertEquals(refreshedAvatar, preserved.value.avatar)
        assertEquals(refreshedAvatar, SubscriberRepository.findByAddress(address)?.avatar)
        assertEquals("group-c", SubscriberRepository.findByAddress(address)?.name)
    }

    @Test
    fun upsertShouldPreserveExistingStateUnlessExplicitlyChanged() {
        initTestDatabase("dynamic-bot-core-subscriber-upsert-preserve-state-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")

        val paused = SubscriberRepository.upsert(
            address = address,
            name = "测试群",
            state = SubscriberState.DELIVERY_PAUSED,
        )
        val refreshed = SubscriberRepository.upsert(address = address, name = "测试群新名称")
        val reenabled = SubscriberRepository.upsert(
            address = address,
            name = "测试群新名称",
            state = SubscriberState.ACTIVE,
        )

        assertEquals(SubscriberState.DELIVERY_PAUSED, paused.value.state)
        assertEquals(SubscriberState.DELIVERY_PAUSED, refreshed.value.state)
        assertEquals(SubscriberState.ACTIVE, reenabled.value.state)
        assertEquals(SubscriberState.ACTIVE, SubscriberRepository.findByAddress(address)?.state)
    }

    @Test
    fun writesShouldKeepSubscriberStateCacheInSync() {
        initTestDatabase("dynamic-bot-core-subscriber-state-cache-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")

        SubscriberRepository.upsert(
            address = address,
            name = "测试群",
            state = SubscriberState.DELIVERY_PAUSED,
        )
        assertEquals(SubscriberState.DELIVERY_PAUSED, SubscriberStateCache.stateOf(address))

        SubscriberRepository.upsert(
            address = address,
            name = "测试群",
            state = SubscriberState.ACTIVE,
        )
        assertEquals(SubscriberState.ACTIVE, SubscriberStateCache.stateOf(address))

        SubscriberRepository.upsert(
            address = address,
            name = "测试群",
            state = SubscriberState.BLOCKED,
        )
        assertEquals(SubscriberState.BLOCKED, SubscriberStateCache.stateOf(address))

        SubscriberRepository.deleteById(SubscriberRepository.findByAddress(address)!!.id)
        assertEquals(SubscriberState.ACTIVE, SubscriberStateCache.stateOf(address))
    }

    @Test
    fun ensureShouldNotDowngradeExistingDisplayNameToTargetId() {
        initTestDatabase("dynamic-bot-core-subscriber-ensure-preserve-name-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")

        val created = SubscriberRepository.upsert(address = address, name = "测试群")
        val ensured = SubscriberRepository.ensure(address = address, name = "10001")

        assertEquals(created.value.id, ensured.id)
        assertEquals("测试群", ensured.name)
        assertEquals("测试群", SubscriberRepository.findByAddress(address)?.name)
    }

    @Test
    fun ensureShouldUpgradePlaceholderNameToDisplayName() {
        initTestDatabase("dynamic-bot-core-subscriber-ensure-upgrade-name-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")

        SubscriberRepository.ensure(address = address, name = "10001")
        val upgraded = SubscriberRepository.ensure(address = address, name = "测试群")

        assertEquals("测试群", upgraded.name)
        assertEquals("测试群", SubscriberRepository.findByAddress(address)?.name)
    }

    @Test
    fun ensureShouldPreserveExistingDeliveryPausedState() {
        initTestDatabase("dynamic-bot-core-subscriber-ensure-preserve-state-db")
        val address = testTargetAddress(platformId = "onebot", kind = TargetKind.GROUP, externalId = "10001")

        val created = SubscriberRepository.upsert(
            address = address,
            name = "测试群",
            state = SubscriberState.DELIVERY_PAUSED,
        )
        val ensured = SubscriberRepository.ensure(address = address, name = "测试群")

        assertEquals(created.value.id, ensured.id)
        assertEquals(SubscriberState.DELIVERY_PAUSED, ensured.state)
        assertEquals(SubscriberState.DELIVERY_PAUSED, SubscriberRepository.findByAddress(address)?.state)
    }
}
