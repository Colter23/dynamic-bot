package top.colter.dynamic

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.draw.DrawLayoutRegistry

public class MainConfigStore(
    private val configService: ConfigService = DefaultConfigService,
) {
    @Volatile
    private var currentConfig: MainDynamicConfig? = null

    public fun loadOrCreate(adminTokenProvider: () -> String): MainDynamicConfig {
        val loaded = configService.loadOrCreate(MainDynamicConfig.CONFIG_ID, MainDynamicConfig::class) {
            MainDynamicConfig()
        }
        val resolved = if (loaded.webAdmin.enabled && loaded.webAdmin.token.isBlank()) {
            loaded.copy(webAdmin = loaded.webAdmin.copy(token = adminTokenProvider()))
                .also { configService.save(MainDynamicConfig.CONFIG_ID, it, MainDynamicConfig::class) }
        } else {
            loaded
        }
        currentConfig = resolved
        return resolved
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

        configService.save(MainDynamicConfig.CONFIG_ID, next, MainDynamicConfig::class)
        currentConfig = next

        val restartTargets = MainConfigForms.restartTargets(previous, next)
        return ConfigApplyResult(
            changed = true,
            restartRequired = restartTargets.isNotEmpty(),
            restartTargets = restartTargets,
            message = if (restartTargets.isEmpty()) {
                "主配置已保存并生效"
            } else {
                "主配置已保存；${restartTargets.joinToString("、")} 需要重启"
            },
        )
    }

    public fun formSpec(): ConfigFormSpec = MainConfigForms.formSpec
}

public object MainConfigForms {
    public val formSpec: ConfigFormSpec
        get() = ConfigFormSpec(
            title = "主配置",
            description = "主项目的模板、命令、订阅、链接解析、图片缓存和 Web 后台设置。",
            fields = listOf(
            ConfigFieldSpec(
                path = "templates",
                label = "消息模板",
                type = ConfigFieldType.TEMPLATE_MAP,
                section = "消息模板",
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
                type = ConfigFieldType.COMMAND_PERMISSIONS,
                section = "命令",
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
                path = "draw.layout",
                label = "布局套装",
                type = ConfigFieldType.SELECT,
                section = "绘图",
                options = DrawLayoutRegistry.options(),
                required = true,
            ),
            ConfigFieldSpec(
                path = "draw.themeColor",
                label = "主题色",
                type = ConfigFieldType.TEXT,
                section = "绘图",
                required = true,
            ),
            ConfigFieldSpec(
                path = "draw.backgroundStartColor",
                label = "背景起始色",
                type = ConfigFieldType.TEXT,
                section = "绘图",
                required = true,
            ),
            ConfigFieldSpec(
                path = "draw.backgroundEndColor",
                label = "背景结束色",
                type = ConfigFieldType.TEXT,
                section = "绘图",
                required = true,
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
                path = "draw.font.textFamily",
                label = "正文字体名",
                type = ConfigFieldType.TEXT,
                section = "绘图字体",
            ),
            ConfigFieldSpec(
                path = "draw.font.emojiFamily",
                label = "Emoji 字体名",
                type = ConfigFieldType.TEXT,
                section = "绘图字体",
            ),
            ConfigFieldSpec(
                path = "draw.font.textFontFile",
                label = "正文字体文件",
                type = ConfigFieldType.TEXT,
                section = "绘图字体",
            ),
            ConfigFieldSpec(
                path = "draw.font.emojiFontFile",
                label = "Emoji 字体文件",
                type = ConfigFieldType.TEXT,
                section = "绘图字体",
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
        require(config.templates.containsKey(MainDynamicConfig.DEFAULT_TEMPLATE_NAME)) {
            "templates must include '${MainDynamicConfig.DEFAULT_TEMPLATE_NAME}'"
        }
        require(config.templates[MainDynamicConfig.DEFAULT_TEMPLATE_NAME]?.isNotBlank() == true) {
            "default template must not be blank"
        }
        require(config.templates.keys.all { it.isNotBlank() }) { "template names must not be blank" }
        require(config.command.prefix.isNotBlank()) { "command prefix must not be blank" }
        config.command.permissions.forEachIndexed { index, rule ->
            require(rule.platform.isNotBlank()) { "command.permissions[$index].platform must not be blank" }
            require(rule.chatId.isNotBlank()) { "command.permissions[$index].chatId must not be blank" }
            require(rule.senderId.isNotBlank()) { "command.permissions[$index].senderId must not be blank" }
        }
        require(config.linkParsing.maxLinksPerMessage >= 1) { "linkParsing.maxLinksPerMessage must be at least 1" }
        require(config.linkParsing.autoDedupeTtlMs >= 0) { "linkParsing.autoDedupeTtlMs must not be negative" }
        require(config.imageCache.sourceRoot.isNotBlank()) { "imageCache.sourceRoot must not be blank" }
        require(config.imageCache.renderedRoot.isNotBlank()) { "imageCache.renderedRoot must not be blank" }
        require(config.imageCache.downloadTimeoutMs >= 1) { "imageCache.downloadTimeoutMs must be at least 1" }
        require(config.imageCache.maxConcurrentDownloads >= 1) {
            "imageCache.maxConcurrentDownloads must be at least 1"
        }
        require(config.imageCache.cleanupCron.isNotBlank()) { "imageCache.cleanupCron must not be blank" }
        require(config.imageCache.sourceCleanup.maxIdleDays >= 0) {
            "imageCache.sourceCleanup.maxIdleDays must not be negative"
        }
        require(config.imageCache.renderedCleanup.maxIdleDays >= 0) {
            "imageCache.renderedCleanup.maxIdleDays must not be negative"
        }
        require(config.draw.layout.isNotBlank()) { "draw.layout must not be blank" }
        require(DrawLayoutRegistry.hasSuite(config.draw.layout)) {
            "draw.layout must be one of ${DrawLayoutRegistry.options().joinToString("|") { it.value }}"
        }
        requireColor(config.draw.themeColor, "draw.themeColor")
        requireColor(config.draw.backgroundStartColor, "draw.backgroundStartColor")
        requireColor(config.draw.backgroundEndColor, "draw.backgroundEndColor")
        require(config.draw.width >= 320) { "draw.width must be at least 320" }
        require(config.webAdmin.port in 1..65_535) { "webAdmin.port must be between 1 and 65535" }
        require(config.webAdmin.host.isNotBlank()) { "webAdmin.host must not be blank" }
        if (config.webAdmin.enabled) {
            require(config.webAdmin.token.isNotBlank()) { "webAdmin.token must not be blank when web admin is enabled" }
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
        return targets.toList()
    }

    public fun commandRoleOptions(): List<ConfigFieldOption> {
        return CommandRole.entries.map { ConfigFieldOption(it.name, it.name) }
    }

    public fun chatTypeOptions(): List<ConfigFieldOption> {
        return ChatType.entries.map { ConfigFieldOption(it.name, it.name) }
    }

    private fun requireColor(value: String, path: String) {
        require(HEX_COLOR_REGEX.matches(value)) {
            "$path must be #RRGGBB or #AARRGGBB"
        }
    }

    private val HEX_COLOR_REGEX: Regex = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
}
