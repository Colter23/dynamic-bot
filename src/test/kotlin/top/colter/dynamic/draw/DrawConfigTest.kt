import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.draw.DrawConfig
import top.colter.skiko.FontRegistry
import kotlin.test.Test
import kotlin.test.assertNotNull

class DrawConfigTest {
    @Test
    fun `draw config loads bundled default fonts`() {
        val fontRegistry = FontRegistry()

        DrawConfig(
            platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            fontRegistry = fontRegistry,
        )

        assertNotNull(fontRegistry.defaultFont)
        assertNotNull(fontRegistry.emojiFont)
    }
}
