package component

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.runBlocking
import loadTestImage
import loadTestResource
import org.jetbrains.skia.*
import org.jetbrains.skiko.toBitmap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testOutput
import top.colter.dynamic.draw.component.Author
import top.colter.dynamic.draw.component.Avatar
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Row
import top.colter.skiko.layout.View
import java.io.File


@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AuthorDrawTest {
    @BeforeAll
    fun init() {
        Dp.factor = 1f

        FontUtils.loadTypeface(loadTestResource("font", "HarmonyOS_Sans_SC_Medium.ttf").absolutePath)
        FontUtils.loadEmojiTypeface(loadTestResource("font", "NotoColorEmoji.ttf").absolutePath)
    }

    @Test
    fun `test draw avatar module`(): Unit = runBlocking {
        val face = loadTestImage("image", "avatar.jpg")
        val pendant = loadTestImage("image", "pendant.png")
        val official = loadTestImage("image", "ORGANIZATION_OFFICIAL_VERIFY.png")

        View(
            file = testOutput.resolve("avatar.png"),
            modifier = Modifier()
                .width(500.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {

            Row(Modifier().fillMaxHeight(), LayoutAlignment.CENTER) {
                Avatar(
                    face = face,
                    badge = official,
                    modifier = Modifier().height(150.dp).margin(15.dp)
                )
                Avatar(
                    face = face,
                    pendant = pendant,
                    badge = official,
                    modifier = Modifier().height(150.dp).margin(15.dp)
                )
            }



        }
    }

    @Test
    fun `test draw author module`(): Unit = runBlocking {
        val face = loadTestImage("image", "avatar.jpg")
        val pendant = loadTestImage("image", "pendant.png")
        val ornament1 = loadTestImage("image", "ornament1.png")
        val ornament2 = loadTestImage("image", "ornament2.png")
        val head = loadTestImage("image", "head.png")
        val official = loadTestImage("image", "ORGANIZATION_OFFICIAL_VERIFY.png")
        val qrCode = loadTestImage("output", "test1.png")
        val logo = loadTestImage("image", "bilibili-logo.png")

        View(
            file = testOutput.resolve("author.png"),
            modifier = Modifier()
                .width(1000.dp)
                .padding(50.dp)
                .background(gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195,191,255), Color.makeRGB(191,250,255))
                ))
        ) {

            Column(
                Modifier().fillMaxWidth().fillMaxHeight()
            ) {
                Author(
                    face = face,
                    head = head,
                    official = null,
                    name = "优妮-Uni_Kokoro",
                    time = "2023年03月14月 22:00:45",
                    ornament = logo,
                    badgeImage = official,
                    modifier = Modifier()
                )

                Author(
                    face = face,
                    pendant = pendant,
                    head = head,
                    official = null,
                    name = "优妮-Uni_Kokoro",
                    time = "2023年03月14月 22:00:45",
                    qrCode = qrCode,
                    badgeImage = official,
                    modifier = Modifier().margin(top = 100.dp)
                )
            }

        }
    }



    @Test
    fun `test qrcode`(): Unit = runBlocking {
        val pointColor = 0xFFFE65A6
        val bgColor = 0x00FFFFFF
        val qrCodeWriter = QRCodeWriter()

        val url = "https://t.bilibili.com/1017453842383503377"

        val bitMatrix = qrCodeWriter.encode(
            url, BarcodeFormat.QR_CODE, 500, 500,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            )
        )

        

        val config = MatrixToImageConfig(pointColor.toInt(), bgColor.toInt())

//        val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())


        val surface = Surface.makeRasterN32Premul(500, 500).apply {
            canvas.apply {

                val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())
                drawImage(img, 0f, 0f)


                drawRect(Rect.Companion.makeWH(108f, 108f).offset(196f, 196f), Paint().apply {
                    color = Color.WHITE
                })


                drawRRect(RRect.makeXYWH(208f, 208f, 84f, 84f, 10f), Paint().apply {
                    color = Color.makeRGB(254,101,166)
                })


//                drawCircle(125f, 125f, 35f, Paint().apply {
//                    color = Color.WHITE
//                })
//                drawCircle(125f, 125f, 30f, Paint().apply {
//                    color = Color.makeRGB(2, 181, 218)
//                })

//                val svg = SVGDOM(Data.makeFromBytes(loadTestResource("image", "bilibili.svg").readBytes()))
//                svg.setContainerSize(42f, 42f)
//                val ii = Surface.makeRasterN32Premul(42, 42).apply { svg.render(canvas) }.makeImageSnapshot()

                val logo = Image.makeFromEncoded(loadTestResource("image", "bilibili.png").readBytes())


                drawImageRect(logo, Rect.makeXYWH(208f, 208f, 84f, 84f))

//                drawImage(logo, 104f, 104f, Paint().apply {
////                    colorFilter = ColorFilter.makeBlend(Color.WHITE, BlendMode.SRC_ATOP)
//                    color = Color.WHITE
//
//                })


//                val svgimg = svg.makeImage(40, 40)
//                drawImage(svgimg, 105f, 105f, Paint().apply {
//                    colorFilter = ColorFilter.makeBlend(Color.WHITE, BlendMode.SRC_ATOP)
//                })

            }
        }

        File("${testOutput.absolutePath}/test1.png").writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)

    }



}