package top.colter.dynamic.admin

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.system.measureTimeMillis
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeEmoji
import top.colter.dynamic.core.data.DynamicContentNodeLink
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicLabelKind
import top.colter.dynamic.core.data.DynamicBlockRole
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicMetric
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.UpdateKey
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.LinkResolver
import top.colter.dynamic.core.link.ParsedLink
import top.colter.dynamic.draw.DefaultDynamicDrawService
import top.colter.dynamic.draw.DefaultLinkPreviewRenderer
import top.colter.dynamic.draw.DynamicDrawService
import top.colter.dynamic.link.LinkPreviewTemplateDiagnostics
import top.colter.dynamic.link.LinkPreviewTemplateRenderResult
import top.colter.dynamic.link.LinkPreviewTemplateRenderer
import top.colter.dynamic.listener.PushTemplateRenderer
import top.colter.dynamic.repository.PublisherRepository

public class AdminTestService(
    private val resolversProvider: () -> List<LinkResolver> = { emptyList() },
    private val configProvider: () -> MainDynamicConfig = { MainDynamicConfig() },
    private val templateRenderer: PushTemplateRenderer = PushTemplateRenderer(),
    private val drawService: DynamicDrawService = DefaultDynamicDrawService(configProvider = configProvider),
    private val drawServiceFactory: ((() -> MainDynamicConfig) -> DynamicDrawService)? = null,
    private val storedPublisherResolver: (PublisherKey) -> Publisher? = { PublisherRepository.findByKey(it) },
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val presetSpecs: List<MockPresetSpec> by lazy { buildPresetSpecs() }

    public fun presets(): AdminTestPresetsResponse {
        return AdminTestPresetsResponse(
            generatedAtEpochMillis = System.currentTimeMillis(),
            defaultPresetId = DEFAULT_PRESET_ID,
            presets = presetSpecs.map { it.toDto() },
        )
    }

    public suspend fun preview(request: AdminTestPreviewRequest): AdminTestPreviewResponse {
        val mode = request.mode.normalizedMode()
        lateinit var response: AdminTestPreviewResponse
        val elapsed = measureTimeMillis {
            response = when (mode) {
                TEST_MODE_MOCK -> previewMock(request)
                TEST_MODE_REAL_LINK -> previewRealLink(request)
                else -> throw IllegalArgumentException("测试模式无效：${request.mode}")
            }
        }
        return response.copy(elapsedMillis = elapsed)
    }

    private suspend fun previewMock(request: AdminTestPreviewRequest): AdminTestPreviewResponse {
        val update = request.customUpdate
            ?: mockUpdate(
                presetId = request.presetId,
                eventTypeInput = request.mockEventType,
                options = request.presetOptions,
            )
        return renderSourceUpdate(
            mode = TEST_MODE_MOCK,
            resolutionType = if (request.customUpdate == null) "MOCK_SOURCE_UPDATE" else "CUSTOM_SOURCE_UPDATE",
            parsedLink = null,
            update = update,
            templateOverride = request.template,
            presetOptions = request.presetOptions,
        )
    }

    private suspend fun previewRealLink(request: AdminTestPreviewRequest): AdminTestPreviewResponse {
        val input = request.link?.trim().orEmpty()
        require(input.isNotBlank()) { "真实请求模式需要填写链接" }

        val resolvers = resolversProvider()
        require(resolvers.isNotEmpty()) { "没有可用的链接解析插件，请确认 Bilibili 插件已启动" }

        val candidate = parseLink(input, resolvers)
            ?: throw IllegalArgumentException("没有插件能识别这个链接")
        val parsedLink = candidate.parsedLink

        return when (val resolution = candidate.resolver.resolveLink(parsedLink)) {
            is LinkResolution.Dynamic -> renderSourceUpdate(
                mode = TEST_MODE_REAL_LINK,
                resolutionType = "DYNAMIC",
                parsedLink = parsedLink,
                update = resolution.update,
                templateOverride = request.template,
                presetOptions = request.presetOptions,
            )
            is LinkResolution.Preview -> renderLinkPreview(
                mode = TEST_MODE_REAL_LINK,
                parsedLink = parsedLink,
                preview = resolution.preview,
                templateOverride = request.template,
                presetOptions = request.presetOptions,
            )
            is LinkResolution.Message -> response(
                mode = TEST_MODE_REAL_LINK,
                status = TEST_STATUS_OK,
                message = "插件直接返回了消息内容",
                parsedLink = parsedLink,
                resolutionType = "MESSAGE",
                template = "",
                templateSource = "PLUGIN_MESSAGE",
                batches = resolution.batches,
            )
            is LinkResolution.Failed -> response(
                mode = TEST_MODE_REAL_LINK,
                status = TEST_STATUS_FAILED,
                message = resolution.reason.ifBlank { "链接解析失败" },
                parsedLink = parsedLink,
                resolutionType = "FAILED",
                template = "",
                templateSource = "NONE",
                batches = emptyList(),
                warnings = listOfNotNull(resolution.cause?.message),
            )
        }
    }

    private suspend fun renderSourceUpdate(
        mode: String,
        resolutionType: String,
        parsedLink: ParsedLink?,
        update: SourceUpdate,
        templateOverride: String?,
        presetOptions: AdminTestPresetOptions = AdminTestPresetOptions(),
    ): AdminTestPreviewResponse {
        val template = templateOverride.normalizedTemplate()
            ?: templateFor(update)
        val templateSource = if (templateOverride.normalizedTemplate() == null) {
            templateSourceFor(update)
        } else {
            "OVERRIDE"
        }
        val warnings = mutableListOf<String>()
        val renderContext = renderContextFor(presetOptions)
        val storedPublisher = storedPublisherResolver(update.publisher.key)
        val drawImage = if (templateRenderer.requiresDraw(template, update)) {
            renderDraw(update, storedPublisher, warnings, renderContext.drawService)
        } else {
            null
        }
        val batches = templateRenderer.render(template, update, drawImage)
        if (batches.isEmpty()) {
            warnings += "模板渲染结果为空"
        }

        return response(
            mode = mode,
            status = if (warnings.isEmpty()) TEST_STATUS_OK else TEST_STATUS_WARN,
            message = if (warnings.isEmpty()) "已按推送模板完成预览" else "已完成预览，但存在警告",
            parsedLink = parsedLink,
            resolutionType = resolutionType,
            template = template,
            templateSource = templateSource,
            batches = batches,
            drawImage = drawImage,
            update = update,
            warnings = warnings,
        )
    }

    private suspend fun renderLinkPreview(
        mode: String,
        parsedLink: ParsedLink,
        preview: LinkPreview,
        templateOverride: String?,
        presetOptions: AdminTestPresetOptions = AdminTestPresetOptions(),
    ): AdminTestPreviewResponse {
        val template = templateOverride.normalizedTemplate()
            ?: configProvider().linkParsing.templates.message
        val templateSource = if (templateOverride.normalizedTemplate() == null) {
            "LINK_PARSING_TEMPLATE"
        } else {
            "OVERRIDE"
        }
        val warnings = mutableListOf<String>()
        val renderContext = renderContextFor(presetOptions)
        val linkTemplateRenderer = LinkPreviewTemplateRenderer(
            DefaultLinkPreviewRenderer(
                configProvider = renderContext.configProvider,
                drawService = renderContext.drawService,
            ),
        )
        val renderResult = runCatching {
            linkTemplateRenderer.renderPreviewResult(template, preview)
        }.getOrElse { error ->
            warnings += "链接预览模板渲染失败：${error.message ?: error::class.simpleName.orEmpty()}"
            LinkPreviewTemplateRenderResult(
                batches = listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText())))),
            )
        }
        val batches = renderResult.batches.ifEmpty {
            warnings += "链接预览模板渲染结果为空，已回退为文本"
            listOf(MessageBatch(listOf(MessageContent.Text(preview.fallbackText()))))
        }
        warnings += renderResult.diagnostics.toAdminWarnings()

        return response(
            mode = mode,
            status = if (warnings.isEmpty()) TEST_STATUS_OK else TEST_STATUS_WARN,
            message = if (warnings.isEmpty()) "已按链接解析模板完成预览" else "已完成预览，但存在警告",
            parsedLink = parsedLink,
            resolutionType = "PREVIEW",
            template = template,
            templateSource = templateSource,
            batches = batches,
            drawImage = renderResult.drawImage,
            preview = preview,
            warnings = warnings,
        )
    }

    private suspend fun renderDraw(
        update: SourceUpdate,
        storedPublisher: Publisher?,
        warnings: MutableList<String>,
        effectiveDrawService: DynamicDrawService = drawService,
    ): MediaRef? {
        return runCatching {
            effectiveDrawService.render(update, storedPublisher)
        }.getOrElse { error ->
            warnings += "绘图失败，已继续渲染文本模板：${error.message ?: error::class.simpleName.orEmpty()}"
            null
        }
    }

    private fun LinkPreviewTemplateDiagnostics.toAdminWarnings(): List<String> {
        return buildList {
            if (missingPlaceholders.isNotEmpty()) {
                add("链接预览模板中 ${missingPlaceholders.sorted().joinToString("、") { "{$it}" }} 没有可用数据，已自动清理")
            }
            if (previewSkippedVideoPlaceholders) {
                add("测试台链接预览不会下载视频，{video}/{size} 只会在真实视频下载阶段渲染")
            }
        }
    }

    private fun renderContextFor(
        options: AdminTestPresetOptions,
    ): AdminTestRenderContext {
        val themeColors = options.themeColors?.trim()?.takeIf { it.isNotBlank() }
            ?: return AdminTestRenderContext(configProvider, drawService)
        val effectiveConfigProvider = {
            val config = configProvider()
            config.copy(
                draw = config.draw.copy(
                    autoTheme = false,
                    themeColors = themeColors,
                ),
            )
        }
        return AdminTestRenderContext(
            configProvider = effectiveConfigProvider,
            drawService = drawServiceFactory?.invoke(effectiveConfigProvider) ?: drawService,
        )
    }

    private suspend fun parseLink(input: String, resolvers: List<LinkResolver>): ParsedLinkCandidate? {
        resolvers.forEach { resolver ->
            val matches = runCatching { resolver.matchesLink(input) }.getOrDefault(false)
            if (!matches) return@forEach
            val parsed = runCatching { resolver.parseLink(input) }.getOrNull() ?: return@forEach
            return ParsedLinkCandidate(resolver, parsed)
        }
        return null
    }

    private fun templateFor(update: SourceUpdate): String {
        val templates = configProvider().templates
        return when (update.eventType) {
            SourceEventType.LIVE_STARTED -> templates.liveStarted
            SourceEventType.LIVE_ENDED -> templates.liveEnded
            else -> templates.dynamic
        }
    }

    private fun templateSourceFor(update: SourceUpdate): String {
        return when (update.eventType) {
            SourceEventType.LIVE_STARTED -> "PUSH_TEMPLATE_LIVE_STARTED"
            SourceEventType.LIVE_ENDED -> "PUSH_TEMPLATE_LIVE_ENDED"
            else -> "PUSH_TEMPLATE_DYNAMIC"
        }
    }

    private fun response(
        mode: String,
        status: String,
        message: String,
        parsedLink: ParsedLink?,
        resolutionType: String,
        template: String,
        templateSource: String,
        batches: List<MessageBatch>,
        drawImage: MediaRef? = null,
        update: SourceUpdate? = null,
        preview: LinkPreview? = null,
        warnings: List<String> = emptyList(),
    ): AdminTestPreviewResponse {
        return AdminTestPreviewResponse(
            mode = mode,
            status = status,
            message = message,
            elapsedMillis = 0,
            parsedLink = parsedLink,
            resolutionType = resolutionType,
            template = template,
            templateSource = templateSource,
            batches = batches,
            drawImage = drawImage,
            media = collectMedia(batches),
            update = update,
            preview = preview,
            warnings = warnings,
        )
    }

    private fun mockUpdate(
        presetId: String?,
        eventTypeInput: String?,
        options: AdminTestPresetOptions,
    ): SourceUpdate {
        val preset = presetId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> presetSpecs.firstOrNull { it.id == id } }
            ?: fallbackPreset(eventTypeInput)
        val now = nowEpochSeconds()
        return preset.build(preset.defaultOptions.mergedWith(options), now)
    }

    private fun fallbackPreset(eventTypeInput: String?): MockPresetSpec {
        val eventType = eventTypeInput.toSourceEventType()
        val fallbackId = when (eventType) {
            SourceEventType.LIVE_STARTED -> "combo-live-start-rich"
            SourceEventType.LIVE_ENDED -> "combo-live-end-rich"
            else -> DEFAULT_PRESET_ID
        }
        return presetSpecs.first { it.id == fallbackId }
    }

    private fun buildPresetSpecs(): List<MockPresetSpec> {
        return listOf(
            MockPresetSpec(
                id = "combo-daily-rich",
                name = "日常图文组合",
                description = "常规正文、话题、链接、三张图片和一张附加活动卡片。",
                group = "真实组合场景",
                eventType = "DYNAMIC",
                recommended = true,
                tags = listOf("常规文本", "表情", "链接", "多图", "附加卡片"),
                defaultOptions = AdminTestPresetOptions(
                    textVariant = "NORMAL",
                    imageCount = 3,
                    imageRatio = "MIXED",
                    includeAdditionalCard = true,
                    themeColors = "#FE65A6;#4F8FE8",
                ),
                build = ::dailyRichUpdate,
            ),
            MockPresetSpec(
                id = "combo-long-grid-video",
                name = "长文九宫格图文",
                description = "长文本、多段换行、九宫格图片和统计数据；视频只在手动勾选时混入。",
                group = "真实组合场景",
                eventType = "DYNAMIC",
                recommended = true,
                tags = listOf("长文本", "九宫格", "统计", "主题"),
                defaultOptions = AdminTestPresetOptions(
                    textVariant = "LONG",
                    imageCount = 9,
                    imageRatio = "SQUARE",
                    themeColors = "#0EA5A4;#2563EB",
                ),
                build = ::longGridVideoUpdate,
            ),
            MockPresetSpec(
                id = "combo-repost-rich",
                name = "复杂转发动态",
                description = "主动态转发说明，原动态为图文内容，可手动混入视频主卡片做压力测试。",
                group = "真实组合场景",
                eventType = "DYNAMIC",
                recommended = true,
                tags = listOf("转发", "原动态", "图片", "长标题"),
                defaultOptions = AdminTestPresetOptions(
                    textVariant = "NORMAL",
                    imageCount = 2,
                    imageRatio = "MIXED",
                    includeRepost = true,
                    themeColors = "#7C3AED;#06B6D4",
                ),
                build = ::richRepostUpdate,
            ),
            MockPresetSpec(
                id = "combo-card-links",
                name = "视频与附加卡片",
                description = "正文、外链、一个视频主卡片和一张附加活动卡片；图片只在手动设置数量时混入。",
                group = "真实组合场景",
                eventType = "DYNAMIC",
                recommended = true,
                tags = listOf("链接", "视频主卡片", "附加卡片", "无图片"),
                defaultOptions = AdminTestPresetOptions(
                    textVariant = "NORMAL",
                    imageCount = 0,
                    includeVideoCard = true,
                    includeAdditionalCard = true,
                    themeColors = "#16A34A;#F59E0B",
                ),
                build = ::cardLinksUpdate,
            ),
            MockPresetSpec(
                id = "combo-missing-media",
                name = "信息缺失组合",
                description = "无头像、无头图、无封面附加卡片、超长标题和默认资源兜底。",
                group = "真实组合场景",
                eventType = "DYNAMIC",
                recommended = true,
                tags = listOf("缺失头像", "缺失封面", "附加卡片", "超长标题", "兜底"),
                defaultOptions = AdminTestPresetOptions(
                    textVariant = "NORMAL",
                    imageCount = 0,
                    includeAdditionalCard = true,
                    themeColors = "#334155;#64748B",
                ),
                build = ::missingMediaUpdate,
            ),
            MockPresetSpec(
                id = "combo-live-start-rich",
                name = "直播开播组合",
                description = "长标题、分区、封面、人气数据和开播时间。",
                group = "直播场景",
                eventType = "LIVE_STARTED",
                recommended = true,
                tags = listOf("直播", "开播", "封面", "长标题", "人气"),
                defaultOptions = AdminTestPresetOptions(themeColors = "#E11D48;#F97316"),
                build = ::liveStartedUpdate,
            ),
            MockPresetSpec(
                id = "combo-live-end-rich",
                name = "直播结束组合",
                description = "下播状态、开始/结束时间、时长和结束标题。",
                group = "直播场景",
                eventType = "LIVE_ENDED",
                recommended = true,
                tags = listOf("直播", "下播", "时长", "状态切换"),
                defaultOptions = AdminTestPresetOptions(themeColors = "#475569;#0F172A"),
                build = ::liveEndedUpdate,
            ),
            MockPresetSpec(
                id = "boundary-short-text",
                name = "极短文本",
                description = "只有极短正文和一个链接，用于检查紧凑高度。",
                group = "边界场景",
                eventType = "DYNAMIC",
                tags = listOf("短文本", "紧凑"),
                defaultOptions = AdminTestPresetOptions(textVariant = "SHORT", imageCount = 0),
                build = ::shortTextUpdate,
            ),
            MockPresetSpec(
                id = "boundary-long-text",
                name = "极长文本",
                description = "超长正文、多段换行和链接，专门检查文本排版。",
                group = "边界场景",
                eventType = "DYNAMIC",
                tags = listOf("超长文本", "换行", "链接"),
                defaultOptions = AdminTestPresetOptions(textVariant = "EXTREME", imageCount = 0),
                build = ::longTextOnlyUpdate,
            ),
            MockPresetSpec(
                id = "boundary-nine-grid",
                name = "纯九宫格",
                description = "少量正文配九张图片，检查宫格间距和裁切。",
                group = "边界场景",
                eventType = "DYNAMIC",
                tags = listOf("九宫格", "图片裁切"),
                defaultOptions = AdminTestPresetOptions(textVariant = "SHORT", imageCount = 9, imageRatio = "MIXED"),
                build = ::nineGridUpdate,
            ),
            MockPresetSpec(
                id = "boundary-card-only",
                name = "纯视频卡片",
                description = "正文极少，主体只有一个视频主卡片；文章和附加卡片需手动勾选。",
                group = "边界场景",
                eventType = "DYNAMIC",
                tags = listOf("视频主卡片", "无图片"),
                defaultOptions = AdminTestPresetOptions(includeVideoCard = true, imageCount = 0),
                build = ::cardOnlyUpdate,
            ),
            MockPresetSpec(
                id = "boundary-repost-only",
                name = "纯转发",
                description = "主动态只有转发说明，原动态承担主要内容。",
                group = "边界场景",
                eventType = "DYNAMIC",
                tags = listOf("转发", "原动态"),
                defaultOptions = AdminTestPresetOptions(includeRepost = true, imageCount = 0),
                build = ::repostOnlyUpdate,
            ),
        )
    }

    private fun dailyRichUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "combo-daily-rich",
            now = now,
            publisher = mockPublisher(name = "日常测试 UP"),
            title = "周末照片整理",
            labels = listOf(DynamicLabel("日常", DynamicLabelKind.TAG), DynamicLabel("组合", DynamicLabelKind.BADGE)),
            blocks = buildList {
                add(richTextBlock(textFor("把周末拍的几张照片整理了一下，顺手记录一下配色、构图和现场的小细节。", options.textVariant)))
                add(imageGridBlock(options.imageCount ?: 3, options.imageRatio ?: "MIXED"))
                if (options.includeVideoCard == true) add(videoCardBlock())
                if (options.includeArticleCard == true) add(articleCardBlock())
                if (options.includeAdditionalCard == true) add(additionalCardBlock())
            },
            metrics = commonMetrics(),
        )
    }

    private fun longGridVideoUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "combo-long-grid-video",
            now = now,
            publisher = mockPublisher(name = "长文摄影 UP"),
            title = "一组九宫格照片的长文记录",
            labels = listOf(DynamicLabel("长文"), DynamicLabel("九宫格", DynamicLabelKind.TAG)),
            blocks = buildList {
                add(richTextBlock(textFor(longParagraph(), options.textVariant)))
                add(imageGridBlock(options.imageCount ?: 9, options.imageRatio ?: "SQUARE"))
                if (options.includeVideoCard == true) add(videoCardBlock(title = "九宫格背后的拍摄记录：从构图到后期完整流程"))
                if (options.includeArticleCard == true) add(articleCardBlock())
                if (options.includeAdditionalCard == true) add(additionalCardBlock())
            },
            metrics = commonMetrics(like = "8.8万", comment = "4096", share = "1200"),
        )
    }

    private fun richRepostUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        val embedded = dynamicUpdate(
            presetId = "combo-repost-rich-origin",
            now = now - 3600,
            publisher = mockPublisher(name = "原动态作者"),
            title = "原动态标题",
            labels = listOf(DynamicLabel("原动态")),
            blocks = buildList {
                add(richTextBlock("原动态是一组图文整理，主要用来检查转发卡片里的正文、图片和作者信息。"))
                if ((options.imageCount ?: 2) > 0) {
                    add(imageGridBlock((options.imageCount ?: 2).coerceAtLeast(1), options.imageRatio ?: "MIXED"))
                }
                if (options.includeVideoCard == true) {
                    add(videoCardBlock(title = "原动态视频卡片：标题长度也稍微长一点"))
                }
                if (options.includeAdditionalCard == true) add(additionalCardBlock())
            },
            metrics = commonMetrics(like = "1.2万", comment = "321", share = "98"),
            link = "https://t.bilibili.com/mock-origin",
        )
        return dynamicUpdate(
            presetId = "combo-repost-rich",
            now = now,
            publisher = mockPublisher(name = "转发测试 UP"),
            title = "复杂转发测试",
            labels = listOf(DynamicLabel("转发", DynamicLabelKind.TAG)),
            blocks = listOf(
                richTextBlock(textFor("转发补充一句：这个场景用来检查主动态和原动态组合后的层级、间距、头像和图片排版。", options.textVariant)),
                RepostBlock(
                    referenceKind = DynamicReferenceKind.REPOST,
                    key = embedded.key,
                    link = embedded.link,
                    embedded = embedded,
                ),
            ),
            metrics = commonMetrics(like = "3.1万", comment = "777", share = "512"),
        )
    }

    private fun cardLinksUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "combo-card-links",
            now = now,
            publisher = mockPublisher(name = "卡片链接 UP"),
            title = "视频与附加卡片组合",
            labels = listOf(DynamicLabel("链接合集")),
            blocks = buildList {
                add(
                    TextBlock(
                        DynamicContent(
                            listOf(
                                DynamicContentNodeText("新视频已经发出，这里顺带放一个项目链接和一个活动链接，检查正文链接、主卡片和附加卡片的展示层级。"),
                                DynamicContentNodeLink("项目主页", url = "https://github.com/Colter23/dynamic-bot"),
                                DynamicContentNodeText(" "),
                                DynamicContentNodeLink("Bilibili 链接", url = "https://www.bilibili.com/video/BV1xx411c7mD"),
                            )
                        )
                    )
                )
                if ((options.imageCount ?: 0) > 0) add(imageGridBlock(options.imageCount ?: 0, options.imageRatio ?: "WIDE"))
                if (options.includeVideoCard == true) add(videoCardBlock())
                if (options.includeArticleCard == true) add(articleCardBlock())
                if (options.includeAdditionalCard == true) add(additionalCardBlock())
            },
            metrics = commonMetrics(),
        )
    }

    private fun missingMediaUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "combo-missing-media",
            now = now,
            publisher = mockPublisher(
                name = "缺失信息测试 UP",
                avatar = "",
                banner = null,
            ),
            title = "信息缺失组合",
            labels = listOf(DynamicLabel("兜底", DynamicLabelKind.WARNING)),
            blocks = buildList {
                add(richTextBlock("这个预设故意缺少头像、头图和卡片封面，同时放一个很长的附加卡片标题，用来检查占位图、默认主题和文本截断。"))
                if (options.includeAdditionalCard != false) {
                    add(
                        additionalCardBlock(
                            title = "这是一个非常非常非常长的无封面附加卡片标题，用于测试卡片标题换行、压缩、截断和整体布局稳定性",
                            cover = null,
                        )
                    )
                }
                if (options.includeArticleCard == true) add(articleCardBlock(cover = null))
                if (options.includeVideoCard == true) add(videoCardBlock(cover = null))
            },
            metrics = commonMetrics(like = "0", comment = "0", share = "0"),
        )
    }

    private fun liveStartedUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return liveUpdate(
            presetId = "combo-live-start-rich",
            now = now,
            eventType = SourceEventType.LIVE_STARTED,
            publisher = mockPublisher(name = "直播测试 UP"),
            title = "今晚 20:00 超长标题直播测试：聊天、游戏、绘图布局和封面显示一起看",
            cover = MockMediaAssets.cover(1),
        )
    }

    private fun liveEndedUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return liveUpdate(
            presetId = "combo-live-end-rich",
            now = now,
            eventType = SourceEventType.LIVE_ENDED,
            publisher = mockPublisher(name = "直播测试 UP"),
            title = "今晚 20:00 超长标题直播测试：聊天、游戏、绘图布局和封面显示一起看",
            cover = MockMediaAssets.cover(1),
        )
    }

    private fun shortTextUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "boundary-short-text",
            now = now,
            publisher = mockPublisher(name = "短文本 UP"),
            blocks = listOf(richTextBlock(textFor("好。", options.textVariant))),
            metrics = commonMetrics(like = "12", comment = "3", share = "1"),
        )
    }

    private fun longTextOnlyUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "boundary-long-text",
            now = now,
            publisher = mockPublisher(name = "长文本 UP"),
            title = "极长文本边界",
            labels = listOf(DynamicLabel("长文本")),
            blocks = listOf(richTextBlock(textFor(longParagraph(), options.textVariant ?: "EXTREME"))),
            metrics = commonMetrics(like = "9999", comment = "888", share = "77"),
        )
    }

    private fun nineGridUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "boundary-nine-grid",
            now = now,
            publisher = mockPublisher(name = "九宫格 UP"),
            blocks = listOf(
                richTextBlock("九宫格边界测试：横图、竖图、方图混合。"),
                imageGridBlock(options.imageCount ?: 9, options.imageRatio ?: "MIXED"),
            ),
            metrics = commonMetrics(),
        )
    }

    private fun cardOnlyUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        return dynamicUpdate(
            presetId = "boundary-card-only",
            now = now,
            publisher = mockPublisher(name = "视频卡片 UP"),
            title = "只有主卡片的动态",
            blocks = buildList {
                add(richTextBlock("这条动态正文很短，主要用于检查只有主卡片时的高度、间距和封面裁切。"))
                if (options.includeVideoCard == true) add(videoCardBlock())
                if (options.includeArticleCard == true) add(articleCardBlock())
                if (options.includeAdditionalCard == true) add(additionalCardBlock())
            },
            metrics = commonMetrics(),
        )
    }

    private fun repostOnlyUpdate(options: AdminTestPresetOptions, now: Long): SourceUpdate {
        val embedded = dynamicUpdate(
            presetId = "boundary-repost-origin",
            now = now - 1200,
            publisher = mockPublisher(name = "被转发 UP"),
            blocks = listOf(
                richTextBlock("原动态承载主要内容，主动态只有一句非常短的转发说明。"),
                imageGridBlock(4, "SQUARE"),
            ),
            metrics = commonMetrics(),
        )
        return dynamicUpdate(
            presetId = "boundary-repost-only",
            now = now,
            publisher = mockPublisher(name = "纯转发 UP"),
            blocks = listOf(
                richTextBlock("转发"),
                RepostBlock(
                    referenceKind = DynamicReferenceKind.REPOST,
                    key = embedded.key,
                    link = embedded.link,
                    embedded = embedded,
                ),
            ),
            metrics = commonMetrics(like = "42", comment = "5", share = "8"),
        )
    }

    private fun dynamicUpdate(
        presetId: String,
        now: Long,
        publisher: PublisherInfo,
        blocks: List<top.colter.dynamic.core.data.DynamicBlock>,
        title: String? = null,
        labels: List<DynamicLabel> = emptyList(),
        metrics: List<DynamicMetric> = commonMetrics(),
        link: String = "https://t.bilibili.com/$presetId",
    ): SourceUpdate {
        return SourceUpdate(
            key = UpdateKey.of(
                publisherKey = publisher.key,
                eventType = SourceEventType.DYNAMIC_CREATED,
                externalId = presetId,
            ),
            publisher = publisher,
            occurredAtEpochSeconds = now,
            observedAtEpochSeconds = now,
            link = link,
            payload = DynamicPayload(
                labels = labels,
                title = title,
                blocks = blocks,
                metrics = metrics,
            ),
        )
    }

    private fun liveUpdate(
        presetId: String,
        now: Long,
        eventType: SourceEventType,
        publisher: PublisherInfo,
        title: String,
        cover: String?,
    ): SourceUpdate {
        val startedAt = now - 7240
        return SourceUpdate(
            key = UpdateKey.of(
                publisherKey = publisher.key,
                eventType = eventType,
                externalId = presetId,
            ),
            publisher = publisher,
            occurredAtEpochSeconds = now,
            observedAtEpochSeconds = now,
            link = "https://live.bilibili.com/2233",
            payload = LivePayload(
                roomId = "2233",
                title = title,
                area = "虚拟主播 / 绘图测试分区",
                cover = cover?.let { MediaRef(it, MediaKind.COVER) },
                status = if (eventType == SourceEventType.LIVE_ENDED) LiveStatus.CLOSE else LiveStatus.OPEN,
                statusText = if (eventType == SourceEventType.LIVE_ENDED) "已下播" else "直播中",
                previousStatus = if (eventType == SourceEventType.LIVE_ENDED) LiveStatus.OPEN else LiveStatus.CLOSE,
                startedAtEpochSeconds = startedAt,
                endedAtEpochSeconds = if (eventType == SourceEventType.LIVE_ENDED) now else null,
                metrics = listOf(DynamicMetric("online", display = "2.2万")),
            ),
        )
    }

    private fun mockPublisher(
        name: String,
        avatar: String = MockMediaAssets.avatar(),
        banner: String? = MockMediaAssets.banner(),
    ): PublisherInfo {
        return PublisherInfo(
            key = PublisherKey.of(
                platformId = "bilibili",
                kind = PublisherKind.USER,
                externalId = MOCK_UID,
            ),
            name = name,
            avatar = MediaRef(avatar, MediaKind.AVATAR),
            banner = banner?.let { MediaRef(it, MediaKind.COVER) },
        )
    }

    private fun richTextBlock(text: String): TextBlock {
        return TextBlock(
            DynamicContent(
                listOf(
                    DynamicContentNodeText(text),
                    DynamicContentNodeText("\n"),
                    DynamicContentNodeEmoji("✨"),
                    DynamicContentNodeText(" #动态Bot测试# "),
                    DynamicContentNodeLink("项目链接", url = "https://github.com/Colter23/dynamic-bot"),
                )
            )
        )
    }

    private fun imageGridBlock(count: Int, ratio: String): ImageGridBlock {
        val safeCount = count.coerceIn(0, 9)
        return ImageGridBlock(
            images = (0 until safeCount).map { index ->
                val (width, height) = imageSize(index, ratio)
                ImageItem(
                    image = MediaRef(MockMediaAssets.image(index, width, height), MediaKind.IMAGE),
                    width = width,
                    height = height,
                    alt = "mock 图片 ${index + 1}",
                )
            }
        )
    }

    private fun imageSize(index: Int, ratio: String): Pair<Int, Int> {
        return when (ratio.uppercase()) {
            "SQUARE" -> 1080 to 1080
            "VERTICAL" -> 720 to 1280
            "WIDE" -> 1280 to 720
            "MIXED" -> when (index % 3) {
                0 -> 1280 to 720
                1 -> 1080 to 1080
                else -> 720 to 1280
            }
            else -> 1280 to 720
        }
    }

    private fun videoCardBlock(
        title: String = "新视频：从构思到发布的完整记录",
        cover: String? = MockMediaAssets.cover(0),
    ): MediaCardBlock {
        return MediaCardBlock(
            style = MediaCardStyle.LARGE,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.VIDEO,
                sourceKind = "bilibili.video",
                id = "BV1xx411c7mD",
                title = title,
                description = "记录一次内容制作流程，用于检查视频主卡片封面、标题、描述、时长和统计数据。",
                badge = "视频",
                cover = cover?.let { MediaRef(it, MediaKind.COVER) },
                durationSeconds = 205,
                metrics = listOf(
                    DynamicMetric("play", display = "12.3万"),
                    DynamicMetric("danmaku", display = "1024"),
                    DynamicMetric("like", display = "2.3万"),
                ),
                link = "https://www.bilibili.com/video/BV1xx411c7mD",
            ),
        )
    }

    private fun articleCardBlock(
        title: String = "专栏：动态排版与内容整理笔记",
        cover: String? = MockMediaAssets.cover(2),
    ): MediaCardBlock {
        return MediaCardBlock(
            style = MediaCardStyle.SMALL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.ARTICLE,
                sourceKind = "bilibili.article",
                id = "cv22334455",
                title = title,
                description = "文章卡片描述通常更偏文本，用来检查主卡片里的标题、摘要和封面排版。",
                badge = "专栏",
                cover = cover?.let { MediaRef(it, MediaKind.COVER) },
                metrics = listOf(DynamicMetric("read", display = "4.5万")),
                link = "https://www.bilibili.com/read/cv22334455",
            ),
        )
    }

    private fun additionalCardBlock(
        title: String = "活动预约与附加链接卡片",
        cover: String? = MockMediaAssets.square(0),
    ): MediaCardBlock {
        return MediaCardBlock(
            style = MediaCardStyle.MINI,
            role = DynamicBlockRole.ADDITIONAL,
            card = DynamicMediaCard(
                kind = DynamicMediaCardKind.LINK,
                sourceKind = "bilibili.additional.common:official_activity",
                id = "additional-link",
                title = title,
                description = "用于检查 Bilibili 附加卡片在正文、图片或主卡片之后的排列。",
                badge = "活动",
                cover = cover?.let { MediaRef(it, MediaKind.COVER) },
                coverRatio = 1f,
                link = "https://www.bilibili.com/blackboard/activity-test.html",
            ),
        )
    }

    private fun textFor(base: String, variant: String?): String {
        return when (variant?.trim()?.uppercase()) {
            "SHORT" -> "好。"
            "LONG" -> longParagraph()
            "EXTREME" -> listOf(longParagraph(), longParagraph(), longParagraph()).joinToString("\n\n")
            else -> base
        }
    }

    private fun longParagraph(): String {
        return """
            这是一段较长的动态正文，用来模拟真实用户会写的多段内容。第一段通常会说明事情背景、补充几个细节，还可能穿插一些链接、话题和表情。

            第二段会继续展开，长度足够让绘图布局进入换行、分页或压缩状态。这里希望看到头像区域、正文区域、图片区域和卡片区域组合之后是否仍然清晰。

            第三段故意加入更长的句子：当一条动态包含九宫格图片、统计信息和较长正文，或手动混入视频主卡片做压力测试时，卡片高度、文字行距、底部链接二维码或平台标识都应该保持稳定。
        """.trimIndent()
    }

    private fun commonMetrics(
        like: String = "2.3万",
        comment: String = "256",
        share: String = "88",
    ): List<DynamicMetric> {
        return listOf(
            DynamicMetric("like", display = like),
            DynamicMetric("comment", display = comment),
            DynamicMetric("share", display = share),
        )
    }

    private fun String?.toSourceEventType(): SourceEventType {
        return when (this?.trim().orEmpty().uppercase()) {
            "",
            "DYNAMIC",
            "DYNAMIC_CREATED",
            "DYNAMIC.CREATED" -> SourceEventType.DYNAMIC_CREATED
            "LIVE_STARTED",
            "LIVE.STARTED" -> SourceEventType.LIVE_STARTED
            "LIVE_ENDED",
            "LIVE.ENDED" -> SourceEventType.LIVE_ENDED
            else -> throw IllegalArgumentException("mock 事件类型无效：$this")
        }
    }

    private fun String.normalizedMode(): String {
        return when (trim().uppercase()) {
            "",
            TEST_MODE_MOCK -> TEST_MODE_MOCK
            TEST_MODE_REAL_LINK,
            "REAL",
            "BILIBILI" -> TEST_MODE_REAL_LINK
            else -> trim().uppercase()
        }
    }

    private fun String?.normalizedTemplate(): String? {
        return this?.takeIf { it.isNotBlank() }
    }

    private fun LinkPreview.fallbackText(): String {
        return buildString {
            append(title.ifBlank { url })
            description.trim().takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
            url.takeIf { it.isNotBlank() }?.let {
                appendLine()
                append(it)
            }
        }
    }

    private fun collectMedia(batches: List<MessageBatch>): List<AdminTestMediaDto> {
        val media = linkedMapOf<String, AdminTestMediaDto>()
        fun add(ref: MediaRef, source: String) {
            if (ref.uri.isBlank()) return
            media.putIfAbsent(
                "${ref.kind.name}\u0000${ref.uri}",
                AdminTestMediaDto(
                    kind = ref.kind.name,
                    uri = ref.uri,
                    source = source,
                    alt = ref.alt,
                ),
            )
        }
        fun visit(content: MessageContent, source: String) {
            when (content) {
                is MessageContent.Image -> add(content.image, source)
                is MessageContent.Video -> add(content.video, source)
                is MessageContent.Audio -> add(content.audio, source)
                is MessageContent.Forward -> content.nodes.forEachIndexed { nodeIndex, node ->
                    node.batches.forEachIndexed { batchIndex, batch ->
                        batch.content.forEach { visit(it, "$source.forward[$nodeIndex].batch[$batchIndex]") }
                    }
                }
                else -> Unit
            }
        }
        batches.forEachIndexed { batchIndex, batch ->
            batch.content.forEach { visit(it, "batch[$batchIndex]") }
        }
        return media.values.toList()
    }

    private data class ParsedLinkCandidate(
        val resolver: LinkResolver,
        val parsedLink: ParsedLink,
    )

    private data class AdminTestRenderContext(
        val configProvider: () -> MainDynamicConfig,
        val drawService: DynamicDrawService,
    )

    private data class MockPresetSpec(
        val id: String,
        val name: String,
        val description: String,
        val group: String,
        val eventType: String,
        val recommended: Boolean = false,
        val tags: List<String> = emptyList(),
        val defaultOptions: AdminTestPresetOptions = AdminTestPresetOptions(),
        val build: (AdminTestPresetOptions, Long) -> SourceUpdate,
    ) {
        fun toDto(): AdminTestPresetDto {
            return AdminTestPresetDto(
                id = id,
                name = name,
                description = description,
                group = group,
                eventType = eventType,
                recommended = recommended,
                tags = tags,
                defaultOptions = defaultOptions,
            )
        }
    }

    private fun AdminTestPresetOptions.mergedWith(
        override: AdminTestPresetOptions,
    ): AdminTestPresetOptions {
        return AdminTestPresetOptions(
            textVariant = override.textVariant ?: textVariant,
            imageCount = override.imageCount ?: imageCount,
            imageRatio = override.imageRatio ?: imageRatio,
            includeVideoCard = override.includeVideoCard ?: includeVideoCard,
            includeArticleCard = override.includeArticleCard ?: includeArticleCard,
            includeAdditionalCard = override.includeAdditionalCard ?: includeAdditionalCard,
            includeRepost = override.includeRepost ?: includeRepost,
            themeColors = override.themeColors ?: themeColors,
        )
    }

    private companion object {
        private const val DEFAULT_PRESET_ID: String = "combo-daily-rich"
        private const val MOCK_UID: String = "000000000"
        private const val TEST_MODE_MOCK: String = "MOCK"
        private const val TEST_MODE_REAL_LINK: String = "REAL_LINK"
        private const val TEST_STATUS_OK: String = "OK"
        private const val TEST_STATUS_WARN: String = "WARN"
        private const val TEST_STATUS_FAILED: String = "FAILED"
    }
}

