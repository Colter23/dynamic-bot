package component


import kotlinx.coroutines.runBlocking
import loadTestImage
import loadTestResource
import org.jetbrains.skia.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testOutput
import top.colter.dynamic.draw.component.*
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.View


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MediaDrawTest {
    @BeforeAll
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_Sans_SC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test draw media`(): Unit = runBlocking {
        val cover01 = loadTestImage("image", "cover01.jpg")

        View(
            file = testOutput.resolve("media.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {
            Media(
                cover = cover01,
                title = "《崩坏：星穹铁道》EP：「希望有羽毛和翅膀」",
                desc = " 希望在我的灵魂中筑巢栖息，唱着没有词的歌曲，似乎永远不会停息。《希望有羽毛和翅膀》 作曲 Composer：王可鑫 Eli.W (HOYO-MiX) 作词 Lyricist：Ruby Qu",
                badge = "视频",
                duration = "02:58",
                info = "1428.2万播放 4.3万弹幕",
            )
        }
    }

    @Test
    fun `test draw small media`(): Unit = runBlocking {
        val cover01 = loadTestImage("image", "cover01.jpg")

        View(
            file = testOutput.resolve("small_media.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {
            MediaSmall(
                cover = cover01,
                title = "《崩坏：星穹铁道》EP：「希望有羽毛和翅膀」",
                desc = " 希望在我的灵魂中筑巢栖息，唱着没有词的歌曲，似乎永远不会停息。《希望有羽毛和翅膀》 作曲 Composer：王可鑫 Eli.W (HOYO-MiX) 作词 Lyricist：Ruby Qu",
                badge = "视频",
                duration = "02:58",
            )
        }
    }

    @Test
    fun `test draw mini media`(): Unit = runBlocking {
        val cover01 = loadTestImage("image", "cover01.jpg")

        View(
            file = testOutput.resolve("mini_media.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {
            MediaMini(
                cover = cover01,
                title = "《崩坏：星穹铁道》EP：「希望有羽毛和翅膀」",
                desc = " 希望在我的灵魂中筑巢栖息，唱着没有词的歌曲，似乎永远不会停息。《希望有羽毛和翅膀》 作曲 Composer：王可鑫 Eli.W (HOYO-MiX) 作词 Lyricist：Ruby Qu",
                badge = "视频",
            )
        }
    }

}