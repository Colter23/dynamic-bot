package top.colter.dynamic.enums

/**
 * # 发布者平台
 *
 * [BiliBili] : 哔哩哔哩 [https://www.bilibili.com/](https://www.bilibili.com/)
 *
 * [Weibo] : 微博 [https://weibo.com/](https://weibo.com/)
 *
 * [X] : 原Twitter(待定) [https://x.com/](https://x.com/)
 *
 * [Github] : Github(待定) [https://github.com/](https://github.com/)
 *
 * [RSS] : RSS源
 *
 * [Other] : 其他来源
 *
 */
public enum class PublisherPlatform(public val value: Int) {
    BiliBili(1),
    Weibo(2),
    X(3),
    Github(4),
    RSS(8),
    Other(9)
}