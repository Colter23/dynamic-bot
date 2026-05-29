package top.colter.dynamic.draw

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.mediaReferences
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia
import top.colter.dynamic.testOutput
import top.colter.skiko.Dp
import top.colter.skiko.FontUtils

class DrawTest {
    @BeforeTest
    fun init() {
        Dp.factor = 1f
        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test dynamic`() {
        val update = testDynamicUpdate(
            payload = DynamicPayload(
                content = DynamicContent.text("Demo content"),
                attachments = listOf(
                    ImageAttachment(
                        images = listOf(
                            ImageItem(testMedia("https://example.com/pic.png", MediaKind.IMAGE)),
                        ),
                    ),
                ),
            ),
        )
        val placeholderImage = loadTestResource("image", "avatar.jpg").readBytes()
        update.mediaReferences().forEach { reference ->
            DynamicImageCache.put(reference.media, placeholderImage)
        }

        val img = renderDynamicImage(
            update = update,
            config = DrawConfig(
                platform = PlatformDescriptor.of("bilibili", "Bilibili"),
            ),
        )

        File("${testOutput.absolutePath}/test1.png").writeBytes(img.encodeToData()!!.bytes)
    }
}
