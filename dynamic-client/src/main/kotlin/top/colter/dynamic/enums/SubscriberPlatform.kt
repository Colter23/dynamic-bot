package top.colter.dynamic.enums

import kotlinx.serialization.Serializable


/**
 * # 订阅者平台
 *
 * [QQ] : QQ [https://im.qq.com/](https://im.qq.com/)
 *
 * [QQChannel] : QQ频道 [https://im.qq.com/](https://im.qq.com/)
 *
 * [Discord] : Discord [https://discord.com/](https://discord.com/)
 *
 * [WebHook] : 推动到指定服务器(待定)
 *
 * [RSS] : 生成RSS
 *
 * [Other] : 其他目标
 *
 */
@Serializable
public enum class SubscriberPlatform(public val value: Int) {
    QQ(1),
    QQChannel(2),
    Discord(3),
    WebHook(7),
    RSS(8),
    Other(9)
}