private object MockMediaAssets {
    private val root: Path = Paths.get("data", "mock-assets")
    private val palettes: List<MockAssetPalette> = listOf(
        MockAssetPalette(Color(0xFE65A6), Color(0x4F8FE8), Color(0xFFFFFF)),
        MockAssetPalette(Color(0x0EA5A4), Color(0x2563EB), Color(0xE0F2FE)),
        MockAssetPalette(Color(0x16A34A), Color(0xF59E0B), Color(0xFEF3C7)),
        MockAssetPalette(Color(0x7C3AED), Color(0x06B6D4), Color(0xF5F3FF)),
        MockAssetPalette(Color(0x334155), Color(0x64748B), Color(0xF8FAFC)),
    )

    fun avatar(): String {
        return ensurePng(
            name = "avatar.png",
            width = 256,
            height = 256,
            palette = palettes[0],
            label = "UID",
        )
    }

    fun banner(): String {
        return ensurePng(
            name = "banner.png",
            width = 1280,
            height = 320,
            palette = palettes[1],
            label = "Mock Banner",
        )
    }

    fun image(index: Int, width: Int, height: Int): String {
        return ensurePng(
            name = "image-${index + 1}-${width}x$height.png",
            width = width,
            height = height,
            palette = palettes[index.floorMod(palettes.size)],
            label = "Mock Image ${index + 1}",
        )
    }

