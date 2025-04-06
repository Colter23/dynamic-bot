package top.colter.dynamic.enums

import kotlinx.serialization.Serializable


/**
 * # 动态内容类型 (暂时没用)
 *
 * [TEXT] : 纯文本
 *
 * [EMOJI] : 平台独有的Emoji
 *
 * [LINK] : 链接
 *
 */
@Serializable
public enum class DynamicContentType {
    TEXT,
    EMOJI,
    LINK
}