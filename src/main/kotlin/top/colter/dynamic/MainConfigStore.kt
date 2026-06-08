package top.colter.dynamic

import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldOption
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFieldVisibility
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigMigration
import top.colter.dynamic.core.config.ConfigNumberKind
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.event.SystemNotificationSeverity
import top.colter.dynamic.core.link.LinkVideoQuality
import top.colter.dynamic.core.plugin.MessageSinkRoutingStrategy
import top.colter.dynamic.admin.AdminLogBuffer
import top.colter.dynamic.draw.DrawLayoutRegistry
import top.colter.dynamic.draw.DrawThemeFactory
import top.colter.dynamic.link.LinkPreviewTemplateRenderer
import top.colter.dynamic.listener.PushTemplateRenderer

public class MainConfigStore(
    private val configService: ConfigService = YamlConfigService(),
) {
    @Volatile
    private var currentConfig: MainDynamicConfig? = null

    public fun loadOrCreate(adminTokenProvider: () -> String): MainDynamicConfig {
        return loadOrCreate(
            adminTokenProvider = adminTokenProvider,
            secretProvider = adminTokenProvider,
        )
    }

    public fun loadOrCreate(
        adminTokenProvider: () -> String,
        secretProvider: () -> String,
    ): MainDynamicConfig {
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
        val withSecrets = if (withToken.outboundMedia.signingSecret.isBlank()) {
            withToken.copy(outboundMedia = withToken.outboundMedia.copy(signingSecret = secretProvider()))
        } else {
            withToken
        }
        if (withSecrets != loaded) {
            configService.save(MainDynamicConfig.CONFIG_ID, withSecrets)
        }
        AdminLogBuffer.configureCapacity(withSecrets.webAdmin.logBufferCapacity)
        currentConfig = withSecrets
        return withSecrets
    }

    public fun current(): MainDynamicConfig {
        return currentConfig ?: loadOrCreate(adminTokenProvider = { "" }, secretProvider = { "" })
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
                    description = "动态通知的发送格式。\n可使用 {draw}、{name}、{uid}、{did}、{time}、{content}、{images}、{link}、{links}。\\n 表示换行，\\r 表示拆成多条消息，{>>}...{<<} 会作为合并转发发送。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "DYNAMIC"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveStarted",
                    label = "开播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "开播通知的发送格式。\n可使用 {draw}、{name}、{uid}、{rid}、{time}、{title}、{area}、{cover}、{link}。\\n 表示换行，\\r 表示拆成多条消息，{>>}...{<<} 会作为合并转发发送。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LIVE_STARTED"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "templates.liveEnded",
                    label = "下播模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "消息模板",
                    description = "下播通知的发送格式。\n可使用 {name}、{uid}、{rid}、{title}、{area}、{startTime}、{endTime}、{duration}、{link}。\\n 表示换行，{>>}...{<<} 会作为合并转发发送。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LIVE_ENDED"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.prefix",
                    label = "命令前缀",
                    type = ConfigFieldType.TEXT,
                    section = "命令",
                    description = "聊天中触发 Bot 命令的开头文字。\n例如填写 /db，用户发送 /db help 时会被识别为命令。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.receiveMode",
                    label = "命令接收策略",
                    type = ConfigFieldType.SELECT,
                    section = "命令",
                    description = "决定哪个 Bot 响应聊天命令。\n当同一个群或频道接入多个 Bot 时，用它避免多个 Bot 同时回复。",
                    options = commandReceiveModeOptions(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "command.requirePermissionRule",
                    label = "只允许规则内用户",
                    type = ConfigFieldType.BOOLEAN,
                    section = "命令",
                    description = "控制没有规则的用户能否使用命令。\n开启后必须先配置权限规则；关闭后没有命中的用户会按普通用户处理。",
                ),
                ConfigFieldSpec(
                    path = "command.permissions",
                    label = "权限规则",
                    type = ConfigFieldType.JSON,
                    section = "命令",
                    description = "给指定用户或目标分配命令权限。\n可按命令、平台、群或用户、发送者匹配；* 表示全部，命中多条时使用最高权限。",
                    component = "COMMAND_PERMISSION_TABLE",
                ),
                ConfigFieldSpec(
                    path = "subscription.autoFollowPublisherOnSubscribe",
                    label = "订阅时自动关注发布者",
                    type = ConfigFieldType.BOOLEAN,
                    section = "订阅",
                    description = "添加订阅时自动关注发布者。\n关闭后只保存订阅关系，不会让来源平台插件主动关注对方。",
                ),
                ConfigFieldSpec(
                    path = "subscription.unfollowWhenNoSubscribers",
                    label = "无订阅目标时自动取消关注",
                    type = ConfigFieldType.BOOLEAN,
                    section = "订阅",
                    description = "没人订阅时自动取消关注发布者。\n用于减少来源平台的关注列表；关闭后即使没人订阅也保留关注。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoParseEnabled",
                    label = "自动解析链接",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "自动处理聊天中的支持链接。\n开启后，群聊或私聊里出现支持的链接时，会按当前目标发送解析结果。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.fallbackTriggerMode",
                    label = "默认链接解析方式",
                    type = ConfigFieldType.SELECT,
                    section = "链接解析",
                    description = "没有单独设置时怎么触发链接解析。\n可选择不解析、必须 @Bot，或看到支持链接就解析。",
                    options = listOf(
                        ConfigFieldOption(LinkParseTriggerMode.DISABLED.name, "不解析"),
                        ConfigFieldOption(LinkParseTriggerMode.MENTION_ONLY.name, "必须 @bot"),
                        ConfigFieldOption(LinkParseTriggerMode.ALWAYS.name, "匹配链接即解析"),
                    ),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.maxLinksPerMessage",
                    label = "单条消息链接上限",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "一条消息里最多解析几个链接。\n超过数量的链接会被忽略，避免一条消息触发太多转发。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.replyOnFailure",
                    label = "解析失败时自动回复",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "解析失败时是否回复原因。\n开启后用户能看到失败提示；关闭后失败只写入日志。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.autoDedupeTtlSeconds",
                    label = "链接去重时间（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "防止同一个链接短时间重复转发。\n只对“匹配链接即解析”模式生效；设为 0 表示不去重。",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.enabled",
                    label = "解析中提示",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "解析耗时时先发一条提示。\n适合视频下载等慢操作，让用户知道 Bot 已经开始处理。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.text",
                    label = "解析中提示文字",
                    type = ConfigFieldType.TEXT,
                    section = "链接解析",
                    description = "解析中提示的文字内容。\n只有开启“解析中提示”后才会发送。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.progressReply.recallOnComplete",
                    label = "完成后撤回提示",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "完成后自动撤回解析中提示。\n如果目标平台不支持撤回，会自动忽略，不影响解析结果发送。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.templates.video",
                    label = "视频链接模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "链接解析模板",
                    description = "视频链接预览的发送格式。\n可使用 {draw}、{cover}、{name}、{uid}、{id}、{kind}、{title}、{content}、{duration}、{stats}、{link}。\\n 表示换行，\\r 表示拆成多条消息。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LINK_VIDEO"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.templates.live",
                    label = "直播链接模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "链接解析模板",
                    description = "直播间链接预览的发送格式。\n可使用 {draw}、{cover}、{name}、{uid}、{id}、{kind}、{title}、{content}、{stats}、{link}。\\n 表示换行，\\r 表示拆成多条消息。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LINK_LIVE"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.templates.user",
                    label = "用户页链接模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "链接解析模板",
                    description = "用户主页链接预览的发送格式。\n可使用 {draw}、{cover}、{name}、{uid}、{id}、{kind}、{title}、{content}、{link}。\\n 表示换行，\\r 表示拆成多条消息。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LINK_USER"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.templates.fallback",
                    label = "通用链接模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "链接解析模板",
                    description = "其他链接预览的发送格式。\n当链接没有专用模板时使用。可使用 {draw}、{cover}、{name}、{uid}、{id}、{kind}、{title}、{content}、{stats}、{link}。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LINK_FALLBACK"),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "linkParsing.templates.videoFile",
                    label = "下载视频模板",
                    type = ConfigFieldType.TEXTAREA,
                    section = "链接解析模板",
                    description = "视频文件发送成功后的消息格式。\n可使用 {video}、{name}、{uid}、{id}、{title}、{content}、{duration}、{size}、{link}。\\n 表示换行，\\r 表示拆成多条消息。",
                    component = "MESSAGE_TEMPLATE_EDITOR",
                    metadata = mapOf("templateKind" to "LINK_VIDEO_FILE"),
                    required = true,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.enabled",
                    label = "自动下载视频",
                    type = ConfigFieldType.BOOLEAN,
                    section = "链接解析",
                    description = "解析视频链接时尝试发送视频文件。\n如果下载失败、太大或太长，会自动退回为普通链接预览。",
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.maxDurationSeconds",
                    label = "最大视频时长（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "只下载不超过这个时长的视频。\n设为 0 表示不限制时长。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.maxFileMegabytes",
                    label = "视频大小上限（MB）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "只下载不超过这个大小的视频。\n支持小数，例如 0.5 表示 0.5 MB；超过后会退回为普通链接预览。",
                    min = 0,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.quality",
                    label = "视频画质",
                    type = ConfigFieldType.SELECT,
                    section = "链接解析",
                    description = "选择下载视频时的画质。\n画质越高文件越大，发送失败的概率也可能更高。",
                    options = linkVideoQualityOptions(),
                    required = true,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.ffmpegPath",
                    label = "ffmpeg 程序路径",
                    type = ConfigFieldType.TEXT,
                    section = "链接解析",
                    description = "用于合并视频和音频的 ffmpeg 程序。\n留空时会从项目目录自动查找；找不到时会退回为普通链接预览。",
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.cacheRoot",
                    label = "视频缓存目录",
                    type = ConfigFieldType.TEXT,
                    section = "链接解析",
                    description = "保存已下载视频文件的目录。\n后续清理任务会按缓存规则删除旧文件。",
                    required = true,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.timeoutSeconds",
                    label = "视频下载超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "单个视频最多下载多久。\n超过时间会停止下载，并退回为普通链接预览。",
                    min = 1,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.maxConcurrentDownloads",
                    label = "同时下载视频数",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "同一时间最多下载几个视频。\n调大可以更快处理多个链接，但会增加网络和磁盘压力。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "linkParsing.videoDownload.cleanupMaxIdleDays",
                    label = "视频缓存保留天数",
                    type = ConfigFieldType.NUMBER,
                    section = "链接解析",
                    description = "视频文件多久没被使用后可以清理。\n设为 0 表示下次清理任务运行时即可删除。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    visibleWhen = ConfigFieldVisibility("linkParsing.videoDownload.enabled", listOf("true")),
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceRoot",
                    label = "原图缓存目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "保存头像、封面和动态原图的目录。\n这些图片会被 Web 预览和绘图流程复用。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedRoot",
                    label = "渲染图片目录",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "保存绘制完成图片的目录。\n消息发送时会从这里读取最终图片。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.downloadTimeoutSeconds",
                    label = "图片下载超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "单张远程图片最多下载多久。\n支持小数，例如 0.5 表示 0.5 秒。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.maxImageMegabytes",
                    label = "单张图片上限（MB）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "允许读取或下载的单张图片最大大小。\n支持小数，例如 0.5 表示 0.5 MB。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.maxConcurrentDownloads",
                    label = "图片并发下载数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "同一条动态最多同时下载几张图。\n调大可能更快，但也会增加网络和内存压力。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.memoryMaxMegabytes",
                    label = "图片内存上限（MB）",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "图片在内存里最多占用多少空间。\n超过后会自动淘汰较少使用的图片，不影响磁盘缓存。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.memoryMaxEntries",
                    label = "图片内存缓存条数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "内存里最多保留多少张图片。\n设为 0 表示不使用图片内存缓存。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.cleanupCron",
                    label = "图片清理计划",
                    type = ConfigFieldType.TEXT,
                    section = "图片缓存",
                    description = "图片缓存清理任务的运行时间。\n使用 Cron 表达式，默认每天凌晨 4 点执行。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.enabled",
                    label = "清理原图缓存",
                    type = ConfigFieldType.BOOLEAN,
                    section = "图片缓存",
                    description = "定期删除长时间没用过的原图。\n关闭后原图缓存只会继续累积，需手动清理。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.sourceCleanup.maxIdleDays",
                    label = "原图保留天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "原图多久没被使用后可以清理。\n设为 0 表示下次清理任务运行时即可删除。",
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
                    description = "定期删除长时间没用过的绘制图片。\n关闭后渲染结果会一直保留，直到手动清理。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "imageCache.renderedCleanup.maxIdleDays",
                    label = "渲染图片保留天数",
                    type = ConfigFieldType.NUMBER,
                    section = "图片缓存",
                    description = "渲染图片多久没被使用后可以清理。\n设为 0 表示下次清理任务运行时即可删除。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "outboundMedia.enabled",
                    label = "启用外部媒体链接",
                    type = ConfigFieldType.BOOLEAN,
                    section = "出站媒体",
                    description = "把本地图片转换成临时访问链接。\n适合远程 OneBot 或其他消息出口拉取主项目生成的图片。",
                ),
                ConfigFieldSpec(
                    path = "outboundMedia.publicBaseUrl",
                    label = "媒体外部访问地址",
                    type = ConfigFieldType.TEXT,
                    section = "出站媒体",
                    description = "远程消息出口能访问到的主项目地址。\n例如 http://公网IP:2233；留空时会继续使用插件自己的兜底发送方式。",
                ),
                ConfigFieldSpec(
                    path = "outboundMedia.urlTtlSeconds",
                    label = "链接有效期（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "出站媒体",
                    description = "临时媒体链接多久后过期。\n投递重试时会重新生成新的链接。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "outboundMedia.signingSecret",
                    label = "媒体链接签名密钥",
                    type = ConfigFieldType.SECRET,
                    section = "出站媒体",
                    description = "给临时媒体链接签名的密钥。\n留空时首次启动会自动生成；修改后旧链接会立刻失效。",
                    secret = true,
                ),
                ConfigFieldSpec(
                    path = "notifications.enabled",
                    label = "启用系统通知",
                    type = ConfigFieldType.BOOLEAN,
                    section = "系统通知",
                    description = "把运行期重要异常发给管理员。\n启动阶段的插件加载失败不会主动通知，正常运行后出现异常才会通知。",
                ),
                ConfigFieldSpec(
                    path = "notifications.minSeverity",
                    label = "最低通知级别",
                    type = ConfigFieldType.SELECT,
                    section = "系统通知",
                    description = "只发送不低于这个级别的通知。\n恢复类通知通常是 INFO，如果这里设得太高可能收不到恢复消息。",
                    options = notificationSeverityOptions(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "notifications.dedupeSeconds",
                    label = "通知去重时间（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "系统通知",
                    description = "同类通知多久内只发一次。\n设为 0 表示不去重，可能会收到较多重复提醒。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "notifications.routeMonitorIntervalSeconds",
                    label = "Bot 状态检查间隔（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "系统通知",
                    description = "定期检查消息出口账号是否可用。\n用于发现 Bot 掉线和恢复，间隔太短会增加检查频率。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "notifications.adminTargets",
                    label = "系统通知目标",
                    type = ConfigFieldType.JSON,
                    section = "系统通知",
                    description = "接收系统通知的管理员目标。\n可以添加多个用户或群，重要异常会按通知级别发送到这里。",
                    component = "NOTIFICATION_TARGET_TABLE",
                    metadata = mapOf(
                        "example" to """
                            [
                              {"platformId":"qq","targetKind":"USER","externalId":"123456","name":"管理员"},
                              {"platformId":"qq","targetKind":"GROUP","externalId":"654321","accountId":"10001","name":"运维群"}
                            ]
                        """.trimIndent(),
                    ),
                ),
                ConfigFieldSpec(
                    path = "delivery.maxAttempts",
                    label = "最多投递次数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "一条消息失败后最多再试几次。\n达到上限后会标记为最终失败，并出现在消息记录里。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "delivery.retryDelaySeconds",
                    label = "投递重试间隔（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "发送失败后隔多久再试。\n支持小数，例如 0.5 表示 0.5 秒。",
                    min = 0,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.dispatchConcurrency",
                    label = "投递并发数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "同一时间最多发送几条消息。\n调大可以提高吞吐，但也可能增加平台限流风险。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "delivery.lockTtlSeconds",
                    label = "投递占用超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "发送任务卡住多久后重新放回队列。\n用于处理进程中断或发送线程异常退出的情况。",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "delivery.historyRetentionDays",
                    label = "消息记录保留天数",
                    type = ConfigFieldType.NUMBER,
                    section = "消息投递",
                    description = "消息记录保留多久。\n成功或最终失败的记录超过天数后会被清理；0 表示下次清理时删除所有终态记录。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "delivery.cleanupCron",
                    label = "消息记录清理计划",
                    type = ConfigFieldType.TEXT,
                    section = "消息投递",
                    description = "消息记录清理任务的运行时间。\n使用 Cron 表达式，默认每天凌晨 4:30 执行。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.strategy",
                    label = "默认账号路由策略",
                    type = ConfigFieldType.SELECT,
                    section = "消息路由",
                    description = "没有平台单独设置时怎么选发送账号。\n适用于 qq、discord 等真实消息平台的默认发送策略。",
                    options = messageRoutingStrategyOptions(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.primaryAccountId",
                    label = "默认主 Bot 账号",
                    type = ConfigFieldType.TEXT,
                    section = "消息路由",
                    description = "优先使用的 Bot 账号。\n留空时使用当前平台第一个可用账号；轮询也会从它开始。",
                ),
                ConfigFieldSpec(
                    path = "messageRouting.defaultPolicy.failureCooldownSeconds",
                    label = "默认失败冷却（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "消息路由",
                    description = "发送路线失败后多久内先跳过它。\n用于临时避开不可用账号，等待它恢复。",
                    min = 1,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "messageRouting.platformPolicies",
                    label = "平台路由策略",
                    type = ConfigFieldType.JSON,
                    section = "消息路由",
                    description = "给不同消息平台单独设置账号选择方式。\n未配置的平台会使用默认账号路由策略。",
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
                    description = "选择动态图片的整体版式。\n会影响内容排列、边距和视觉风格。",
                    options = DrawLayoutRegistry.options(),
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.themeColors",
                    label = "全局主题色",
                    type = ConfigFieldType.TEXT,
                    section = "绘图",
                    description = "动态图片默认使用的主题色。\n多个颜色用英文分号分隔，例如 #FE65A6;#BFFAFF。",
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.autoTheme",
                    label = "自动获取主题色",
                    type = ConfigFieldType.BOOLEAN,
                    section = "绘图",
                    description = "优先从头像或图片里取颜色。\n提取失败时会退回使用全局主题色。",
                ),
                ConfigFieldSpec(
                    path = "draw.ornament",
                    label = "头部装饰",
                    type = ConfigFieldType.SELECT,
                    section = "绘图",
                    description = "选择动态图片头部右侧显示什么。\n可以显示平台标识、二维码，或不显示装饰。",
                    options = DrawOrnament.entries.map { ConfigFieldOption(it.name, it.name) },
                    required = true,
                ),
                ConfigFieldSpec(
                    path = "draw.width",
                    label = "绘图宽度",
                    type = ConfigFieldType.NUMBER,
                    section = "绘图",
                    description = "生成动态图片的基础宽度。\n太小会显得拥挤，太大可能增加渲染耗时。",
                    min = 320,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "draw.font.text",
                    label = "正文字体",
                    type = ConfigFieldType.TEXT,
                    section = "绘图字体",
                    description = "正文使用的字体。\n可以填系统字体名称，也可以填字体文件路径；留空时使用内置字体。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "draw.font.emoji",
                    label = "Emoji 字体",
                    type = ConfigFieldType.TEXT,
                    section = "绘图字体",
                    description = "Emoji 使用的字体。\n可以填系统 Emoji 字体名称，也可以填字体文件路径；留空时使用内置 Emoji 字体。",
                    restartRequired = true,
                    restartTarget = "主程序",
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.url",
                    label = "插件列表地址",
                    type = ConfigFieldType.TEXT,
                    section = "插件目录",
                    description = "插件下载页面使用的目录地址。\n必须使用 https://；留空表示关闭插件下载与更新功能。",
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.cacheSeconds",
                    label = "插件列表缓存时间（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "插件目录多久刷新一次。\n设为 0 表示每次打开都重新获取。",
                    min = 0,
                    numberKind = ConfigNumberKind.INTEGER,
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.downloadTimeoutSeconds",
                    label = "插件下载超时（秒）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "下载插件和插件目录时最多等待多久。\n支持小数，例如 0.5 表示 0.5 秒。",
                ),
                ConfigFieldSpec(
                    path = "pluginCatalog.maxDownloadMegabytes",
                    label = "插件大小上限（MB）",
                    type = ConfigFieldType.NUMBER,
                    section = "插件目录",
                    description = "允许下载的单个插件最大大小。\n支持小数，例如 0.5 表示 0.5 MB。",
                    min = 0,
                ),
                ConfigFieldSpec(
                    path = "webAdmin.enabled",
                    label = "启用 Web 后台",
                    type = ConfigFieldType.BOOLEAN,
                    section = "Web 后台",
                    description = "控制是否启动内置运维台。\n关闭后需要重启主程序才会停止 Web 后台。",
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.host",
                    label = "Web 后台监听地址",
                    type = ConfigFieldType.TEXT,
                    section = "Web 后台",
                    description = "Web 后台监听的网络地址。\n本机使用通常填 127.0.0.1；对外开放时请确认网络安全。",
                    required = true,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.port",
                    label = "Web 后台端口",
                    type = ConfigFieldType.NUMBER,
                    section = "Web 后台",
                    description = "Web 后台使用的端口。\n访问地址为 http://监听地址:端口/admin。",
                    min = 1,
                    max = 65_535,
                    numberKind = ConfigNumberKind.INTEGER,
                    restartRequired = true,
                    restartTarget = "Web 后台",
                ),
                ConfigFieldSpec(
                    path = "webAdmin.token",
                    label = "Web 后台令牌",
                    type = ConfigFieldType.SECRET,
                    section = "Web 后台",
                    description = "登录 Web 后台使用的访问令牌。\n留空时首次启动会自动生成，并在控制台显示一次。",
                    secret = true,
                ),
                ConfigFieldSpec(
                    path = "webAdmin.logBufferCapacity",
                    label = "日志保留条数",
                    type = ConfigFieldType.NUMBER,
                    section = "Web 后台",
                    description = "日志查看页最多保留多少条实时日志。\n只保留当前进程内的最近日志，不读取历史日志文件。",
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
        PushTemplateRenderer.validateForwardBlockSyntax(config.templates.dynamic)
        PushTemplateRenderer.validateForwardBlockSyntax(config.templates.liveStarted)
        PushTemplateRenderer.validateForwardBlockSyntax(config.templates.liveEnded)
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
        require(config.linkParsing.templates.video.isNotBlank()) { "视频链接模板不能为空" }
        require(config.linkParsing.templates.live.isNotBlank()) { "直播链接模板不能为空" }
        require(config.linkParsing.templates.user.isNotBlank()) { "用户页链接模板不能为空" }
        require(config.linkParsing.templates.fallback.isNotBlank()) { "通用链接模板不能为空" }
        require(config.linkParsing.templates.videoFile.isNotBlank()) { "下载视频模板不能为空" }
        LinkPreviewTemplateRenderer.validate(config.linkParsing.templates.video)
        LinkPreviewTemplateRenderer.validate(config.linkParsing.templates.live)
        LinkPreviewTemplateRenderer.validate(config.linkParsing.templates.user)
        LinkPreviewTemplateRenderer.validate(config.linkParsing.templates.fallback)
        LinkPreviewTemplateRenderer.validate(config.linkParsing.templates.videoFile)
        val videoDownload = config.linkParsing.videoDownload
        require(videoDownload.maxDurationSeconds >= 0) { "视频最大时长不能为负数" }
        require(videoDownload.maxFileMegabytes.isFiniteNumber()) { "视频最大大小必须是有效数字" }
        require(videoDownload.maxFileMegabytes > 0.0) { "视频最大大小必须大于 0 MB" }
        require(videoDownload.cacheRoot.isNotBlank()) { "视频缓存目录不能为空" }
        require(videoDownload.timeoutSeconds > 0.0) { "视频下载超时必须大于 0 秒" }
        require(videoDownload.maxConcurrentDownloads >= 1) { "视频下载并发数至少为 1" }
        require(videoDownload.cleanupMaxIdleDays >= 0) { "视频缓存最大闲置天数不能为负数" }
        require(config.imageCache.sourceRoot.isNotBlank()) { "原图缓存目录不能为空" }
        require(config.imageCache.renderedRoot.isNotBlank()) { "渲染图片目录不能为空" }
        require(config.imageCache.downloadTimeoutSeconds > 0.0) { "图片下载超时必须大于 0 秒" }
        require(config.imageCache.maxImageMegabytes.isFiniteNumber()) { "单张图片大小上限必须是有效数字" }
        require(config.imageCache.maxImageMegabytes > 0.0) { "单张图片大小上限必须大于 0 MB" }
        require(config.imageCache.maxConcurrentDownloads >= 1) { "最大并发下载数至少为 1" }
        require(config.imageCache.memoryMaxMegabytes.isFiniteNumber()) { "图片内存缓存上限必须是有效数字" }
        require(config.imageCache.memoryMaxMegabytes >= 0.0) { "图片内存缓存上限不能为负数" }
        require(config.imageCache.memoryMaxEntries >= 0) { "图片内存缓存最大条数不能为负数" }
        require(config.imageCache.cleanupCron.isNotBlank()) { "清理任务 Cron 不能为空" }
        require(config.imageCache.sourceCleanup.maxIdleDays >= 0) { "原图最大闲置天数不能为负数" }
        require(config.imageCache.renderedCleanup.maxIdleDays >= 0) { "渲染图片最大闲置天数不能为负数" }
        require(config.outboundMedia.urlTtlSeconds >= 1) { "出站媒体链接有效期至少为 1 秒" }
        val outboundMediaBaseUrl = config.outboundMedia.publicBaseUrl.trim()
        if (outboundMediaBaseUrl.isNotBlank()) {
            require(outboundMediaBaseUrl.startsWith("http://", ignoreCase = true) ||
                outboundMediaBaseUrl.startsWith("https://", ignoreCase = true)) {
                "出站媒体外部访问地址必须以 http:// 或 https:// 开头"
            }
            require(config.webAdmin.enabled) { "配置出站媒体外部访问地址时需要启用 Web 后台服务" }
            require(config.outboundMedia.signingSecret.isNotBlank()) { "出站媒体签名密钥不能为空" }
        }
        require(config.notifications.dedupeSeconds >= 0) { "通知去重时间不能为负数" }
        require(config.notifications.routeMonitorIntervalSeconds >= 1) { "Bot 状态检查间隔至少为 1 秒" }
        val notificationTargetKeys = config.notifications.adminTargets
            .filter { it.enabled }
            .mapIndexed { index, target ->
                require(target.platformId.isNotBlank()) { "notifications.adminTargets[$index].platformId 不能为空" }
                require(target.externalId.isNotBlank()) { "notifications.adminTargets[$index].externalId 不能为空" }
                TargetAddress.of(
                    platformId = target.platformId,
                    kind = target.targetKind,
                    externalId = target.externalId,
                    scopeId = target.scopeId,
                    threadId = target.threadId,
                    accountId = target.accountId,
                ).stableValue()
            }
        val duplicatedNotificationTargets = notificationTargetKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicatedNotificationTargets.isEmpty()) {
            "通知目标不能重复"
        }
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
        require(config.pluginCatalog.maxDownloadMegabytes.isFiniteNumber()) { "插件最大下载大小必须是有效数字" }
        require(config.pluginCatalog.maxDownloadMegabytes > 0.0) { "插件最大下载大小必须大于 0 MB" }
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
        if (previous.linkParsing.videoDownload.cacheRoot != next.linkParsing.videoDownload.cacheRoot ||
            previous.linkParsing.videoDownload.cleanupMaxIdleDays != next.linkParsing.videoDownload.cleanupMaxIdleDays
        ) {
            targets += "主程序"
        }
        if (previous.delivery.retryDelaySeconds != next.delivery.retryDelaySeconds ||
            previous.delivery.historyRetentionDays != next.delivery.historyRetentionDays ||
            previous.delivery.cleanupCron != next.delivery.cleanupCron
        ) {
            targets += "主程序"
        }
        if (previous.notifications.routeMonitorIntervalSeconds != next.notifications.routeMonitorIntervalSeconds) {
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

    public fun notificationSeverityOptions(): List<ConfigFieldOption> {
        return listOf(
            ConfigFieldOption(SystemNotificationSeverity.INFO.name, "INFO 信息"),
            ConfigFieldOption(SystemNotificationSeverity.WARN.name, "WARN 警告"),
            ConfigFieldOption(SystemNotificationSeverity.ERROR.name, "ERROR 错误"),
            ConfigFieldOption(SystemNotificationSeverity.CRITICAL.name, "CRITICAL 严重"),
        )
    }

    public fun linkVideoQualityOptions(): List<ConfigFieldOption> {
        return listOf(
            ConfigFieldOption(LinkVideoQuality.AUTO_LOWEST.name, "自动最低"),
            ConfigFieldOption(LinkVideoQuality.P240.name, "最高 240P"),
            ConfigFieldOption(LinkVideoQuality.P360.name, "最高 360P"),
            ConfigFieldOption(LinkVideoQuality.P480.name, "最高 480P"),
            ConfigFieldOption(LinkVideoQuality.P720.name, "最高 720P"),
            ConfigFieldOption(LinkVideoQuality.P1080.name, "最高 1080P"),
            ConfigFieldOption(LinkVideoQuality.AUTO_HIGHEST.name, "自动最高"),
        )
    }

    private fun Double.isFiniteNumber(): Boolean {
        return !isNaN() && !isInfinite()
    }
}
