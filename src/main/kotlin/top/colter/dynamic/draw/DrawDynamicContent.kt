package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.core.data.*
import top.colter.dynamic.draw.tools.makeRGB
import top.colter.skiko.*
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.RichText


fun Layout.drawDynamicContent(content: DynamicContent, config: DrawConfig) {
    if (content.contentNodes.isEmpty()) return

    val style = TextStyle()
        .setColor(config.theme.textColor)
        .setFontSize(30.px)
        .withDefaultFontFamily(config.fontRegistry)
    val linkStyle = TextStyle()
        .setColor(config.theme.linkColor)
        .setFontSize(30.px)
        .withDefaultFontFamily(config.fontRegistry)
    val paragraph = RichParagraphBuilder(style)

    content.contentNodes.forEach {
        when (it) {
            is DynamicContentNodeText -> paragraph.addText(it.text, it.style?.toTextStyle(style, config) ?: style)
            is DynamicContentNodeLink -> paragraph.addText(it.text, it.style?.toTextStyle(linkStyle, config) ?: linkStyle)
            is DynamicContentNodeEmoji -> paragraph.addEmoji(
                it.text,
                config.image(it.image),
                it.style?.toTextStyle(linkStyle, config) ?: linkStyle,
            )
        }
    }

    RichText(
        paragraph = paragraph.build(),
        modifier = Modifier().margin(bottom = 20.dp).fillMaxWidth()
    )

}

fun DynamicContentStyle.toTextStyle(textStyle: TextStyle = TextStyle(), config: DrawConfig): TextStyle {
    val result = textStyle.copyStyle()

    if (sizeNum != null) {
        result.fontSize = sizeNum!!.px
    } else if (size != null) {
        result.fontSize = when (size!!) {
            DynamicContentStyle.DynamicContentSize.SMALL -> 25.px
            DynamicContentStyle.DynamicContentSize.NORMAL -> 30.px
            DynamicContentStyle.DynamicContentSize.LARGE -> 35.px
        }
    }

    if (color != null) {
        result.color = Color.makeRGB(color!!)
    }

    if (fontFamily != null && config.fontRegistry.matchFamily(fontFamily!!).count() != 0) {
        result.setFontFamily(fontFamily!!)
    }

    if (isBold && isItalic) result.fontStyle = FontStyle.BOLD_ITALIC
    else if (isBold) result.fontStyle = FontStyle.BOLD
    else if (isItalic) result.fontStyle = FontStyle.ITALIC

    return result
}