    fun cover(index: Int): String {
        return ensurePng(
            name = "cover-${index + 1}.png",
            width = 1280,
            height = 720,
            palette = palettes[(index + 2).floorMod(palettes.size)],
            label = "Mock Cover ${index + 1}",
        )
    }

    fun square(index: Int): String {
        return ensurePng(
            name = "square-${index + 1}.png",
            width = 720,
            height = 720,
            palette = palettes[(index + 3).floorMod(palettes.size)],
            label = "Mock Card",
        )
    }

    private fun ensurePng(
        name: String,
        width: Int,
        height: Int,
        palette: MockAssetPalette,
        label: String,
    ): String {
        val path = root.resolve(name)
        if (!Files.isRegularFile(path)) {
            synchronized(this) {
                if (!Files.isRegularFile(path)) {
                    Files.createDirectories(root)
                    ImageIO.write(render(width, height, palette, label), "png", path.toFile())
                }
            }
        }
        return path.toString().replace('\\', '/')
    }

    private fun render(
        width: Int,
        height: Int,
        palette: MockAssetPalette,
        label: String,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.paint = GradientPaint(0f, 0f, palette.start, width.toFloat(), height.toFloat(), palette.end)
            graphics.fillRect(0, 0, width, height)

            val unit = width.coerceAtMost(height)
            graphics.color = Color(255, 255, 255, 42)
            repeat(6) { index ->
                val size = max(unit / 5, 64) + index * max(unit / 18, 18)
                val x = width - size - index * max(unit / 20, 14)
                val y = index * max(unit / 18, 18) - size / 3
                graphics.fillOval(x, y, size, size)
            }
            graphics.color = Color(255, 255, 255, 60)
            graphics.fillRoundRect(width / 14, height / 9, width / 3, max(height / 10, 32), 28, 28)
            graphics.color = palette.accent
            graphics.font = Font(Font.SANS_SERIF, Font.BOLD, max(unit / 12, 24))
            drawCentered(graphics, label, width, height / 2 - max(unit / 18, 16))
            graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, max(unit / 22, 18))
            drawCentered(graphics, "${width}x$height", width, height / 2 + max(unit / 14, 20))
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun drawCentered(
        graphics: java.awt.Graphics2D,
        text: String,
        width: Int,
        baseline: Int,
    ) {
        val metrics = graphics.fontMetrics
        val x = (width - metrics.stringWidth(text)) / 2
        graphics.drawString(text, x, baseline)
    }

    private fun Int.floorMod(mod: Int): Int {
        return Math.floorMod(this, mod)
    }

    private data class MockAssetPalette(
        val start: Color,
        val end: Color,
        val accent: Color,
    )
}
