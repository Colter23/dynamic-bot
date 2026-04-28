package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.tools.formatTime
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View


fun DrawDynamic(dynamic: Dynamic, config: DrawConfig): Image {
    return View(
        modifier = Modifier()
            .width(1000.dp)
            .padding(30.dp)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    listOf(Color.makeRGB(195, 192, 255), Color.makeRGB(191, 250, 255))
                )
            )
    ) {
        DynamicView(dynamic, config)
    }
}


fun Layout.DynamicView(dynamic: Dynamic, config: DrawConfig) {
    Column(modifier = Modifier().fillMaxWidth()) {

        drawPublisher(dynamic.publisher, dynamic.time.formatTime, dynamic.link, config)

        Column(modifier = Modifier()
            .fillMaxWidth()
            .margin(top = 30.dp, bottom = 30.dp)
            .padding(30.dp)
            .background(Color.WHITE.withAlpha(0.6f))
            .border(3.dp, 15.dp)
        ) {

            dynamic.title?.let { title ->
                Text(
                    text = title,
                    fontSize = 36.dp,
                    fontStyle = FontStyle.BOLD,
                    maxLinesCount = 2,
                    modifier = Modifier().margin(bottom = 20.dp)
                )
            }
            dynamic.content?.let { content -> drawDynamicContent(content) }

            dynamic.media?.let { media -> drawDynamicMedia(media) }

            dynamic.origin?.let {
                putEnv("FORWARD", true)
                DynamicView(it, config)
                removeEnv("FORWARD")
            }

        }


    }
}

//Column(modifier = Modifier().fillMaxWidth()) {
//
//    drawPublisher(dynamic.publisher, dynamic.time.formatTime, dynamic.link, config)
//
//    Column(modifier = Modifier()
//        .fillMaxWidth()
//        .margin(top = 30.dp)
//        .padding(30.dp)
//        .background(Color.WHITE.withAlpha(0.6f))
//        .border(3.dp, 15.dp)
//    ) {



//Column(
//modifier = Modifier()
//.fillMaxWidth()
//.padding(1.dp)
//.background(Color.WHITE.withAlpha(0.6f))
//.border(3.dp, 15.dp)
//) {
//
//    drawPublisher(dynamic.publisher, dynamic.time.formatTime, dynamic.link, config)
//
//    Column(
//        modifier = Modifier()
//            .fillMaxWidth()
////                .margin(top = 30.dp)
//            .padding(30.dp)
////                .background(Color.WHITE.withAlpha(0.6f))
////                .border(3.dp, 15.dp)
//    ) {
