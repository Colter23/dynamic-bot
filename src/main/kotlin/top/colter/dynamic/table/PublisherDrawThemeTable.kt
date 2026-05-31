package top.colter.dynamic.table

import kotlin.time.Instant
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.datetime.timestamp
import top.colter.dynamic.core.tools.nowInstant
import top.colter.dynamic.draw.DrawThemePalette

public object PublisherDrawThemeTable : IntIdTable("publisher_draw_theme") {
    public val publisherId: Column<EntityID<Int>> = reference("publisher_id", PublisherTable)
    public val palette: Column<DrawThemePalette> = registerColumn("palette_json", drawThemePaletteColumn())
    public val updatedAt: Column<Instant> = timestamp("updated_at").clientDefault { nowInstant() }

    init {
        uniqueIndex(publisherId)
    }
}
