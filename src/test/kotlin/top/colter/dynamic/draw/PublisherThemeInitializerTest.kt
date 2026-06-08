package top.colter.dynamic.draw

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.DrawSettings
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.draw.image.DynamicImageLoader
import top.colter.dynamic.initTestDatabase
import top.colter.dynamic.repository.PublisherDrawThemeRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.testMedia
import top.colter.dynamic.testPublisher

class PublisherThemeInitializerTest {
    @Test
    fun firstSubscriptionShouldGenerateAndStoreThemeWhenAutoThemeEnabled() = runBlocking {
        initTestDatabase("publisher-theme-initializer-auto")
        val publisher = testPublisher(
            id = 1,
            avatar = testMedia("https://example.com/avatar.png", MediaKind.AVATAR),
        )
        PublisherRepository.create(publisher)
        var loadCalls = 0
        val initializer = DefaultPublisherThemeInitializer(
            configProvider = { MainDynamicConfig(draw = DrawSettings(autoTheme = true)) },
            imageLoader = DynamicImageLoader { loadCalls += 1 },
            themeService = PublisherDrawThemeService(
                avatarThemeExtractor = AvatarThemeExtractor { pngBytes(Color(210, 80, 120)) },
            ),
        )

        initializer.initializeAfterFirstSubscription(publisher, previousSubscriptionCount = 0)

        assertEquals(1, loadCalls)
        assertNotNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    @Test
    fun publisherUpsertShouldGenerateAndStoreThemeWhenAutoThemeEnabled() = runBlocking {
        initTestDatabase("publisher-theme-initializer-upsert-auto")
        val publisher = testPublisher(
            id = 1,
            avatar = testMedia("https://example.com/avatar.png", MediaKind.AVATAR),
        )
        PublisherRepository.create(publisher)
        var loadCalls = 0
        val initializer = DefaultPublisherThemeInitializer(
            configProvider = { MainDynamicConfig(draw = DrawSettings(autoTheme = true)) },
            imageLoader = DynamicImageLoader { loadCalls += 1 },
            themeService = PublisherDrawThemeService(
                avatarThemeExtractor = AvatarThemeExtractor { pngBytes(Color(64, 132, 220)) },
            ),
        )

        initializer.initializeAfterPublisherUpsert(publisher)

        assertEquals(1, loadCalls)
        assertNotNull(PublisherDrawThemeRepository.findByPublisherId(publisher.id))
    }

    @Test
    fun initializerShouldSkipWhenAutoThemeDisabledOrNotFirstSubscription() = runBlocking {
        initTestDatabase("publisher-theme-initializer-skip")
        val publisher = testPublisher(id = 1)
        PublisherRepository.create(publisher)
        var loadCalls = 0
        val initializer = DefaultPublisherThemeInitializer(
            configProvider = { MainDynamicConfig(draw = DrawSettings(autoTheme = false)) },
            imageLoader = DynamicImageLoader { loadCalls += 1 },
            themeService = PublisherDrawThemeService(
                avatarThemeExtractor = AvatarThemeExtractor { pngBytes(Color(210, 80, 120)) },
            ),
        )

        initializer.initializeAfterFirstSubscription(publisher, previousSubscriptionCount = 0)
        initializer.initializeAfterFirstSubscription(
            publisher,
            previousSubscriptionCount = 1,
        )

        assertEquals(0, loadCalls)
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
