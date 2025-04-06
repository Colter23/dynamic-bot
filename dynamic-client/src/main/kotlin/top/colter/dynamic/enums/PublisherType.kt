package top.colter.dynamic.enums

import kotlinx.serialization.Serializable


/**
 * # 发布者类型
 *
 * [User] : 用户
 *
 * [Other] : 其他
 *
 */
@Serializable
public enum class PublisherType(public val value: Int) {
    User(1),
    Other(9)
}