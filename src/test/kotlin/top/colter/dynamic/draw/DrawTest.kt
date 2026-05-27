
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
import top.colter.dynamic.draw.DrawDynamic
import top.colter.dynamic.draw.DynamicImageCache
import top.colter.dynamic.draw.component.Author
import top.colter.dynamic.loadTestImage
import top.colter.dynamic.loadTestResource
import top.colter.dynamic.loadTestText
import top.colter.dynamic.testOutput
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.BeforeTest
import kotlin.test.Test


class DrawTest {

    //private val client = BiliClient()

    @BeforeTest
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_SansSC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)

//        try {
//            val cookies = loadTestText(fileName = "cookie.json").decode<List<EditCookie>>().map { it.toCookie() }
//            client.storage.initialize(cookies)
//        }catch (e: FileNotFoundException) {
//            println("未找到cookie文件，将无法使用部分api")
//        }
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

        val img = DrawDynamic(
            dynamic = dynamic,
            config = DrawConfig(
                platform = PlatformDescriptor("", "", "", "",PlatformKind.PUBLISHER),
            )
        )

        File("${testOutput.absolutePath}/test1.png").writeBytes(img.encodeToData()!!.bytes)
        //DrawDynamic(dynamic)
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
