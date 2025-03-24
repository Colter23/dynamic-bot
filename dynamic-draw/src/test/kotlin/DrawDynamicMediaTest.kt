import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import top.colter.dynamic.LazyImage
import top.colter.dynamic.data.DynamicMediaPic
import top.colter.dynamic.draw.drawDynamicMediaPic
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.View


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DrawDynamicMediaTest {
    @BeforeAll
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_Sans_SC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test drawDynamicMediaPic()`(): Unit = runBlocking {
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

        pics.forEach { 
            it.pic.image = loadTestResource("image", it.pic.url).readBytes()
        }

        View(
            file = testOutput.resolve("dynamic.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {
            drawDynamicMediaPic(pics)
        }
    }
}