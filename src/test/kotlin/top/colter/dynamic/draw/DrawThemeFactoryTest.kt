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

            assertEquals(theme.readableAccentColor, theme.linkColor)
            assertTrue(
                actual = contrastRatio(theme.linkColor, theme.cardColor) >= 4.5,
                message = "$color 的链接色对比度不足",
            )
        }
    }

    @Test
    fun primaryColorShouldBeBrighterThanReadableLinkColorInLightTheme() {
        val theme = DrawThemeFactory.fromThemeColorText("#FE65A6")

        assertEquals(DrawThemeMode.LIGHT, theme.mode)
        assertTrue(
            actual = relativeLuminance(theme.primaryColor) > relativeLuminance(theme.linkColor),
            message = "亮色主题的强调色应比正文链接色更明亮",
        )
        assertTrue(
            actual = relativeLuminance(theme.primaryColor) > 0.22,
            message = "亮色主题的强调色不应过暗",
        )
        assertTrue(
            actual = contrastRatio(theme.linkColor, theme.cardColor) >= 4.5,
            message = "正文链接色仍需保持可读",
        )
    }

    @Test
    fun onPrimaryColorShouldKeepDecorativeBadgesReadableEnough() {
        val pinkTheme = DrawThemeFactory.fromThemeColorText("#FE65A6")
        val cyanTheme = DrawThemeFactory.fromThemeColorText("#BFFAFF")
        val darkTheme = DrawThemeFactory.fromThemeColorText("#101624;#24182D;#0D2630")

        assertEquals(WHITE, pinkTheme.onPrimaryColor)
        assertEquals(BLACK, cyanTheme.onPrimaryColor)
        assertTrue(contrastRatio(pinkTheme.onPrimaryColor, pinkTheme.primaryColor) >= 2.6)
        assertTrue(contrastRatio(cyanTheme.onPrimaryColor, cyanTheme.primaryColor) >= 2.6)
        assertTrue(contrastRatio(darkTheme.onPrimaryColor, darkTheme.primaryColor) >= 2.6)
    }

    @Test
    fun qrPointColorShouldStayVisibleOnLightSurface() {
        listOf("#FE65A6", "#BFFAFF", "#FFD700").forEach { color ->
            val theme = DrawThemeFactory.fromThemeColorText(color)

            assertTrue(
                actual = contrastRatio(theme.qrPointColor, WHITE) >= 2.4,
                message = "$color 的二维码点色在浅色底上不够清楚",
            )
        }
    }

    @Test
    fun neutralThemeColorShouldGenerateSoftNeutralBackground() {
        val theme = DrawThemeFactory.fromThemeColorText("#808080")
        val hsb = Color.RGBtoHSB(
            theme.backgroundColors.first().red(),
            theme.backgroundColors.first().green(),
            theme.backgroundColors.first().blue(),
            null,
        )

        assertTrue(
            actual = hsb[1] <= 0.13f,
            message = "低饱和主题不应生成过强的彩色背景",
        )
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

    private companion object {
        const val WHITE: Int = -0x1
        const val BLACK: Int = -0x1000000
    }
}
