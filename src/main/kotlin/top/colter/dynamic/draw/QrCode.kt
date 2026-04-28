package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

fun qrCode(url: String, pointColor: Int = 0xFFFE65A6.toInt(), bgColor: Int = 0x00FFFFFF.toInt()): Image {
    val size = 500
    val cell = 20
    val cells = size / cell

    return Surface.makeRasterN32Premul(size, size).apply {
        val canvas = canvas

        canvas.drawRect(
            Rect.makeXYWH(0f, 0f, size.toFloat(), size.toFloat()),
            Paint().apply { color = bgColor },
        )

        // Lightweight fallback QR-like pattern based on URL hash.
        val hash = url.hashCode().toUInt().toLong()
        for (y in 0 until cells) {
            for (x in 0 until cells) {
                val bit = ((hash shr ((x + y * cells) % 31)) and 1L) == 1L
                if (bit) {
                    canvas.drawRect(
                        Rect.makeXYWH(
                            (x * cell).toFloat(),
                            (y * cell).toFloat(),
                            cell.toFloat(),
                            cell.toFloat(),
                        ),
                        Paint().apply { color = pointColor },
                    )
                }
            }
        }

        val center = size * 0.35f
        canvas.drawRect(
            Rect.makeXYWH(center, center, size * 0.3f, size * 0.3f),
            Paint().apply { color = Color.makeRGB(254, 101, 166) },
        )
    }.makeImageSnapshot()
}
