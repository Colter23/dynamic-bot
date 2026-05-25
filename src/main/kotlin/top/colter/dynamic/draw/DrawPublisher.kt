package top.colter.dynamic.draw

import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.draw.component.Author
import top.colter.dynamic.draw.component.AuthorSmall
import top.colter.dynamic.draw.tools.loadResourceImage
import top.colter.skiko.*
import top.colter.skiko.layout.Layout


fun Layout.drawPublisher(publisher: Publisher, time: String, link: String, config: DrawConfig) {

    val officialImage = publisher.official?.let { loadResourceImage(name = it) }

    if (containsEnv("FORWARD")) {
        AuthorSmall(
            face = config.image(publisher.face),
            name = publisher.name,
            time = time,
            badge = officialImage,
            modifier = Modifier().fillMaxWidth().height(50.dp)//.margin(horizontal = 5.dp, vertical = 10.dp) // .background(Color.RED)
        )
    } else {

        val ornamentImage = when (config.ornament) {
            "LOGO" -> loadResourceImage(name = "BILIBILI_A.png")
            "QRCODE" -> qrCode(link, config.themeColor.withAlpha(1f))
            else -> null
        }

        Author(
            face = config.image(publisher.face),
            pendant = publisher.pendant?.let { config.image(it) },
            head = publisher.header?.let { config.image(it) },
            ornament = ornamentImage,
            badge = officialImage,
            name = publisher.name,
            time = time,
            modifier = Modifier().fillMaxWidth().height(100.dp)// .background(Color.RED)
//                modifier = Modifier().fillMaxWidth().height(100.dp).margin(top = 10.dp, right = (-15).dp, bottom = 30.dp, left = (-15).dp) // .background(Color.RED)
        )
    }

}
