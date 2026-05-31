package top.colter.dynamic.draw.resource

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

internal fun qrCode(url: String, pointColor: Int = 0xFFFE65A6.toInt(), bgColor: Int = 0x00FFFFFF): Image {
    val size = 500
    val matrix = QRCodeWriter().encode(
        url,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 2,
        ),
    )

    return Surface.makeRasterN32Premul(size, size).apply {
        val pointPaint = Paint().apply {
            color = pointColor
            isAntiAlias = false
        }

        canvas.drawRect(
            Rect.makeXYWH(0f, 0f, size.toFloat(), size.toFloat()),
            Paint().apply {
                color = bgColor
                isAntiAlias = false
            },
        )

        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                while (x < matrix.width && !matrix[x, y]) x++
                val start = x
                while (x < matrix.width && matrix[x, y]) x++
                if (x > start) {
                    canvas.drawRect(
                        Rect.makeXYWH(start.toFloat(), y.toFloat(), (x - start).toFloat(), 1f),
                        pointPaint,
                    )
                }
            }
        }
    }.makeImageSnapshot()
}
