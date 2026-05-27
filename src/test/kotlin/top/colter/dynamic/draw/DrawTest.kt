
import io.ktor.util.Platform
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.skia.Color
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.core.data.PlatformDescriptor
import top.colter.dynamic.core.data.PlatformKind
import top.colter.dynamic.core.data.forEachLazyImageFields
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.image.DynamicImageCache
import top.colter.dynamic.draw.renderDynamicImage
import top.colter.dynamic.loadTestImage
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.loadTestText
import top.colter.dynamic.testOutput
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test


class DrawTest {

    //private val client = BiliClient()

    @BeforeTest
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)

    }

    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        allowStructuredMapKeys = true
    }

    @Test
    fun `test dynamic`(): Unit = runBlocking {
        //val dynamic = client.getDynamicDetail(683357961644408871)

        val dy = loadTestText("json", "dynamic.json")
        val dynamic: Dynamic = json.decodeFromJsonElement(json.parseToJsonElement(dy))
        val placeholderImage = loadTestResource("image", "avatar.jpg").readBytes()
        forEachLazyImageFields(dynamic) {
            if (uri.isNotBlank()) {
                DynamicImageCache.put(this, placeholderImage)
            }
        }

        val img = renderDynamicImage(
            dynamic = dynamic,
            config = DrawConfig(
                platform = PlatformDescriptor("", "", "", "",PlatformKind.PUBLISHER),
            )
        )

        File("${testOutput.absolutePath}/test1.png").writeBytes(img.encodeToData()!!.bytes)
        //renderDynamicImage(dynamic)
    }

    @Test
    fun `test dynamic style1`(): Unit = runBlocking {
        val face = loadTestImage("image", "avatar.jpg")
        val pendant = loadTestImage("image", "pendant.png")
        val ornament1 = loadTestImage("image", "ornament1.png")
        val ornament2 = loadTestImage("image", "ornament2.png")

        val cover = loadTestImage("image", "bg1.jpg")


    }
    
}
