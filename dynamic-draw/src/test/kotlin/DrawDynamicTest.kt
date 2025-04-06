import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import top.colter.dynamic.LazyImage
import top.colter.dynamic.data.*
import top.colter.dynamic.draw.DrawDynamic
import top.colter.dynamic.draw.drawDynamicContent
import top.colter.dynamic.encode
import top.colter.dynamic.enums.PublisherPlatform
import top.colter.dynamic.forEachLazyImageFields
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.View

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DrawDynamicTest {
    @BeforeAll
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_Sans_SC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test drawDynamic()`(): Unit = runBlocking {

        val content = DynamicContent("", listOf(
            DynamicContentNodeLink("#崩坏星穹铁道# #再见匹诺康尼#"),
            DynamicContentNodeText("  「角色前瞻 | 流萤」\n\n" +
                    "为战而生，为生而战！\n" +
                    "开拓者好呀~今天为大家带来「流萤（毁灭•火）」的角色前瞻！ \n" +
                    "———————————————————\n" +
                    "▌官方微博：崩坏星穹铁道 \n" +
                    "▌官方公众号：崩坏星穹铁道"),
//            DynamicContentNodeEmoji("aa", image = LazyImage("[阿库娅_不关我事].png")),
            DynamicContentNodeLink("#崩坏星穹铁道# #再见匹诺康尼#"),
        ))
//        val i = (content.contentNodes[2] as DynamicContentNodeEmoji).image
//        i.image = loadTestResource("emoji", i.url).readBytes()

        val pics: List<DynamicMediaPic> = listOf(
            DynamicMediaPic(LazyImage("b410195c3be40f6195dcd90a02b913311340190821.png"), 1080, 1920),
            DynamicMediaPic(LazyImage("9c15933d6d19fa9ad0da8aba1c943f1f1340190821.gif"), 550, 550, badge = "动图"),
            DynamicMediaPic(LazyImage("ae5aea39e3ddea8a2ad7108dff51331b1340190821.gif"), 550, 947, badge = "动图"),
            DynamicMediaPic(LazyImage("22a21643280b17df3325ce95f650f3651340190821.jpg"), 900, 5296, badge = "长图"),
            DynamicMediaPic(LazyImage("b410195c3be40f6195dcd90a02b913311340190821.png"), 1080, 1920),
            DynamicMediaPic(LazyImage("9c15933d6d19fa9ad0da8aba1c943f1f1340190821.gif"), 550, 550, badge = "动图"),
            DynamicMediaPic(LazyImage("ae5aea39e3ddea8a2ad7108dff51331b1340190821.gif"), 550, 947, badge = "动图"),
            DynamicMediaPic(LazyImage("22a21643280b17df3325ce95f650f3651340190821.jpg"), 900, 5296, badge = "长图"),
            DynamicMediaPic(LazyImage("22a21643280b17df3325ce95f650f3651340190821.jpg"), 900, 5296, badge = "长图"),
        )

//        pics.forEach {
//            it.pic.image = loadTestResource("image", it.pic.url).readBytes()
//        }


        val video = DynamicMediaVideo(
            "",
            "《崩坏：星穹铁道》EP：「希望有羽毛和翅膀」",
            "希望在我的灵魂中筑巢栖息，唱着没有词的歌曲，似乎永远不会停息。《希望有羽毛和翅膀》 作曲 Composer：王可鑫 Eli.W (HOYO-MiX) 作词 Lyricist：Ruby Qu",
            LazyImage("cover01.jpg"),
            duration = "02:58",
            badge = "视频",
            stats = DynamicMediaVideoStats("1428.2万", "4.3万", "300"),
            link = "http",
        )

        val media = DynamicMedia(
//            pics = pics,
            video = video,
        )

        val dynamic = Dynamic(
            platform = PublisherPlatform.BiliBili,
            dynamicId = "",
            publisher = Publisher(
                platform = PublisherPlatform.BiliBili,
                userId = "",
                name = "Colter_null",
                face = LazyImage("avatar.jpg"),
                pendant = LazyImage("pendant.png"),
                head = LazyImage("head.png"),
                official = null
            ),
            time = 1743911606,
            title = "测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试测试",
            content = content,
            media = media,
        )


        forEachLazyImageFields(dynamic) {
            this.image = loadTestResource("image", this.url).readBytes()
        }
//        dynamic.publisher.face.image = loadTestResource("image", dynamic.publisher.face.url).readBytes()
//        dynamic.publisher.pendant?.image = loadTestResource("image", dynamic.publisher.pendant?.url!!).readBytes()
//        dynamic.publisher.head?.image = loadTestResource("image", dynamic.publisher.head?.url!!).readBytes()

        val image = DrawDynamic(dynamic)
        testOutput.resolve("dynamic.png").writeBytes(image.encodeToData()!!.bytes)

        testOutput.resolve("dynamic.json").writeText(dynamic.encode())

    }
}