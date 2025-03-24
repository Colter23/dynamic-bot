package top.colter.dynamic.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.data.DynamicContent
import top.colter.dynamic.data.DynamicContentNodeEmoji
import top.colter.dynamic.data.DynamicContentNodeLink
import top.colter.dynamic.data.DynamicContentNodeText
import top.colter.skiko.*
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.RichText


fun Layout.drawDynamicContent(content: DynamicContent) {
    val style = TextStyle().setColor(Color.BLACK).setFontSize(30.px).setFontFamily(FontUtils.defaultFont!!.familyName)
    val linkStyle = TextStyle().setColor(Color.makeRGB(23, 139, 207)).setFontSize(30.px).setFontFamily(FontUtils.defaultFont!!.familyName)
    val paragraph = RichParagraphBuilder(style)

    content.contentNodes.forEach {
        when (it) {
            is DynamicContentNodeText -> paragraph.addText(it.text)
            is DynamicContentNodeLink -> paragraph.addText(it.text, linkStyle)
            is DynamicContentNodeEmoji -> paragraph.addEmoji(it.text, it.image.image?.makeImage()!!)
        }
    }

    RichText(
        paragraph = paragraph.build(),
        modifier = Modifier().margin(vertical = 20.dp).fillMaxWidth()
    )
}