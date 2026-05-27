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

    val style = TextStyle().setColor(Color.BLACK).setFontSize(30.px).withDefaultFontFamily()
    val linkStyle = TextStyle().setColor(Color.makeRGB(23, 139, 207)).setFontSize(30.px).withDefaultFontFamily()
    val paragraph = RichParagraphBuilder(style)

    content.contentNodes.forEach {
        when (it) {
            is DynamicContentNodeText -> paragraph.addText(it.text, it.style?.toTextStyle(style))
            is DynamicContentNodeLink -> paragraph.addText(it.text, it.style?.toTextStyle(linkStyle) ?: linkStyle)
            is DynamicContentNodeEmoji -> paragraph.addEmoji(
                it.text,
                config.image(it.image),
                it.style?.toTextStyle(linkStyle) ?: linkStyle,
            )
        }
    }

    RichText(
        paragraph = paragraph.build(),
        modifier = Modifier().margin(bottom = 20.dp).fillMaxWidth()
    )

}

private fun TextStyle.withDefaultFontFamily(): TextStyle {
    FontUtils.defaultFont?.familyName?.let { setFontFamily(it) }
    return this
}

fun DynamicContentStyle.toTextStyle(textStyle: TextStyle = TextStyle()) = textStyle.also {

    if (sizeNum != null) {
        it.fontSize = sizeNum!!.px
    } else if (size != null) {
        it.fontSize = when (size!!) {
            DynamicContentStyle.DynamicContentSize.SMALL -> 25.px
            DynamicContentStyle.DynamicContentSize.NORMAL -> 30.px
            DynamicContentStyle.DynamicContentSize.LARGE -> 35.px
        }
    }

    if (color != null) {
        it.color = Color.makeRGB(color!!)
    }

    if (fontFamily != null && FontUtils.matchFamily(fontFamily!!).count() != 0) {
        it.setFontFamily(fontFamily!!)
    }

    if (isBold && isItalic) it.fontStyle = FontStyle.BOLD_ITALIC
    if (isBold) it.fontStyle = FontStyle.BOLD
    if (isItalic) it.fontStyle = FontStyle.ITALIC

}
