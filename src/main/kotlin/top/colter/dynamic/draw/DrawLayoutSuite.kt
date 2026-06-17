package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.DRAW_BASE_WIDTH
import top.colter.dynamic.DRAW_SCALE_MAX
import top.colter.dynamic.DRAW_SCALE_MIN
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.draw.layout.default.DefaultDrawLayoutSuite
import top.colter.dynamic.draw.layout.minimal.MinimalDrawLayoutSuite
import top.colter.skiko.Dp
import top.colter.skiko.dp

public sealed interface DrawScene {
    data class DynamicScene(val update: SourceUpdate) : DrawScene
    data class LiveScene(val update: SourceUpdate) : DrawScene
}

public interface DrawLayoutSuite {
    val id: String
    val name: String
        get() = id

    fun render(scene: DrawScene, config: DrawConfig): Image
}

public object DrawLayoutRegistry {
    private val suites: MutableMap<String, DrawLayoutSuite> = linkedMapOf(
        DefaultDrawLayoutSuite.id to DefaultDrawLayoutSuite,
        MinimalDrawLayoutSuite.id to MinimalDrawLayoutSuite,
    )

    fun register(suite: DrawLayoutSuite) {
        require(suite.id.isNotBlank()) { "draw layout suite id must not be blank" }
        suites[suite.id] = suite
    }

    fun hasSuite(id: String): Boolean = suites.containsKey(id)

    fun options(): List<ConfigFieldOption> {
        return suites.values.map { ConfigFieldOption(it.id, it.name) }
    }

    fun render(scene: DrawScene, config: DrawConfig): Image {
        Dp.factor = config.settings.scale.toDpFactor()
        val suite = suites[config.settings.layout] ?: DefaultDrawLayoutSuite
        return suite.render(scene, config)
    }
}

internal val DRAW_BASE_WIDTH_DP: Dp = DRAW_BASE_WIDTH.dp

private fun Double.toDpFactor(): Float {
    require(!isNaN() && !isInfinite()) { "绘图倍率必须是有效数字" }
    require(this in DRAW_SCALE_MIN..DRAW_SCALE_MAX) { "绘图倍率必须在 $DRAW_SCALE_MIN 到 $DRAW_SCALE_MAX 之间" }
    val factor = toFloat()
    require(factor.isFinite()) { "绘图倍率过大" }
    return factor
}
