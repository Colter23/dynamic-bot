package top.colter.dynamic.command

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository

private val selectionLogger = loggerFor<SubscriptionSelectionComponent>()

/**
 * 订阅动作的共用实现：命令与"候选选择"两条入口都走这里，
 * 保证自动关注、主题初始化、事件广播的行为一致。
 */
internal class SubscriptionService(
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin?,
    private val publisherFollowResolver: (String) -> PublisherFollowPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val eventBus: EventBus,
    private val themeInitializer: PublisherThemeInitializer,
) {
    fun lookup(platform: String): PublisherLookupPlugin? = publisherLookupResolver(platform)

    fun autoFollowEnabled(): Boolean = configProvider().subscription.autoFollowPublisherOnSubscribe

    /** 已经拿到发布者资料，执行订阅；返回给用户看的文案。 */
    suspend fun subscribeResolved(
        context: CommandContext,
        platform: String,
        publisherInfo: PublisherInfo,
    ): String {
        val autoFollowEnabled = autoFollowEnabled()
        val autoFollowOutcome = if (autoFollowEnabled) {
            tryAutoFollow(platform, publisherInfo.key.externalId)
        } else {
            AutoFollowOutcome(followed = false, warning = null)
        }

        val publisherUpsert = PublisherRepository.upsertInfo(publisherInfo)
        val previousSubscriptionCount = SubscriptionRepository.countByPublisherId(publisherUpsert.value.id)
        val subscriber = SubscriberRepository.ensure(
            address = context.target,
            name = context.chatId,
        )
        val mutation = SubscriptionRepository.subscribe(subscriber.id, publisherUpsert.value.id)
        mutation.event?.let { eventBus.broadcast(it) }
        if (mutation.changed) {
            themeInitializer.initializeAfterFirstSubscription(
                publisher = publisherUpsert.value,
                previousSubscriptionCount = previousSubscriptionCount,
            )
        }

        val publisherState = if (publisherUpsert.created) "新建" else "已存在"
        val subscriptionState = if (mutation.changed) "新建" else "已存在"
        val followStateText = when {
            !autoFollowEnabled -> "已关闭"
            autoFollowOutcome.followed -> "是"
            else -> "否"
        }
        val warningText = autoFollowOutcome.warning?.let { "，自动关注提示=$it" }.orEmpty()
        return "已订阅：${publisherUpsert.value.name}（自动关注=$followStateText，发布者=$publisherState，订阅=$subscriptionState$warningText）"
    }

    private suspend fun tryAutoFollow(platform: String, publisherUserId: String): AutoFollowOutcome {
        val followPlugin = publisherFollowResolver(platform)
            ?: return AutoFollowOutcome(followed = false, warning = "未找到发布者关注插件：$platform")
        val followResult = followPlugin.followPublisher(publisherUserId)
        return when (followResult.status) {
            FollowActionStatus.DONE -> AutoFollowOutcome(followed = true, warning = null)
            FollowActionStatus.NOOP -> AutoFollowOutcome(followed = false, warning = null)
            FollowActionStatus.FAILED -> AutoFollowOutcome(
                followed = false,
                warning = followResult.message ?: "关注发布者失败：$platform",
            )
            FollowActionStatus.UNSUPPORTED -> AutoFollowOutcome(
                followed = false,
                warning = "$platform 不支持自动关注",
            )
        }
    }

    private data class AutoFollowOutcome(
        val followed: Boolean,
        val warning: String?,
    )
}

