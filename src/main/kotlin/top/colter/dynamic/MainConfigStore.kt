package top.colter.dynamic

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.draw.DrawLayoutRegistry
import top.colter.dynamic.draw.DrawThemeFactory

public class MainConfigStore(
    private val configService: ConfigService = YamlConfigService(),
) {
    @Volatile
    private var currentConfig: MainDynamicConfig? = null

    public fun loadOrCreate(adminTokenProvider: () -> String): MainDynamicConfig {
        val loaded = configService.loadOrCreate(MainDynamicConfig.CONFIG_ID, MainDynamicConfig::class) {
            MainDynamicConfig()
        }
        val withToken = if (loaded.webAdmin.enabled && loaded.webAdmin.token.isBlank()) {
            loaded.copy(webAdmin = loaded.webAdmin.copy(token = adminTokenProvider()))
        } else {
            loaded
        }
        if (withToken != loaded) {
            configService.save(MainDynamicConfig.CONFIG_ID, withToken)
        }
        currentConfig = withToken
        return withToken
    }

    public fun current(): MainDynamicConfig {
        return currentConfig ?: loadOrCreate { "" }
    }

    public fun save(next: MainDynamicConfig): ConfigApplyResult {
        val previous = current()
        MainConfigForms.validate(next)
        val changed = previous != next
        if (!changed) {
            return ConfigApplyResult(changed = false, message = "主配置未变化")
        }

        configService.save(MainDynamicConfig.CONFIG_ID, next)
        currentConfig = next

        val restartTargets = MainConfigForms.restartTargets(previous, next)
        return ConfigApplyResult(
            changed = true,
            restartRequired = restartTargets.isNotEmpty(),
            restartTargets = restartTargets,
            message = if (restartTargets.isEmpty()) {
                "主配置已保存并生效"
            } else {
                "主配置已保存，${restartTargets.joinToString("、")} 需要重启"
            },
        )
    }

    public fun formSpec(): ConfigFormSpec = MainConfigForms.formSpec
}

