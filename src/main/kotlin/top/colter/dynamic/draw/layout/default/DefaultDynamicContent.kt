package top.colter.dynamic.draw.layout.default

import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeMention
import top.colter.dynamic.core.data.DynamicContentNodeTag
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.draw.DrawConfig
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.RichText
import top.colter.skiko.margin
import top.colter.skiko.px
import top.colter.skiko.withDefaultFontFamily
import top.colter.skiko.data.RichParagraphBuilder

internal fun Layout.drawDynamicContent(
    content: DynamicContent,
    config: DrawConfig,
    bottomSpacing: Dp = 0.dp,
) {
    if (content.nodes.isEmpty()) return

    val style = TextStyle()
        .setColor(config.theme.textColor)
        .setFontSize(30.px)
        .withDefaultFontFamily(config.fontRegistry)
    val linkStyle = TextStyle()
        .setColor(config.theme.linkColor)
        .setFontSize(30.px)
        .withDefaultFontFamily(config.fontRegistry)
    val paragraph = RichParagraphBuilder(style)

    content.nodes.forEach {
        when (it) {
            is DynamicContentNodeText -> paragraph.addText(it.text, style)
            is DynamicContentNodeLink -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeMention -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeTag -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeEmoji -> {
                val image = it.image
                if (image == null) {
                    paragraph.addText(it.text, style)
                } else {
                    paragraph.addEmoji(it.text, config.image(image), linkStyle)
                }
            }
        }
    }

    RichText(
        paragraph = paragraph.build(),
        modifier = Modifier().margin(bottom = bottomSpacing).fillMaxWidth(),
    )
}
