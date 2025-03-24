import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import top.colter.dynamic.LazyImage
import top.colter.dynamic.data.*
import top.colter.dynamic.draw.drawDynamicContent
import top.colter.dynamic.draw.drawDynamicMediaPic
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.View
import javax.swing.text.View

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DrawDynamicContentTest {
    @BeforeAll
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_Sans_SC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test drawDynamicContent()`(): Unit = runBlocking {

        val content = DynamicContent("", listOf(
            DynamicContentNodeLink("#崩坏星穹铁道# #再见匹诺康尼#"),
            DynamicContentNodeText("  「角色前瞻 | 流萤」\n\n" +
                    "为战而生，为生而战！\n" +
                    "开拓者好呀~今天为大家带来「流萤（毁灭•火）」的角色前瞻！ \n" +
                    "———————————————————\n" +
                    "▌官方微博：崩坏星穹铁道 \n" +
                    "▌官方公众号：崩坏星穹铁道\n"),
            DynamicContentNodeEmoji("aa", image = LazyImage("[阿库娅_不关我事].png"))
        ))

        val i = (content.contentNodes.last() as DynamicContentNodeEmoji).image
        i.image = loadTestResource("emoji", i.url).readBytes()

        View(
            file = testOutput.resolve("content.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                )
                )
        ) {
            drawDynamicContent(content)
        }
    }
}