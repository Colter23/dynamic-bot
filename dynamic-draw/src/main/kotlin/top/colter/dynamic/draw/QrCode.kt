package top.colter.dynamic.draw

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.skia.*
import org.jetbrains.skiko.toBitmap
import top.colter.dynamic.draw.tools.loadResourceBytes


val pointColor = 0xFF000000
val bgColor = 0xFFFFFFFF
val qrCodeWriter = QRCodeWriter()

fun qrCode(url: String) {
    val bitMatrix = qrCodeWriter.encode(
        url, BarcodeFormat.QR_CODE, 250, 250,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
        )
    )

    val config = MatrixToImageConfig(pointColor.toInt(), bgColor.toInt())

    val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())


    Surface.makeRasterN32Premul(500, 500).apply {
        canvas.apply {

            val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())
            drawImage(img, 0f, 0f)

            drawRect(Rect.Companion.makeWH(108f, 108f).offset(196f, 196f), Paint().apply {
                color = Color.WHITE
            })

            drawRRect(RRect.makeXYWH(208f, 208f, 84f, 84f, 10f), Paint().apply {
                color = Color.makeRGB(254,101,166)
            })


            val logo = Image.makeFromEncoded(loadResourceBytes("image/bilibili.png")!!)
            drawImageRect(logo, Rect.makeXYWH(208f, 208f, 84f, 84f))

        }
    }.makeImageSnapshot()

}