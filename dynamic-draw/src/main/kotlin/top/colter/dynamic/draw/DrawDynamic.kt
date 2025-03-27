package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.data.Dynamic
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.View


suspend fun DrawDynamic(dynamic: Dynamic): Image? {
    return View(
        modifier = Modifier()
            .width(1000.dp)
            .padding(30.dp)
            .background(gradient = Gradient(LayoutAlignment.LEFT_TOP, LayoutAlignment.RIGHT_BOTTOM, listOf(Color.RED, Color.GREEN)))
    ) {
        DynamicView(dynamic)
    }
}


fun Layout.DynamicView(dynamic: Dynamic) {

    Column(modifier = Modifier()
        .fillMaxWidth()
        .padding(20.dp)
        .background(Color.WHITE.withAlpha(0.6f))
        .border(3.dp, 15.dp)
    ) {

        drawPublisher(dynamic.publisher, dynamic.time.toString())

        dynamic.content?.let { content -> drawDynamicContent(content) }

        dynamic.media?.let { media -> drawDynamicMedia(media) }

        dynamic.origin?.let {
            putEnv("forward", true)
            DynamicView(it)
            removeEnv("forward")
        }

    }

}