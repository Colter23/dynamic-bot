package top.colter.dynamic.enums

import kotlinx.serialization.Serializable


/**
 * # 订阅者类型
 *
 * [User] : 用户
 *
 * [Group] : 群组
 *
 * [Channel] : 频道
 *
 * [Other] : 其他
 *
 */
@Serializable
public enum class SubscriberType(public val value: Int) {
    User(1),
    Group(2),
    Channel(3),
    Other(9)
}