/** 候选选择的有效期与容量。 */
internal class SessionSelectionStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SELECTION_TTL_MILLIS,
) {
    internal data class Candidate(
        val externalId: String,
        val name: String,
    )

    internal data class Pending(
        val platformId: String,
        val candidates: List<Candidate>,
        val expiresAtMillis: Long,
    )

    private val pending: MutableMap<String, Pending> = ConcurrentHashMap()

    fun offer(context: CommandContext, platformId: String, candidates: List<Candidate>): Pending? {
        if (candidates.isEmpty()) return null
        pruneExpired()
        val next = Pending(
            platformId = platformId,
            candidates = candidates,
            expiresAtMillis = clock() + ttlMillis,
        )
        pending[key(context)] = next
        return next
    }

    /** 取出序号对应的候选；索引越界、过期或不存在都返回 null。 */
    fun take(context: CommandContext, index: Int): Pair<String, Candidate>? {
        val selection = validPending(context) ?: return null
        val candidate = selection.candidates.getOrNull(index - 1) ?: return null
        pending.remove(key(context))
        return selection.platformId to candidate
    }

    /** 当前会话是否还有待选候选，以及候选数量；用于提示"序号无效"。 */
    fun pendingCount(context: CommandContext): Int? {
        return validPending(context)?.candidates?.size
    }

    private fun validPending(context: CommandContext): Pending? {
        val selection = pending[key(context)] ?: return null
        if (clock() > selection.expiresAtMillis) {
            pending.remove(key(context))
            return null
        }
        return selection
    }

    /** 清理过期待办，避免长期运行时无限累积。 */
    private fun pruneExpired() {
        val now = clock()
        pending.entries.removeIf { now > it.value.expiresAtMillis }
    }

    private fun key(context: CommandContext): String {
        return "${context.target.stableValue()}|${context.senderId}"
    }

    internal companion object {
        internal const val SELECTION_TTL_MILLIS: Long = 3 * 60 * 1000L
    }
}

/**
 * 用户名搜索与 `#序号` 选择的组合件。
 *
 * 订阅命令在搜索命中多条时把候选存进 [store]，用户随后回复 `#1` 才真正订阅，
 * 满足"用户名搜索必须选择结果、UID 才能直接提交"的约束。
 */
public class SubscriptionSelectionComponent internal constructor(
    private val service: SubscriptionService,
    private val store: SessionSelectionStore = SessionSelectionStore(),
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    internal fun service(): SubscriptionService = service

    internal fun maxCandidates(): Int = maxCandidates

    /** 展示候选并登记待办，返回给用户的文案。 */
    internal fun offerCandidates(
        context: CommandContext,
        platform: String,
        candidates: List<PublisherInfo>,
    ): String {
        val limited = candidates.take(maxCandidates)
        val stored = limited.map { info ->
            SessionSelectionStore.Candidate(
                externalId = info.key.externalId,
                name = sanitize(info.name.ifBlank { info.key.externalId }),
            )
        }
        store.offer(context, platform, stored)
        val lines = stored.mapIndexed { index, candidate ->
            "#${index + 1}  ${candidate.name}（${candidate.externalId}）"
        }
        return buildString {
            appendLine("找到 ${stored.size} 个结果，回复 #序号 选择（${SessionSelectionStore.SELECTION_TTL_MILLIS / 60_000} 分钟内有效）：")
            append(lines.joinToString("\n"))
        }
    }

    /**
     * 处理 `#序号` 形式的回复。
     * 返回 null 表示不是候选选择消息，交给后续链路处理。
     */
    internal suspend fun handleSelection(context: CommandContext, rawText: String): String? {
        val index = parseSelection(rawText) ?: return null
        val taken = store.take(context, index)
        if (taken == null) {
            // 有候选但序号越界时给出提示；没有候选则说明不是选择消息，交给后续链路。
            val count = store.pendingCount(context) ?: return null
            return "序号无效：请回复 #1 到 #$count（${SessionSelectionStore.SELECTION_TTL_MILLIS / 60_000} 分钟内有效）。"
        }
        val (platform, candidate) = taken
        val lookup = service.lookup(platform)
            ?: return "未找到发布者查询插件：$platform"
        val publisherInfo = runCatching { lookup.fetchPublisherInfo(candidate.externalId) }
            .getOrElse { return "获取发布者资料失败：${it.message ?: "未知错误"}" }
            ?: return "未找到发布者：$platform:${candidate.externalId}"
        return runCatching { service.subscribeResolved(context, platform, publisherInfo) }
            .getOrElse { error ->
                selectionLogger.warn(error) { "候选选择订阅失败：platform=$platform，externalId=${candidate.externalId}" }
                "订阅失败：${error.message ?: "未知错误"}"
            }
    }

    private fun sanitize(value: String): String {
        return value.replace('\n', ' ').replace('\r', ' ').trim().take(60)
    }

    internal companion object {
        internal const val DEFAULT_MAX_CANDIDATES: Int = 5

        /** 解析 `#1` / `＃2` / `# 3`；其它内容返回 null。 */
        internal fun parseSelection(rawText: String): Int? {
            val normalized = rawText
                .trim()
                .replace('＃', '#')
                .replace(" ", "")
            if (!normalized.startsWith("#")) return null
            val digits = normalized.drop(1)
            if (digits.isEmpty() || digits.length > 3 || digits.any { !it.isDigit() }) return null
            return digits.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
