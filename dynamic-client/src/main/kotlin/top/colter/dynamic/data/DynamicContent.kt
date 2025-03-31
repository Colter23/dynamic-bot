package top.colter.dynamic.data

import top.colter.dynamic.ImageType
import top.colter.dynamic.ImgType
import top.colter.dynamic.LazyImage


/**
 * # 动态内容
 *
 * @param text 格式化后文本
 * @param contentNodes 内容节点 [DynamicContentNode]
 *
 */
public data class DynamicContent(
    val text: String,
    val contentNodes: List<DynamicContentNode>
)


/**
 * # 内容节点
 *
 * [DynamicContentNodeText] 纯文本节点
 *
 * [DynamicContentNodeEmoji] Emoji节点
 *
 * [DynamicContentNodeLink] 链接节点
 *
 */
public interface DynamicContentNode {
    public val text: String
    public val style: DynamicContentStyle?
}


/**
 * # 文本内容节点
 *
 * @param text 文本
 * @param style 样式 [DynamicContentStyle]
 */
public data class DynamicContentNodeText(
    override val text: String,
    override val style: DynamicContentStyle? = null
) : DynamicContentNode

/**
 * # Emoji内容节点
 *
 * @param text Emoji文本
 * @param style 样式 [DynamicContentStyle]
 * @param image Emoji图片
 */
public data class DynamicContentNodeEmoji(
    override val text: String,
    override val style: DynamicContentStyle? = null,
    @ImgType(ImageType.EMOJI)
    val image: LazyImage
) : DynamicContentNode

/**
 * # 链接内容节点
 *
 * @param text 链接文本
 * @param style 样式 [DynamicContentStyle]
 * @param icon 链接图标
 * @param url 链接
 */
public data class DynamicContentNodeLink(
    override val text: String,
    override val style: DynamicContentStyle? = null,
    @ImgType(ImageType.OTHER)
    val icon: LazyImage? = null,
    val url: String? = null
) : DynamicContentNode


/**
 * # 动态内容样式(暂定)
 *
 * @param size 字体大小 [DynamicContentSize]
 * @param sizeNum 指定字体大小，同时设定会覆盖 [size]
 * @param color HEX颜色 (#FFFFFF)
 * @param fontFamily 字体名
 * @param isBold 是否加粗
 * @param isItalic 是否斜体
 */
//TODO 动态内容样式
public data class DynamicContentStyle(
    val size: DynamicContentSize? = null,
    val sizeNum: Int? = null,
    val color: String? = null,
    val fontFamily: String? = null,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
) {
    public enum class DynamicContentSize {
        SMALL,
        NORMAL,
        LARGE
    }
}