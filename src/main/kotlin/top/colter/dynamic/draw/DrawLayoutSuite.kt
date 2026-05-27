package top.colter.dynamic.draw

import org.jetbrains.skia.Image
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.data.Dynamic

sealed interface DrawScene {
    data class DynamicScene(val dynamic: Dynamic) : DrawScene
}

interface DrawLayoutSuite {
    val id: String
    val name: String
        get() = id

    fun render(scene: DrawScene, config: DrawConfig): Image
}

object DrawLayoutRegistry {
    private val suites: MutableMap<String, DrawLayoutSuite> = linkedMapOf(
        DefaultDrawLayoutSuite.id to DefaultDrawLayoutSuite,
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
        val suite = suites[config.settings.layout] ?: DefaultDrawLayoutSuite
        return suite.render(scene, config)
    }
}
