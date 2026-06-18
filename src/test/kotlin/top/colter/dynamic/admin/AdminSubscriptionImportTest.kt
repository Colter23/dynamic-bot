package top.colter.dynamic.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkPlugin
import top.colter.dynamic.core.plugin.MessageTargetCandidate
import top.colter.dynamic.core.plugin.PublisherBatchLookupPlugin
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.plugin.PluginHandle
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository

class AdminSubscriptionImportTest {

    @Test
    fun `import should skip profile lookup and auto follow when options disabled`() = runBlocking {
        initTestDatabase("admin-import-options-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val messageSink = RecordingMessageSinkPlugin()
        val service = testAdminService(
            publisherLookup = publisherLookup,
            messageSink = messageSink,
        )

        val result = service.importSubscriptions(
            importDocument(
                importOptions = SubscriptionImportOptions(
                    fetchProfiles = false,
                    autoFollowPublishers = false,
                ),
            ),
        )

        assertEquals(1, result.created)
        assertEquals(emptyList(), publisherLookup.batchRequests)
        assertEquals(emptyList<String>(), publisherLookup.singleRequests)
        assertEquals(emptyList<TargetKind?>(), messageSink.listRequests)
        assertEquals("1001", PublisherRepository.findAll().single().name)
        assertEquals("2001", SubscriberRepository.findAll().single().name)
    }

    @Test
    fun `import should prefetch publisher and target profiles in batches`() = runBlocking {
        initTestDatabase("admin-import-prefetch-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val messageSink = RecordingMessageSinkPlugin()
        val service = testAdminService(
            publisherLookup = publisherLookup,
            messageSink = messageSink,
        )

        val result = service.importSubscriptions(
            importDocument(
                subscriptions = listOf(
                    importItem(publisherId = "1001", targetId = "2001"),
                    importItem(publisherId = "1002", targetId = "2002"),
                    importItem(publisherId = "1001", targetId = "2002"),
                ),
            ),
        )

        assertEquals(3, result.created)
        assertEquals(listOf(listOf("1001", "1002")), publisherLookup.batchRequests)
        assertEquals(emptyList<String>(), publisherLookup.singleRequests)
        assertEquals(listOf<TargetKind?>(TargetKind.GROUP), messageSink.listRequests)
        assertEquals(setOf("UP-1001", "UP-1002"), PublisherRepository.findAll().map { it.name }.toSet())
        assertEquals(setOf("QQ群-2001", "QQ群-2002"), SubscriberRepository.findAll().map { it.name }.toSet())
    }

    @Test
    fun `import should continue when target prefetch fails`() = runBlocking {
        initTestDatabase("admin-import-prefetch-target-failure-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val messageSink = RecordingMessageSinkPlugin(failList = true)
        val service = testAdminService(
            publisherLookup = publisherLookup,
            messageSink = messageSink,
        )

        val result = service.importSubscriptions(importDocument())

        assertEquals(1, result.created)
        assertEquals(0, result.failed)
        assertEquals(listOf<TargetKind?>(TargetKind.GROUP), messageSink.listRequests)
        assertTrue(result.warnings.any { it.contains("消息目标资料预取失败") })
        assertEquals("UP-1001", PublisherRepository.findAll().single().name)
        assertEquals("2001", SubscriberRepository.findAll().single().name)
    }

    @Test
    fun `import should continue when message sink provider fails`() = runBlocking {
        initTestDatabase("admin-import-sink-provider-failure-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val service = AdminService(
            pluginProvider = { emptyList() },
            messageSinkProvider = { throw IllegalStateException("插件列表暂时不可用") },
            publisherLookupResolver = { platformId ->
                publisherLookup.takeIf { platformId.equals("bilibili", ignoreCase = true) }
            },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
        )

        val result = service.importSubscriptions(importDocument())

        assertEquals(1, result.created)
        assertEquals(0, result.failed)
        assertTrue(result.warnings.any { it.contains("消息出口列表获取失败") })
        assertEquals("UP-1001", PublisherRepository.findAll().single().name)
        assertEquals("2001", SubscriberRepository.findAll().single().name)
    }

    @Test
    fun `import should keep valid rows when one row cannot be parsed for prefetch`() = runBlocking {
        initTestDatabase("admin-import-prefetch-invalid-row-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val messageSink = RecordingMessageSinkPlugin()
        val service = testAdminService(
            publisherLookup = publisherLookup,
            messageSink = messageSink,
        )

        val result = service.importSubscriptions(
            importDocument(
                subscriptions = listOf(
                    importItem(targetId = "2001"),
                    importItem(targetId = "bad", targetKind = "UNKNOWN_KIND"),
                ),
            ),
        )

        assertEquals(1, result.created)
        assertEquals(1, result.failed)
        assertEquals(listOf(listOf("1001")), publisherLookup.batchRequests)
        assertEquals(listOf<TargetKind?>(TargetKind.GROUP), messageSink.listRequests)
        assertTrue(result.items.any { it.status == "FAILED" && it.message.contains("target.targetKind") })
        assertEquals("QQ群-2001", SubscriberRepository.findAll().single().name)
    }

    @Test
    fun `import should not batch lookup non user publishers`() = runBlocking {
        initTestDatabase("admin-import-prefetch-non-user-publisher-test")
        val publisherLookup = RecordingPublisherLookupPlugin()
        val messageSink = RecordingMessageSinkPlugin()
        val service = testAdminService(
            publisherLookup = publisherLookup,
            messageSink = messageSink,
        )

        val result = service.importSubscriptions(
            importDocument(
                subscriptions = listOf(
                    importItem(
                        publisherId = "topic-1",
                        publisherKind = PublisherKind.OTHER.name,
                    ),
                ),
            ),
        )

        assertEquals(1, result.created)
        assertEquals(emptyList<List<String>>(), publisherLookup.batchRequests)
        assertEquals("topic-1", PublisherRepository.findAll().single().name)
        assertEquals("QQ群-2001", SubscriberRepository.findAll().single().name)
    }

    private fun testAdminService(
        publisherLookup: RecordingPublisherLookupPlugin,
        messageSink: RecordingMessageSinkPlugin,
    ): AdminService {
        return AdminService(
            pluginProvider = { emptyList() },
            messageSinkProvider = {
                listOf(
                    PluginHandle(
                        info = pluginInfo("qq-sink"),
                        instance = messageSink,
                    ),
                )
            },
            publisherLookupResolver = { platformId ->
                publisherLookup.takeIf { platformId.equals("bilibili", ignoreCase = true) }
            },
            publisherFollowResolver = { null },
            configProvider = { MainDynamicConfig() },
        )
    }

    private fun pluginInfo(id: String): PluginInfo {
        return PluginInfo(
            descriptor = PluginDescriptor(
                id = id,
                name = id,
                version = "test",
                mainClass = id,
            ),
            capabilities = emptySet(),
            state = PluginState.ACTIVE,
            sourceJarPath = "",
        )
    }

    private fun importDocument(
        subscriptions: List<SubscriptionExportItem> = listOf(importItem()),
        importOptions: SubscriptionImportOptions = SubscriptionImportOptions(),
    ): SubscriptionExportDocument {
        return SubscriptionExportDocument(
            exportedAtEpochSeconds = 1,
            subscriptions = subscriptions,
            importOptions = importOptions,
        )
    }

    private fun importItem(
        publisherId: String = "1001",
        publisherKind: String = PublisherKind.USER.name,
        targetId: String = "2001",
        targetKind: String = TargetKind.GROUP.name,
    ): SubscriptionExportItem {
        return SubscriptionExportItem(
            publisher = SubscriptionExportPublisher(
                platformId = "bilibili",
                kind = publisherKind,
                externalId = publisherId,
            ),
            target = SubscriptionExportTarget(
                platformId = "qq",
                targetKind = targetKind,
                externalId = targetId,
            ),
        )
    }

    private class RecordingPublisherLookupPlugin : PublisherBatchLookupPlugin {
        val singleRequests = mutableListOf<String>()
        val batchRequests = mutableListOf<List<String>>()

        override val platformId: PlatformId = PlatformId.of("bilibili")

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            singleRequests += userId
            return publisherInfo(userId)
        }

        override suspend fun fetchPublisherInfos(userIds: Collection<String>): Map<String, PublisherInfo> {
            val ids = userIds.toList()
            batchRequests += ids
            return ids.associateWith(::publisherInfo)
        }

        private fun publisherInfo(userId: String): PublisherInfo {
            return PublisherInfo(
                key = PublisherKey.of("bilibili", externalId = userId),
                name = "UP-$userId",
                avatar = MediaRef("https://example.com/$userId.png", MediaKind.AVATAR),
            )
        }
    }

    private class RecordingMessageSinkPlugin(
        private val failList: Boolean = false,
    ) : MessageSinkPlugin {
        val listRequests = mutableListOf<TargetKind?>()

        override val transportId: String = "qq"
        override val supportedTargetPlatforms: Set<PlatformId> = setOf(PlatformId.of("qq"))
        override val supportedTargetKinds: Set<TargetKind> = setOf(TargetKind.GROUP)

        override suspend fun listMessageTargets(kind: TargetKind?): List<MessageTargetCandidate> {
            listRequests += kind
            if (failList) throw IllegalStateException("目标列表暂时不可用")
            return listOf("2001", "2002").map { targetId ->
                MessageTargetCandidate(
                    address = TargetAddress.of(
                        platformId = "qq",
                        kind = TargetKind.GROUP,
                        externalId = targetId,
                    ),
                    name = "QQ群-$targetId",
                    avatar = MediaRef("https://example.com/group-$targetId.png", MediaKind.AVATAR),
                )
            }
        }
    }
}
