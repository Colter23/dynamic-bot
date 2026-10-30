package top.colter.dynamic.command

import java.util.concurrent.ConcurrentHashMap
import top.colter.dynamic.core.data.CommandContext

/**
 * `list` 输出时的订阅快照，让用户可以直接用 `unsubscribe #序号` 退订。
 *
 * 快照按「会话 + 发送者」保存并带 TTL：数据库的订阅列表没有稳定排序保证，
 * 依赖实时查询的下标会出现"看到的 3 号其实是另一个"。快照保证序号与用户刚看到的一致。
 */
internal class SubscriptionListSnapshotStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = SNAPSHOT_TTL_MILLIS,
) {
    internal data class Entry(
        val platformId: String,
        val externalId: String,
        val name: String,
    )

    private data class Snapshot(
        val entries: List<Entry>,
        val expiresAtMillis: Long,
    )

    private val snapshots: MutableMap<String, Snapshot> = ConcurrentHashMap()

    fun save(context: CommandContext, entries: List<Entry>) {
        if (entries.isEmpty()) return
        prune()
        snapshots[key(context)] = Snapshot(entries, clock() + ttlMillis)
    }

    /** 取出序号对应的订阅项；越界或过期返回 null。 */
    fun resolve(context: CommandContext, index: Int): Entry? {
        val snapshot = validSnapshot(context) ?: return null
        return snapshot.entries.getOrNull(index - 1)
    }

    fun size(context: CommandContext): Int? {
        return validSnapshot(context)?.entries?.size
    }

    private fun validSnapshot(context: CommandContext): Snapshot? {
        val snapshot = snapshots[key(context)] ?: return null
        if (clock() > snapshot.expiresAtMillis) {
            snapshots.remove(key(context))
            return null
        }
        return snapshot
    }

    private fun prune() {
        val now = clock()
        snapshots.entries.removeIf { now > it.value.expiresAtMillis }
    }

    private fun key(context: CommandContext): String {
        return "${context.target.stableValue()}|${context.senderId}"
    }

    internal companion object {
        internal const val SNAPSHOT_TTL_MILLIS: Long = 3 * 60 * 1000L
    }
}
