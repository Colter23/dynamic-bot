package top.colter.dynamic.repository

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.draw.DrawThemePalette
import top.colter.dynamic.table.PublisherDrawThemeTable
import top.colter.dynamic.table.PublisherTable

public data class PublisherDrawTheme(
    val publisherId: Int,
    val palette: DrawThemePalette,
    val updatedAtEpochSeconds: Long,
)

public object PublisherDrawThemeRepository {
    public fun findByPublisherId(publisherId: Int): PublisherDrawTheme? {
        return transaction {
            PublisherDrawThemeTable
                .selectAll()
                .where { PublisherDrawThemeTable.publisherId eq EntityID(publisherId, PublisherTable) }
                .firstOrNull()
                ?.toPublisherDrawTheme()
        }
    }

    public fun upsert(publisherId: Int, palette: DrawThemePalette): PublisherDrawTheme {
        val now = nowInstant()
        transaction {
            val inserted = PublisherDrawThemeTable.insertIgnore {
                it[PublisherDrawThemeTable.publisherId] = EntityID(publisherId, PublisherTable)
                it[PublisherDrawThemeTable.palette] = palette
                it[updatedAt] = now
            }.insertedCount > 0
            if (!inserted) {
                PublisherDrawThemeTable.update({
                    PublisherDrawThemeTable.publisherId eq EntityID(publisherId, PublisherTable)
                }) {
                    it[PublisherDrawThemeTable.palette] = palette
                    it[updatedAt] = now
                }
            }
        }
        return findByPublisherId(publisherId)
            ?: error("发布者主题已写入但无法重新读取：publisherId=$publisherId")
    }

    public fun deleteByPublisherId(publisherId: Int): Boolean {
        return transaction {
            PublisherDrawThemeTable.deleteWhere {
                PublisherDrawThemeTable.publisherId eq EntityID(publisherId, PublisherTable)
            } > 0
        }
    }
}

public fun ResultRow.toPublisherDrawTheme(): PublisherDrawTheme = PublisherDrawTheme(
    publisherId = this[PublisherDrawThemeTable.publisherId].value,
    palette = this[PublisherDrawThemeTable.palette],
    updatedAtEpochSeconds = this[PublisherDrawThemeTable.updatedAt].epochSeconds,
)
