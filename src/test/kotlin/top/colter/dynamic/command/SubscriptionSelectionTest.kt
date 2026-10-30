package top.colter.dynamic.command

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.repository.PersistenceManager
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.testPublisherInfo

class SubscriptionSelectionTest {

    @Test
    fun parseSelectionShouldAcceptHashFormOnly() {
        assertEquals(1, SubscriptionSelectionComponent.parseSelection("#1"))
        assertEquals(2, SubscriptionSelectionComponent.parseSelection("＃2"))
        assertEquals(3, SubscriptionSelectionComponent.parseSelection(" # 3 "))
        assertNull(SubscriptionSelectionComponent.parseSelection("1"))
        assertNull(SubscriptionSelectionComponent.parseSelection("#0"))
        assertNull(SubscriptionSelectionComponent.parseSelection("#abc"))
        assertNull(SubscriptionSelectionComponent.parseSelection("#1234"))
        assertNull(SubscriptionSelectionComponent.parseSelection(""))
    }

    @Test
    fun storeShouldScopeBySenderAndExpire() {
        var now = 0L
        val store = SessionSelectionStore(clock = { now }, ttlMillis = 1_000L)
        val senderA = context("a")
        val senderB = context("b")

        store.offer(senderA, "bilibili", listOf(SessionSelectionStore.Candidate("111", "甲")))

        assertNull(store.take(senderB, 1), "其他发送者不应命中")
        now = 2_000L
        assertNull(store.take(senderA, 1), "过期后不应命中")
    }

    @Test
    fun handleSelectionShouldSubscribeChosenCandidate(): Unit = runBlocking {
        initDb("subscribe-selection")
        val component = component()
        val context = context("sender")

        val text = component.offerCandidates(
            context,
            "bilibili",
            listOf(publisherInfo("111", "甲"), publisherInfo("222", "乙")),
        )
        assertTrue(text.contains("#1"), "应列出候选：$text")
        assertTrue(text.contains("#2"), "应列出候选：$text")

        val reply = component.handleSelection(context, "#2")
        assertNotNull(reply)
        assertTrue(reply.contains("已订阅"), "reply=$reply")

        val publisher = PublisherRepository.findByKey(PublisherKey.of("bilibili", PublisherKind.USER, "222"))
        assertNotNull(publisher)
        val subscriber = SubscriberRepository.findByAddress(TargetAddress.of("onebot", TargetKind.GROUP, "100"))
        assertNotNull(subscriber)
        assertNotNull(SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id))
    }

    @Test
    fun handleSelectionShouldIgnorePlainNumbersAndConsumedIndex(): Unit = runBlocking {
        initDb("subscribe-selection-ignore")
        val component = component()
        val context = context("sender")
        component.offerCandidates(context, "bilibili", listOf(publisherInfo("111", "甲")))

        assertNull(component.handleSelection(context, "1"), "裸数字不应被拦截")
        assertNotNull(component.handleSelection(context, "#1"))
        assertNull(component.handleSelection(context, "#1"), "同一候选只能使用一次")
    }

    @Test
    fun handleSelectionShouldReportOutOfRangeIndexAndKeepCandidates(): Unit = runBlocking {
        initDb("subscribe-selection-range")
        val component = component()
        val context = context("sender")
        component.offerCandidates(context, "bilibili", listOf(publisherInfo("111", "甲")))

        val reply = component.handleSelection(context, "#9")
        assertNotNull(reply)
        assertTrue(reply.contains("序号无效"), "reply=$reply")

        // 越界不应消耗候选
        assertNotNull(component.handleSelection(context, "#1"))
    }

    private fun component(): SubscriptionSelectionComponent {
        val lookup = FakeLookupPlugin()
        return SubscriptionSelectionComponent(
            SubscriptionService(
                publisherLookupResolver = { platform -> if (platform == "bilibili") lookup else null },
                publisherFollowResolver = { null },
                configProvider = { MainDynamicConfig() },
                eventBus = EventBus(),
                themeInitializer = PublisherThemeInitializer { _, _ -> },
            ),
        )
    }

    private fun context(senderId: String): CommandContext {
        return CommandContext.of("onebot", TargetKind.GROUP, "100", senderId)
    }

    private fun publisherInfo(externalId: String, name: String): PublisherInfo {
        return testPublisherInfo(
            key = PublisherKey.of("bilibili", PublisherKind.USER, externalId),
            name = name,
        )
    }

    private fun initDb(suffix: String) {
        val dir = createTempDirectory("dynamic-bot-$suffix").toFile()
        PersistenceManager.init(dir.resolve("test.db").path)
    }

    private class FakeLookupPlugin : PublisherLookupPlugin {
        override val platformId: PlatformId = PlatformId.of("bilibili")

        override suspend fun fetchPublisherInfo(userId: String): PublisherInfo? {
            return testPublisherInfo(
                key = PublisherKey.of("bilibili", PublisherKind.USER, userId),
                name = "user-$userId",
            )
        }
    }
}