public object MainConfigForms {
    public val formSpec: ConfigFormSpec
        get() = ConfigFormSpec(
            title = "主配置",
            description = "主项目的模板、命令、订阅、链接解析、图片缓存、绘图和 Web 后台设置。",
            fields = listOf(
                ConfigFieldSpec(
                    path = "templates.dynamic",
                    label = "动态模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "支持 {draw} {name} {uid} {did} {time} {content} {images} {link} {links}；\\n 换行，\\r 分割为多条消息。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveStarted",
                    label = "开播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "支持 {draw} {name} {uid} {rid} {time} {title} {area} {cover} {link}；\\n 换行，\\r 分割为多条消息。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveEnded",
                    label = "下播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "支持 {name} {uid} {rid} {title} {area} {startTime} {endTime} {duration} {link}；\\n 换行。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.prefix",
                    label = "命令前缀",
                    type = ConfigFieldType.TEXT,
                    section = "命令",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.permissions",
                    label = "权限规则",
                    type = ConfigFieldType.JSON,
                    section = "命令",
                    description = "未命中规则时为普通用户；命中多条规则时取最高权限。平台、目标、发送者等字段支持 * 通配。",
                ),
                ConfigFieldSpec(
                    path = "subscription.unfollowWhenNoSubscribers",
                    label = "无订阅目标时自动取消关注",
                    type = ConfigFieldType.BOOLEAN,
                    section = "订阅",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoParseEnabled",
                    label = "自动解析链接",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.maxLinksPerMessage",
                    label = "单条消息最大解析链接数",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    min = 1,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoReplyOnFailure",
                    label = "解析失败时自动回复",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoDedupeTtlMs",
                    label = "自动去重时间窗口（毫秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceRoot",
                    label = "原图缓存目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedRoot",
                    label = "渲染图片目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.downloadTimeoutMs",
                    label = "图片下载超时（毫秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    min = 1,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.maxConcurrentDownloads",
                    label = "最大并发下载数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    min = 1,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.cleanupCron",
                    label = "清理任务 Cron",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.enabled",
                    label = "清理原图缓存",
                    type = ConfigFieldType.BOOLEAN,
                    section = "图片缓存",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.maxIdleDays",
                    label = "原图最大闲置天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedCleanup.enabled",
                    label = "清理渲染图片",
                    type = ConfigFieldType.BOOLEAN,
                    section = "图片缓存",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedCleanup.maxIdleDays",
                    label = "渲染图片最大闲置天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.maxAttempts",
                    label = "最大投递尝试次数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    min = 1,
                ),
                ConfigFieldSpec(
                    path = "delivery.retryDelayMs",
                    label = "投递重试间隔（毫秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    min = 1,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.dispatchConcurrency",
                    label = "投递并发数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    min = 1,
                ),
                ConfigFieldSpec(
                    path = "delivery.lockTtlMs",
                    label = "投递锁超时（毫秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    min = 1,
                ),
                ConfigFieldSpec(
                    path = "draw.layout",
                    label = "布局套装",
                    type = ConfigFieldType.SELECT,
                    section = "绘图",
                    options = DrawLayoutRegistry.options(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.themeColors",
                    label = "全局主题色",
                    type = ConfigFieldType.TEXT,
                    section = "绘图",
                    description = "多个颜色用英文分号分隔，例如 #FE65A6;#BFFAFF",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.autoTheme",
                    label = "自动获取主题色",
                    type = ConfigFieldType.BOOLEAN,
                    section = "绘图",
                ),
                ConfigFieldSpec(
                    path = "draw.ornament",
                    label = "头部装饰",
                    type = ConfigFieldType.SELECT,
                    section = "绘图",
                    options = DrawOrnament.entries.map { ConfigFieldOption(it.name, it.name) },
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.width",
                    label = "绘图宽度",
                    type = ConfigFieldType.NUMBER,
                    section = "绘图",
                    min = 320,
                ),
                ConfigFieldSpec(
                    path = "draw.font.text",
                    label = "正文字体",
                    type = ConfigFieldType.TEXT,
                    section = "绘图字体",
                    description = "填写系统字体名称或字体文件路径。启动时会先按本地文件检测，找不到文件时再按字体名称匹配；留空使用内置字体。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "draw.font.emoji",
                    label = "Emoji 字体",
                    type = ConfigFieldType.TEXT,
                    section = "绘图字体",
                    description = "填写系统 Emoji 字体名称或字体文件路径。启动时会先按本地文件检测，找不到文件时再按字体名称匹配；留空使用内置 Emoji 字体。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.enabled",
                    label = "启用 Web 后台",
                    type = ConfigFieldType.BOOLEAN,
                    section = "Web 后台",
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.host",
                    label = "Web 后台监听地址",
                    type = ConfigFieldType.TEXT,
                    section = "Web 后台",
                    required = true,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.port",
                    label = "Web 后台端口",
                    type = ConfigFieldType.NUMBER,
                    section = "Web 后台",
                    min = 1,
                    max = 65_535,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.token",
                    label = "Web 后台 Token",
                    type = ConfigFieldType.SECRET,
                    section = "Web 后台",
                    secret = true,
                ),
            ),
        )

    public fun validate(config: MainDynamicConfig) {
        require(config.templates.dynamic.isNotBlank()) { "templates.dynamic 不能为空" }
        require(config.templates.liveStarted.isNotBlank()) { "templates.liveStarted 不能为空" }
        require(config.templates.liveEnded.isNotBlank()) { "templates.liveEnded 不能为空" }
        require(config.command.prefix.isNotBlank()) { "命令前缀不能为空" }
        config.command.permissions.forEachIndexed { index, rule ->
            require(rule.platformId.isNotBlank()) { "command.permissions[$index].platformId 不能为空" }
            require(rule.targetId.isNotBlank()) { "command.permissions[$index].targetId 不能为空" }
            require(rule.scopeId.isNotBlank()) { "command.permissions[$index].scopeId 不能为空" }
            require(rule.threadId.isNotBlank()) { "command.permissions[$index].threadId 不能为空" }
            require(rule.accountId.isNotBlank()) { "command.permissions[$index].accountId 不能为空" }
            require(rule.senderId.isNotBlank()) { "command.permissions[$index].senderId 不能为空" }
        }
        require(config.linkParsing.maxLinksPerMessage >= 1) { "单条消息最大解析链接数至少为 1" }
        require(config.linkParsing.autoDedupeTtlMs >= 0) { "自动去重时间窗口不能为负数" }
        require(config.imageCache.sourceRoot.isNotBlank()) { "原图缓存目录不能为空" }
        require(config.imageCache.renderedRoot.isNotBlank()) { "渲染图片目录不能为空" }
        require(config.imageCache.downloadTimeoutMs >= 1) { "图片下载超时至少为 1 毫秒" }
        require(config.imageCache.maxConcurrentDownloads >= 1) { "最大并发下载数至少为 1" }
        require(config.imageCache.cleanupCron.isNotBlank()) { "清理任务 Cron 不能为空" }
        require(config.imageCache.sourceCleanup.maxIdleDays >= 0) { "原图最大闲置天数不能为负数" }
        require(config.imageCache.renderedCleanup.maxIdleDays >= 0) { "渲染图片最大闲置天数不能为负数" }
        require(config.delivery.maxAttempts >= 1) { "最大投递尝试次数至少为 1" }
        require(config.delivery.retryDelayMs >= 1) { "投递重试间隔至少为 1 毫秒" }
        require(config.delivery.dispatchConcurrency >= 1) { "投递并发数至少为 1" }
        require(config.delivery.lockTtlMs >= 1) { "投递锁超时至少为 1 毫秒" }
        require(config.draw.layout.isNotBlank()) { "绘图布局不能为空" }
        require(DrawLayoutRegistry.hasSuite(config.draw.layout)) {
            "绘图布局必须是 ${DrawLayoutRegistry.options().joinToString("|") { it.value }} 之一"
        }
        DrawThemeFactory.parseThemeColors(config.draw.themeColors)
        require(config.draw.width >= 320) { "绘图宽度至少为 320" }
        require(config.webAdmin.port in 1..65_535) { "Web 后台端口必须在 1 到 65535 之间" }
        require(config.webAdmin.host.isNotBlank()) { "Web 后台监听地址不能为空" }
        if (config.webAdmin.enabled) {
            require(config.webAdmin.token.isNotBlank()) { "启用 Web 后台时 token 不能为空" }
        }
    }

    public fun restartTargets(previous: MainDynamicConfig, next: MainDynamicConfig): List<String> {
        val targets = linkedSetOf<String>()
        if (previous.webAdmin.enabled != next.webAdmin.enabled ||
            previous.webAdmin.host != next.webAdmin.host ||
            previous.webAdmin.port != next.webAdmin.port
        ) {
            targets += "Web 后台"
        }
        if (previous.imageCache != next.imageCache) {
            targets += "主程序"
        }
        if (previous.delivery.retryDelayMs != next.delivery.retryDelayMs) {
            targets += "主程序"
        }
        if (previous.draw.font != next.draw.font) {
            targets += "主程序"
        }
        return targets.toList()
    }

    public fun commandRoleOptions(): List<ConfigFieldOption> {
        return CommandRole.entries.map { ConfigFieldOption(it.name, it.name) }
    }

    public fun chatTypeOptions(): List<ConfigFieldOption> {
        return listOf(TargetKind.GROUP, TargetKind.USER, TargetKind.CHANNEL)
            .map { ConfigFieldOption(it.name, it.name) }
    }

}
