package top.colter.dynamic.draw

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.skia.*
import org.jetbrains.skiko.toBitmap
import top.colter.dynamic.draw.tools.loadResourceBytes
import kotlin.math.round
import kotlin.math.roundToInt


val pointColor = 0xFF000000
val bgColor = 0xFFFFFFFF
val qrCodeWriter = QRCodeWriter()

fun qrCode(url: String): Image {
    val bitMatrix = qrCodeWriter.encode(
        url, BarcodeFormat.QR_CODE, 500, 500,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
        )
    )

    val matrix = BitMatrix(bitMatrix.width, bitMatrix.height)

    val x = bitMatrix.topLeftOnBit[0]
    val y = bitMatrix.topLeftOnBit[1] + 16
    val row = bitMatrix.getRow(y,null)

    var bitSize = 16

    for (c in x until row.size) {
        if (!row[c]) {
            bitSize = c-x
            break
        }
    }
    val rectangle = bitMatrix.enclosingRectangle

    val bitNum = (rectangle[2]) / bitSize
    println((bitNum / 4f).toInt())
    val logoSize = (bitNum / 4f).toInt() * bitSize

    val logoX = x + rectangle[2] / 2 - logoSize / 2



    for (x in logoX until logoX + logoSize) {
        for (y in logoX until logoX + logoSize) {
            if (bitMatrix.get(x, y)) {
                matrix.set(x, y)
            }
        }
    }
    println(bitMatrix)
//    matrix.setRegion(0, 0, bitMatrix.width/2, bitMatrix.height/2)

    val config = MatrixToImageConfig(pointColor.toInt(), bgColor.toInt())

    bitMatrix.xor(matrix)

    val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())


    return Surface.makeRasterN32Premul(500, 500).apply {
        canvas.apply {

//            val img = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())
            drawImage(img, 0f, 0f)

//            drawRect(Rect.Companion.makeWH(108f, 108f).offset(196f, 196f), Paint().apply {
//                color = Color.WHITE
//            })
//
//            drawRRect(RRect.makeXYWH(208f, 208f, 84f, 84f, 10f), Paint().apply {
//                color = Color.makeRGB(254,101,166)
//            })


            val logo = Image.makeFromEncoded(loadResourceBytes("image/bilibili.png")!!)
            drawImageRect(logo, Rect.makeXYWH(208f, 208f, 84f, 84f))

        }
    }.makeImageSnapshot()

}