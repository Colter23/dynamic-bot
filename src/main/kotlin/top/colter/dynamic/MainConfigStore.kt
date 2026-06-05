package top.colter.dynamic

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.admin.AdminLogBuffer
import top.colter.dynamic.draw.DrawLayoutRegistry
import top.colter.dynamic.draw.DrawThemeFactory

public class MainConfigStore(
    private val configService: ConfigService = YamlConfigService(),
) {
    @Volatile
    private var currentConfig: MainDynamicConfig? = null

    public fun loadOrCreate(adminTokenProvider: () -> String): MainDynamicConfig {
        val loaded = configService.loadOrCreate(
            MainDynamicConfig.CONFIG_ID,
            MainDynamicConfig::class,
            MainConfigForms.migrations,
        ) {
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
        AdminLogBuffer.configureCapacity(withToken.webAdmin.logBufferCapacity)
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
        AdminLogBuffer.configureCapacity(next.webAdmin.logBufferCapacity)
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
    public val migrations: List<ConfigMigration> = listOf(
        ConfigMigration(
            id = "main-draw-theme-colors",
            description = "迁移旧绘图主题色字段到 draw.themeColors",
        ) {
            val legacyColors = listOf(
                get("draw.themeColor") as? String,
                get("draw.backgroundStartColor") as? String,
                get("draw.backgroundEndColor") as? String,
            )
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .distinct()

            if (!contains("draw.themeColors") && legacyColors.isNotEmpty()) {
                set("draw.themeColors", legacyColors.joinToString(";"))
            }
            remove("draw.themeColor")
            remove("draw.backgroundStartColor")
            remove("draw.backgroundEndColor")
        },
    )

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
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "DYNAMIC"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveStarted",
                    label = "开播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "支持 {draw} {name} {uid} {rid} {time} {title} {area} {cover} {link}；\\n 换行，\\r 分割为多条消息。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LIVE_STARTED"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveEnded",
                    label = "下播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "支持 {name} {uid} {rid} {title} {area} {startTime} {endTime} {duration} {link}；\\n 换行。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LIVE_ENDED"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.prefix",
                    label = "命令前缀",
                    type = ConfigFieldType.TEXT,
                    section = "命令",
                    description = "Bot 命令的触发前缀，例如 /db；修改后新的命令会按此前缀识别。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.receiveMode",
                    label = "命令接收策略",
                    type = ConfigFieldType.SELECT,
                    section = "命令",
                    description = "多个 Bot 同时接入同一消息目标时，决定哪些 Bot 可以处理聊天命令。",
                    options = commandReceiveModeOptions(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.requirePermissionRule",
                    label = "必须配置权限规则",
                    type = ConfigFieldType.BOOLEAN,
                    section = "命令",
                    description = "开启后，未命中权限规则的发送者没有任何指令权限；关闭后未命中规则的发送者按普通用户处理。",
                ),
                ConfigFieldSpec(
                    path = "command.permissions",
                    label = "权限规则",
                    type = ConfigFieldType.JSON,
                    section = "命令",
                    description = "未命中规则时为普通用户；命中多条规则时取最高权限。命令路径、平台、目标、发送者等字段支持 * 通配。",
                    component = "COMMAND_PERMISSION_TABLE",
                ),
                ConfigFieldSpec(
                    path = "subscription.autoFollowPublisherOnSubscribe",
                    label = "订阅时自动关注发布者",
                    type = ConfigFieldType.BOOLEAN,
                    section = "订阅",
                    description = "添加订阅时，允许来源插件自动关注对应发布者；关闭后只保存订阅关系，不主动关注。",
                ),
                ConfigFieldSpec(
                    path = "subscription.unfollowWhenNoSubscribers",
                    label = "无订阅目标时自动取消关注",
                    type = ConfigFieldType.BOOLEAN,
                    section = "订阅",
                    description = "发布者没有任何有效订阅目标时，允许来源插件自动取消关注该发布者。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoParseEnabled",
                    label = "自动解析链接",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "收到聊天消息中的支持链接时，自动解析并按当前消息目标转发解析结果。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.fallbackTriggerMode",
                    label = "未配置目标回退触发方式",
                    type = ConfigFieldType.SELECT,
                    section = "链接解析",
                    description = "当前消息目标没有单独配置时使用的自动解析触发方式。",
                    options = listOf(
                        ConfigFieldOption(LinkParseTriggerMode.DISABLED.name, "不解析"),
                        ConfigFieldOption(LinkParseTriggerMode.MENTION_ONLY.name, "必须 @bot"),
                        ConfigFieldOption(LinkParseTriggerMode.ALWAYS.name, "匹配链接即解析"),
                    ),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.maxLinksPerMessage",
                    label = "单条消息最大解析链接数",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "一条聊天消息内最多自动处理多少个支持链接，超出部分会被忽略。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.replyOnFailure",
                    label = "解析失败时自动回复",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "自动解析链接失败时，在原消息目标中回复失败原因。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoDedupeTtlSeconds",
                    label = "自动去重时间窗口（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "同一个链接在该时间窗口内只会自动转发一次，仅“匹配链接即解析”模式有效。",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.enabled",
                    label = "解析中提示",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "自动解析触发后，在原消息目标中发送一条解析中的提示消息。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.text",
                    label = "解析中提示文字",
                    type = ConfigFieldType.TEXT,
                    section = "链接解析",
                    description = "自动解析耗时较长时展示给用户的提示文字。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.recallOnComplete",
                    label = "完成后撤回解析中提示",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "解析结果发送完成或失败后，尽量撤回解析中的提示消息；不支持撤回的平台会忽略。",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceRoot",
                    label = "原图缓存目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "下载到本地的头像、封面、动态图片等原始图片缓存目录。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedRoot",
                    label = "渲染图片目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "动态绘制结果的输出目录，消息发送时会从这里读取生成图片。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.downloadTimeoutSeconds",
                    label = "图片下载超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "下载单张远程图片的超时时间；支持小数，例如 0.5 表示 0.5 秒。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.maxImageBytes",
                    label = "单张图片大小上限（字节）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "后台预览和图片缓存允许读取或下载的单张图片最大体积，用于避免异常大图占满内存。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.maxConcurrentDownloads",
                    label = "最大并发下载数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "同一条动态中允许同时下载的图片数量，过高可能增加网络和内存压力。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.memoryMaxBytes",
                    label = "图片内存缓存上限（字节）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "原图字节在进程内热缓存的最大总量；超出后按最近最少使用淘汰，磁盘缓存不受影响。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.memoryMaxEntries",
                    label = "图片内存缓存最大条数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "原图字节在进程内最多保留多少个 URI；设为 0 表示不保留图片字节热缓存。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.cleanupCron",
                    label = "清理任务 Cron",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "图片缓存清理任务的 Cron 表达式，默认每天凌晨 4 点执行。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.enabled",
                    label = "清理原图缓存",
                    type = ConfigFieldType.BOOLEAN,
                    section = "图片缓存",
                    description = "开启后会定期删除长时间未访问的原始图片缓存。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.maxIdleDays",
                    label = "原图最大闲置天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "原图缓存超过该天数未被访问后会被清理；0 表示只要命中清理任务即可删除。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedCleanup.enabled",
                    label = "清理渲染图片",
                    type = ConfigFieldType.BOOLEAN,
                    section = "图片缓存",
                    description = "开启后会定期删除长时间未访问的动态绘制结果。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedCleanup.maxIdleDays",
                    label = "渲染图片最大闲置天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "渲染图片超过该天数未被访问后会被清理；0 表示只要命中清理任务即可删除。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.maxAttempts",
                    label = "最大投递尝试次数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "消息发送失败后最多尝试投递的次数，达到上限后会标记为失败。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "delivery.retryDelaySeconds",
                    label = "投递重试间隔（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "投递失败后等待多久再次尝试；支持小数，例如 0.5 表示 0.5 秒。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.dispatchConcurrency",
                    label = "投递并发数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "同一轮投递任务允许并发发送的消息数量。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "delivery.lockTtlSeconds",
                    label = "投递锁超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "投递任务被领取后多久未完成会恢复为待投递；支持小数，例如 0.5 表示 0.5 秒。",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "delivery.historyRetentionDays",
                    label = "消息记录保留天数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "已成功或最终失败的投递记录超过该天数后会被定期清理；0 表示下次清理时删除所有终态记录。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.cleanupCron",
                    label = "消息记录清理 Cron",
                    type = ConfigFieldType.TEXT,
                    section = "消息投递",
                    description = "消息记录清理任务的 Cron 表达式，默认每天凌晨 4:30 执行。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.strategy",
                    label = "默认账号路由策略",
                    type = ConfigFieldType.SELECT,
                    section = "消息路由",
                    description = "未给真实平台单独配置时使用的账号选择策略。",
                    options = messageRoutingStrategyOptions(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.primaryAccountId",
                    label = "默认主 Bot 账号",
                    type = ConfigFieldType.TEXT,
                    section = "消息路由",
                    description = "留空时使用当前平台第一个接入的可用 Bot；轮询也会从主 Bot 开始。",
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.failureCooldownSeconds",
                    label = "默认失败冷却秒数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息路由",
                    description = "某条发送路线失败后，在该时间内优先跳过它。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "messageRouting.platformPolicies",
                    label = "按平台路由策略",
                    type = ConfigFieldType.JSON,
                    section = "消息路由",
                    description = "为 qq、discord 等真实目标平台单独覆盖路由策略；未配置的平台使用默认策略。",
                    component = "MESSAGE_ROUTING_POLICY_TABLE",
                    metadata = mapOf(
                        "example" to """
                            [
                              {"platformId":"qq","policy":{"strategy":"PRIMARY_BACKUP","primaryAccountId":"123456","failureCooldownSeconds":60}}
                            ]
                        """.trimIndent(),
                    ),
                ),
                ConfigFieldSpec(
                    path = "draw.layout",
                    label = "布局套装",
                    type = ConfigFieldType.SELECT,
                    section = "绘图",
                    description = "动态图片使用的绘图布局，影响整体排版和内容呈现。",
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
                    description = "开启后会优先从头像或图片中提取主题色，失败时使用全局主题色。",
                ),
                ConfigFieldSpec(
                    path = "draw.ornament",
                    label = "头部装饰",
                    type = ConfigFieldType.SELECT,
                    section = "绘图",
                    description = "动态图片头部右侧的装饰内容，可选择 Logo、二维码或不显示。",
                    options = DrawOrnament.entries.map { ConfigFieldOption(it.name, it.name) },
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.width",
                    label = "绘图宽度",
                    type = ConfigFieldType.NUMBER,
                    section = "绘图",
                    description = "生成动态图片的基础宽度，过小会压缩内容，过大可能增加渲染耗时。",
                    min = 320,
                    numberKind = ConfigNumberKind.INTEGER,
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
                    path = "pluginCatalog.url",
                    label = "插件列表地址",
                    type = ConfigFieldType.TEXT,
                    section = "插件目录",
                    description = "官方插件列表 JSON 地址；必须使用 https://，留空表示关闭插件下载与更新功能。",
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.cacheSeconds",
                    label = "插件列表缓存时间（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "后台拉取插件列表后的内存缓存时间；设为 0 表示每次都重新获取。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.downloadTimeoutSeconds",
                    label = "插件下载超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "下载插件 Jar 和插件列表时允许等待的时间；支持小数，例如 0.5 表示 0.5 秒。",
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.maxDownloadBytes",
                    label = "插件最大下载大小（字节）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "单个插件 Jar 允许下载的最大字节数，默认 200MB。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "webAdmin.enabled",
                    label = "启用 Web 后台",
                    type = ConfigFieldType.BOOLEAN,
                    section = "Web 后台",
                    description = "关闭后不启动内置运维后台，需要重启主程序才能生效。",
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.host",
                    label = "Web 后台监听地址",
                    type = ConfigFieldType.TEXT,
                    section = "Web 后台",
                    description = "后台服务绑定的地址；本机访问通常使用 127.0.0.1，对外开放需谨慎配置。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.port",
                    label = "Web 后台端口",
                    type = ConfigFieldType.NUMBER,
                    section = "Web 后台",
                    description = "后台服务监听端口，访问地址为 http://监听地址:端口/admin。",
                    min = 1,
                    max = 65_535,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.token",
                    label = "Web 后台 Token",
                    type = ConfigFieldType.SECRET,
                    section = "Web 后台",
                    description = "登录 Web 后台使用的访问令牌，留空会在首次启动时自动生成。",
                    secret = true,
                ),
                ConfigFieldSpec(
                    path = "webAdmin.logBufferCapacity",
                    label = "日志缓冲容量",
                    type = ConfigFieldType.NUMBER,
                    section = "Web 后台",
                    description = "Web 后台在内存中保留的最近日志条数；只影响当前进程的实时日志页面，不读取历史日志文件。",
                    min = 100,
                    max = AdminLogBuffer.MAX_CAPACITY.toLong(),
                    numberKind = ConfigNumberKind.INTEGER,
                ),
            ),
        )

    public fun validate(config: MainDynamicConfig) {
        require(config.templates.dynamic.isNotBlank()) { "templates.dynamic 不能为空" }
        require(config.templates.liveStarted.isNotBlank()) { "templates.liveStarted 不能为空" }
        require(config.templates.liveEnded.isNotBlank()) { "templates.liveEnded 不能为空" }
        require(config.command.prefix.isNotBlank()) { "命令前缀不能为空" }
        config.command.permissions.forEachIndexed { index, rule ->
            require(rule.commandPath.isNotBlank()) { "command.permissions[$index].commandPath 不能为空" }
            require(rule.platformId.isNotBlank()) { "command.permissions[$index].platformId 不能为空" }
            require(rule.targetId.isNotBlank()) { "command.permissions[$index].targetId 不能为空" }
            require(rule.scopeId.isNotBlank()) { "command.permissions[$index].scopeId 不能为空" }
            require(rule.threadId.isNotBlank()) { "command.permissions[$index].threadId 不能为空" }
            require(rule.botAccountId.isNotBlank()) { "command.permissions[$index].botAccountId 不能为空" }
            require(rule.senderId.isNotBlank()) { "command.permissions[$index].senderId 不能为空" }
            require(rule.role != CommandRole.NONE) {
                "command.permissions[$index].role 不能为 NONE"
            }
            require(rule.senderId != "*" || rule.role != CommandRole.ADMIN) {
                "command.permissions[$index] 不能把全部发送者直接设为管理员"
            }
        }
        require(config.linkParsing.maxLinksPerMessage >= 1) { "单条消息最大解析链接数至少为 1" }
        require(config.linkParsing.autoDedupeTtlSeconds >= 0.0) { "自动去重时间窗口不能为负数" }
        if (config.linkParsing.progressReply.enabled) {
            require(config.linkParsing.progressReply.text.isNotBlank()) { "解析中提示文字不能为空" }
        }
        require(config.imageCache.sourceRoot.isNotBlank()) { "原图缓存目录不能为空" }
        require(config.imageCache.renderedRoot.isNotBlank()) { "渲染图片目录不能为空" }
        require(config.imageCache.downloadTimeoutSeconds > 0.0) { "图片下载超时必须大于 0 秒" }
        require(config.imageCache.maxImageBytes > 0) { "单张图片大小上限必须大于 0" }
        require(config.imageCache.maxConcurrentDownloads >= 1) { "最大并发下载数至少为 1" }
        require(config.imageCache.memoryMaxBytes >= 0) { "图片内存缓存上限不能为负数" }
        require(config.imageCache.memoryMaxEntries >= 0) { "图片内存缓存最大条数不能为负数" }
        require(config.imageCache.cleanupCron.isNotBlank()) { "清理任务 Cron 不能为空" }
        require(config.imageCache.sourceCleanup.maxIdleDays >= 0) { "原图最大闲置天数不能为负数" }
        require(config.imageCache.renderedCleanup.maxIdleDays >= 0) { "渲染图片最大闲置天数不能为负数" }
        require(config.delivery.maxAttempts >= 1) { "最大投递尝试次数至少为 1" }
        require(config.delivery.retryDelaySeconds > 0.0) { "投递重试间隔必须大于 0 秒" }
        require(config.delivery.dispatchConcurrency >= 1) { "投递并发数至少为 1" }
        require(config.delivery.lockTtlSeconds > 0.0) { "投递锁超时必须大于 0 秒" }
        require(config.delivery.historyRetentionDays >= 0) { "消息记录保留天数不能为负数" }
        require(config.delivery.cleanupCron.isNotBlank()) { "消息记录清理 Cron 不能为空" }
        require(config.messageRouting.defaultPolicy.failureCooldownSeconds >= 1) { "默认失败冷却秒数至少为 1" }
        val routePlatformIds = config.messageRouting.platformPolicies.map { it.platformId.trim().lowercase() }
        require(routePlatformIds.none { it.isBlank() }) { "按平台路由策略的平台 ID 不能为空" }
        val duplicatedRoutePlatforms = routePlatformIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicatedRoutePlatforms.isEmpty()) {
            "按平台路由策略的平台 ID 不能重复：${duplicatedRoutePlatforms.joinToString(",")}"
        }
        config.messageRouting.platformPolicies.forEachIndexed { index, platformPolicy ->
            require(platformPolicy.policy.failureCooldownSeconds >= 1) {
                "messageRouting.platformPolicies[$index].policy.failureCooldownSeconds 至少为 1"
            }
        }
        require(config.draw.layout.isNotBlank()) { "绘图布局不能为空" }
        require(DrawLayoutRegistry.hasSuite(config.draw.layout)) {
            "绘图布局必须是 ${DrawLayoutRegistry.options().joinToString("|") { it.value }} 之一"
        }
        DrawThemeFactory.parseThemeColors(config.draw.themeColors)
        require(config.draw.width >= 320) { "绘图宽度至少为 320" }
        val catalogUrl = config.pluginCatalog.url.trim()
        if (catalogUrl.isNotBlank()) {
            require(catalogUrl.startsWith("https://", ignoreCase = true)) { "插件列表地址必须使用 https://" }
        }
        require(config.pluginCatalog.cacheSeconds >= 0) { "插件列表缓存时间不能为负数" }
        require(config.pluginCatalog.downloadTimeoutSeconds > 0.0) { "插件下载超时必须大于 0 秒" }
        require(config.pluginCatalog.maxDownloadBytes > 0) { "插件最大下载大小必须大于 0" }
        require(config.webAdmin.port in 1..65_535) { "Web 后台端口必须在 1 到 65535 之间" }
        require(config.webAdmin.host.isNotBlank()) { "Web 后台监听地址不能为空" }
        require(config.webAdmin.logBufferCapacity in 100..AdminLogBuffer.MAX_CAPACITY) {
            "日志缓冲容量必须在 100 到 ${AdminLogBuffer.MAX_CAPACITY} 之间"
        }
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
        if (previous.delivery.retryDelaySeconds != next.delivery.retryDelaySeconds ||
            previous.delivery.historyRetentionDays != next.delivery.historyRetentionDays ||
            previous.delivery.cleanupCron != next.delivery.cleanupCron
        ) {
            targets += "主程序"
        }
        if (previous.draw.font != next.draw.font) {
            targets += "主程序"
        }
        return targets.toList()
    }

    public fun commandRoleOptions(): List<ConfigFieldOption> {
        return CommandRole.entries.filter { it != CommandRole.NONE }.map {
            ConfigFieldOption(
                value = it.name,
                label = when (it) {
                    CommandRole.USER -> "普通用户"
                    CommandRole.MANAGER -> "目标管理员"
                    CommandRole.ADMIN -> "系统管理员"
                    CommandRole.NONE -> "无权限"
                },
            )
        }
    }

    public fun commandReceiveModeOptions(): List<ConfigFieldOption> {
        return listOf(
            ConfigFieldOption(CommandReceiveMode.PRIMARY_OR_MENTIONED.name, "主 Bot 或被 @ 的 Bot"),
            ConfigFieldOption(CommandReceiveMode.MENTIONED_ONLY.name, "必须 @ 当前 Bot"),
            ConfigFieldOption(CommandReceiveMode.ANY.name, "所有 Bot 都处理"),
        )
    }

    public fun chatTypeOptions(): List<ConfigFieldOption> {
        return listOf(TargetKind.GROUP, TargetKind.USER, TargetKind.CHANNEL)
            .map { ConfigFieldOption(it.name, it.name) }
    }

    public fun messageRoutingStrategyOptions(): List<ConfigFieldOption> {
        return listOf(
            ConfigFieldOption(MessageSinkRoutingStrategy.ROUND_ROBIN.name, "轮询分担"),
            ConfigFieldOption(MessageSinkRoutingStrategy.PRIMARY_BACKUP.name, "主备切换"),
        )
    }
}
