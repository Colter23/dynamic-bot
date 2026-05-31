package top.colter.dynamic.draw

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DrawThemeFactoryTest {
    @Test
    fun singleColorShouldGenerateThreeColorGradient() {
        val theme = DrawThemeFactory.fromThemeColorText("#FE65A6")

        assertEquals(3, theme.backgroundColors.size)
        assertTrue(theme.backgroundColors.distinct().size > 1)
    }

    @Test
    fun multiColorTextShouldUseSemicolonOrder() {
        val theme = DrawThemeFactory.fromThemeColorText("#FE65A6; #BFFAFF")

        assertEquals(2, theme.backgroundColors.size)
        assertEquals(DrawThemeFactory.parseThemeColors("#FE65A6; #BFFAFF"), listOf("#FE65A6", "#BFFAFF"))
    }

    @Test
    fun highSaturationColorShouldBeSoftenedForBackground() {
        val theme = DrawThemeFactory.fromThemeColorText("#FF0000")
        val hsb = Color.RGBtoHSB(
            theme.backgroundColors.first().red(),
            theme.backgroundColors.first().green(),
            theme.backgroundColors.first().blue(),
            null,
        )

        assertEquals(0.30f, hsb[1], absoluteTolerance = 0.01f)
        assertEquals(1.0f, hsb[2], absoluteTolerance = 0.01f)
    }

    @Test
    fun themeModeShouldSwitchCardAndTextColors() {
        val lightTheme = DrawThemeFactory.fromThemeColorText("#FFFFFF")
        val pinkTheme = DrawThemeFactory.fromThemeColorText("#FE65A6")
        val darkTheme = DrawThemeFactory.fromThemeColorText("#000000")

        assertEquals(DrawThemeMode.LIGHT, lightTheme.mode)
        assertEquals(DrawThemeMode.LIGHT, pinkTheme.mode)
        assertEquals(DrawThemeMode.DARK, darkTheme.mode)
        assertTrue(relativeLuminance(lightTheme.textColor) < relativeLuminance(lightTheme.cardColor))
        assertTrue(relativeLuminance(darkTheme.textColor) > relativeLuminance(darkTheme.cardColor))
    }

    @Test
    fun linkColorShouldBeReadableOnCard() {
        listOf("#FE65A6", "#101010", "#BFFAFF").forEach { color ->
            val theme = DrawThemeFactory.fromThemeColorText(color)

            assertTrue(
                actual = contrastRatio(theme.linkColor, theme.cardColor) >= 4.5,
                message = "$color 的链接色对比度不足",
            )
        }
    }

    @Test
    fun invalidThemeColorTextShouldUseChineseMessages() {
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                DrawThemeFactory.parseThemeColors("#FE65A6;")
            }.message!!.contains("空项"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                DrawThemeFactory.parseThemeColors("FE65A6")
            }.message!!.contains("颜色必须以 # 开头"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                DrawThemeFactory.parseThemeColors("#FE65AZ")
            }.message!!.contains("非法字符"),
        )
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
        val darker = minOf(relativeLuminance(a), relativeLuminance(b))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun Int.red(): Int = (this ushr 16) and 0xff
    private fun Int.green(): Int = (this ushr 8) and 0xff
    private fun Int.blue(): Int = this and 0xff
}
