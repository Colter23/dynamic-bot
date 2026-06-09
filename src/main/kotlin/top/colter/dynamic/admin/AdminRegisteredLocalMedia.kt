package top.colter.dynamic.admin

import java.net.URI
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import top.colter.dynamic.table.PublisherLiveRecordTable
import top.colter.dynamic.table.PublisherLiveStatusTable
import top.colter.dynamic.table.PublisherTable
import top.colter.dynamic.table.SubscriberTable

public fun interface AdminRegisteredLocalMediaLookup {
    public fun contains(path: Path): Boolean
}

public object EmptyAdminRegisteredLocalMediaLookup : AdminRegisteredLocalMediaLookup {
    override fun contains(path: Path): Boolean = false
}

public object DatabaseAdminRegisteredLocalMediaLookup : AdminRegisteredLocalMediaLookup {
    override fun contains(path: Path): Boolean {
        val normalizedPath = AdminLocalMediaPath.normalizeExistingOrRequested(path)
        return transaction {
            registeredMediaUris().any { uri ->
                AdminLocalMediaPath.fromUri(uri) == normalizedPath
            }
        }
    }

    private fun registeredMediaUris(): List<String> {
        return buildList {
            PublisherTable.selectAll().forEach { row ->
                add(row[PublisherTable.avatar].uri)
                row[PublisherTable.banner]?.uri?.let(::add)
                row[PublisherTable.pendant]?.uri?.let(::add)
            }
            SubscriberTable.selectAll().forEach { row ->
                row[SubscriberTable.avatar]?.uri?.let(::add)
            }
            PublisherLiveStatusTable.selectAll().forEach { row ->
                row[PublisherLiveStatusTable.cover]?.uri?.let(::add)
            }
            PublisherLiveRecordTable.selectAll().forEach { row ->
                row[PublisherLiveRecordTable.cover]?.uri?.let(::add)
            }
        }
    }
}

internal object AdminLocalMediaPath {
    private val runtimeRoot: Path = Paths.get("").toAbsolutePath().normalize()
    private val windowsAbsolutePath = Regex("""^[A-Za-z]:[\\/].+""")

    fun fromUri(uri: String): Path? {
        val rawPath = rawPathFor(uri.trim()) ?: return null
        val resolved = if (rawPath.isAbsolute) rawPath.normalize() else runtimeRoot.resolve(rawPath).normalize()
        return normalizeExistingOrRequested(resolved)
    }

    fun normalizeExistingOrRequested(path: Path): Path {
        return runCatching { path.toRealPath(LinkOption.NOFOLLOW_LINKS) }
            .getOrElse { path.toAbsolutePath().normalize() }
    }

    private fun rawPathFor(uri: String): Path? {
        if (uri.isBlank()) return null
        if (windowsAbsolutePath.matches(uri) || uri.startsWith("\\\\")) {
            return runCatching { Paths.get(uri) }.getOrNull()
        }

        val parsed = runCatching { URI(uri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (scheme == "file") return runCatching { Paths.get(parsed) }.getOrNull()
        if (scheme != null) return null

        return runCatching { Paths.get(uri) }.getOrNull()
    }
}
