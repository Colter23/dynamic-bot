package top.colter.dynamic.draw.layout.default

import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.component.Author
import top.colter.dynamic.draw.layout.default.component.AuthorSmall
import top.colter.dynamic.draw.resource.loadResourceImage
import top.colter.dynamic.draw.resource.qrCode
import top.colter.skiko.*
import top.colter.skiko.layout.Layout


internal fun Layout.drawPublisher(
    publisher: PublisherInfo,
    time: String,
    link: String,
    config: DrawConfig,
    mode: DynamicRenderMode,
) {

    val officialImage = publisher.official?.let { loadResourceImage(name = it) }

    if (mode == DynamicRenderMode.FORWARD) {
        AuthorSmall(
            face = config.image(publisher.avatar),
            name = publisher.name,
            time = time,
            badge = officialImage,
            accentColor = config.theme.primaryColor,
            mutedColor = config.theme.mutedTextColor,
            modifier = Modifier().fillMaxWidth().height(50.dp)//.margin(horizontal = 5.dp, vertical = 10.dp) // .background(Color.RED)
        )
    } else {

        val ornamentImage = when (config.settings.ornament) {
            DrawOrnament.LOGO -> platformLogo(config)
            DrawOrnament.QRCODE -> qrCode(link, config.theme.primaryColor.withAlpha(1f))
            DrawOrnament.NONE -> null
        }

        Author(
            face = config.image(publisher.avatar),
            pendant = publisher.pendant?.let { config.image(it) },
            head = publisher.banner?.let { config.image(it) },
            ornament = ornamentImage,
            badge = officialImage,
            name = publisher.name,
            time = time,
            modifier = Modifier().fillMaxWidth().height(100.dp)// .background(Color.RED)
//                modifier = Modifier().fillMaxWidth().height(100.dp).margin(top = 10.dp, right = (-15).dp, bottom = 30.dp, left = (-15).dp) // .background(Color.RED)
        )
    }

}

private fun platformLogo(config: DrawConfig) =
    loadResourceImage(name = "${config.platform.id.value.uppercase()}_A.png")
        ?: loadResourceImage(name = "${config.platform.id.value.uppercase()}_LOGO.png")
        ?: config.platform.icon
            ?.let { config.image(it.copy(kind = MediaKind.IMAGE)) }
        ?: config.platform.homepageUri
            ?.takeIf { it.isNotBlank() }
            ?.let { config.image(MediaRef(uri = it, kind = MediaKind.IMAGE)) }
