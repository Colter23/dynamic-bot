package top.colter.dynamic.draw

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.repository.PublisherDrawThemeRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher

class PublisherDrawThemeServiceTest {
    @Test
    fun storedPublisherThemeShouldWinAndSkipAvatarExtraction() {
        initTestDatabase("publisher-theme-stored")
        val publisher = testPublisher(id = 1)
        PublisherRepository.create(publisher)
        val storedPalette = DrawThemeFactory.fromThemeColorText("#BFFAFF").toPalette()
        PublisherDrawThemeRepository.upsert(publisher.id, storedPalette)
        val service = PublisherDrawThemeService(
            avatarThemeExtractor = AvatarThemeExtractor { error("不应读取头像") },
        )

        val theme = service.resolveTheme(
            update = testDynamicUpdate(publisher = publisher.toInfo()),
            storedPublisher = publisher,
            settings = DrawSettings(themeColors = "#FE65A6", autoTheme = true),
        )

        assertEquals(storedPalette, theme.toPalette())
    }

    @Test
    fun autoThemeShouldExtractFromAvatarAndPersistWhenPublisherExists() {
        initTestDatabase("publisher-theme-auto")
        val publisher = testPublisher(
            id = 1,
            avatar = testMedia("https://example.com/avatar.png", MediaKind.AVATAR),
        )
        PublisherRepository.create(publisher)
        val service = PublisherDrawThemeService(
            avatarThemeExtractor = AvatarThemeExtractor { pngBytes(Color(210, 80, 120)) },
        )

        val theme = service.resolveTheme(
            update = testDynamicUpdate(publisher = publisher.toInfo()),
            storedPublisher = publisher,
            settings = DrawSettings(themeColors = "#BFFAFF", autoTheme = true),
        )

        assertEquals(theme.toPalette(), assertNotNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id)).palette)
    }

    @Test
    fun autoThemeDisabledShouldUseGlobalThemeAndNotPersist() {
        initTestDatabase("publisher-theme-disabled")
        val publisher = testPublisher(id = 1)
        PublisherRepository.create(publisher)
        val settings = DrawSettings(themeColors = "#BFFAFF", autoTheme = false)
        val service = PublisherDrawThemeService(
            avatarThemeExtractor = AvatarThemeExtractor { error("不应读取头像") },
        )

        val theme = service.resolveTheme(
            update = testDynamicUpdate(publisher = publisher.toInfo()),
            storedPublisher = publisher,
            settings = settings,
        )

        assertEquals(DrawThemeFactory.fromSettings(settings).toPalette(), theme.toPalette())
        assertNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    @Test
    fun autoThemeWithoutStoredPublisherShouldBeTransient() {
        initTestDatabase("publisher-theme-transient")
        val publisher = testPublisher(id = 1)
        val service = PublisherDrawThemeService(
            avatarThemeExtractor = AvatarThemeExtractor { pngBytes(Color(80, 120, 210)) },
        )

        val theme = service.resolveTheme(
            update = testDynamicUpdate(publisher = publisher.toInfo()),
            storedPublisher = null,
            settings = DrawSettings(themeColors = "#FE65A6", autoTheme = true),
        )

        assertNotNull(theme)
        assertNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    @Test
    fun unreadableAvatarShouldFallbackToGlobalTheme() {
        initTestDatabase("publisher-theme-avatar-fallback")
        val publisher = testPublisher(id = 1)
        PublisherRepository.create(publisher)
        val settings = DrawSettings(themeColors = "#BFFAFF", autoTheme = true)
        val service = PublisherDrawThemeService(
            avatarThemeExtractor = AvatarThemeExtractor { byteArrayOf(1, 2, 3) },
        )

        val theme = service.resolveTheme(
            update = testDynamicUpdate(publisher = publisher.toInfo()),
            storedPublisher = publisher,
            settings = settings,
        )

        assertEquals(DrawThemeFactory.fromSettings(settings).toPalette(), theme.toPalette())
        assertNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    private fun pngBytes(color: Color): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}
