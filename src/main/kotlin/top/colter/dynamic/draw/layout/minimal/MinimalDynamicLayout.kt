package top.colter.dynamic.draw.layout.minimal

import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import top.colter.dynamic.DrawOrnament
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.LivePayload
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.draw.DrawConfig
import top.colter.dynamic.draw.layout.default.DynamicRenderMode
import top.colter.dynamic.draw.layout.default.component.AuthorContent
import top.colter.dynamic.draw.layout.default.component.minimalAuthorContentStyle
import top.colter.dynamic.draw.layout.default.drawDynamicBlocks
import top.colter.dynamic.draw.layout.default.drawLiveMediaCard
import top.colter.dynamic.draw.layout.default.dynamicTitleFontSize
import top.colter.dynamic.draw.layout.default.orderDynamicBlocksForLayout
import top.colter.dynamic.draw.resource.qrCode
import top.colter.dynamic.util.formatTime
import top.colter.skiko.Dp
import top.colter.skiko.Modifier
import top.colter.skiko.background
import top.colter.skiko.bleed
import top.colter.skiko.border
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.dp
import top.colter.skiko.fillMaxWidth
import top.colter.skiko.height
import top.colter.skiko.layout.Column
import top.colter.skiko.layout.Layout
import top.colter.skiko.layout.Text
import top.colter.skiko.layout.View
import top.colter.skiko.margin
import top.colter.skiko.offset
import top.colter.skiko.padding
import top.colter.skiko.withAlpha
import top.colter.skiko.width

private val scenePadding: Dp = 20.dp
private val contentSpacing: Dp = 20.dp
private val cardPadding: Dp = 14.dp
private val cardBorderWidth: Dp = 3.dp
private val cardRadius: Dp = 15.dp
private val authorLogoBottomSpacing: Dp = 24.dp
private val authorQrBottomSpacing: Dp = 12.dp
private val authorQrTopOffset: Dp = (-10).dp

internal fun renderMinimalDynamic(update: SourceUpdate, config: DrawConfig): Image {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(scenePadding)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    config.theme.backgroundColors,
                )
            )
    ) {
        MinimalDynamicView(update, config)
    }
}

internal fun renderMinimalLive(update: SourceUpdate, config: DrawConfig): Image {
    return View(
        fontRegistry = config.fontRegistry,
        modifier = Modifier()
            .width(config.settings.width.dp)
            .padding(scenePadding)
            .background(
                gradient = Gradient(
                    LayoutAlignment.LEFT_TOP,
                    LayoutAlignment.RIGHT_BOTTOM,
                    config.theme.backgroundColors,
                )
            )
    ) {
        MinimalLiveView(update, config)
    }
}

private fun Layout.MinimalDynamicView(
    update: SourceUpdate,
    config: DrawConfig,
) {
    val payload = update.payload as? DynamicPayload ?: return
    val title = payload.title?.takeIf { it.isNotBlank() }
    val blocks = orderDynamicBlocksForLayout(payload.blocks)
    val hasBlocks = blocks.isNotEmpty()
    val titleFontSize = dynamicTitleFontSize(blocks)
    val avatarBadgeImage = update.publisher.avatarBadgeKey?.let {
        config.platformAssetImage(it, width = 100, height = 100)
    }
    val link = update.link.orEmpty()
    val qrCodeImage = if (config.settings.ornament == DrawOrnament.QRCODE && link.isNotBlank()) {
        qrCode(link, config.theme.primaryColor.withAlpha(1f))
    } else {
        null
    }
    val ornamentImage = when (config.settings.ornament) {
        DrawOrnament.LOGO -> config.platformAssetImage(PlatformDrawAssetKeys.PRIMARY_LOGO)
        DrawOrnament.QRCODE -> config.platformAssetImage(PlatformDrawAssetKeys.TEXT_LOGO)
            ?: config.platformAssetImage(PlatformDrawAssetKeys.PRIMARY_LOGO)
        DrawOrnament.NONE -> null
    }
    val authorStyle = minimalAuthorContentStyle(qrCodeImage != null)
    val authorBottomSpacing = if (qrCodeImage != null) authorQrBottomSpacing else authorLogoBottomSpacing
    val authorModifier = Modifier()
        .fillMaxWidth()
        .height(authorStyle.height)
        .margin(bottom = if (title != null || hasBlocks) authorBottomSpacing else 0.dp)
    if (qrCodeImage != null) {
        authorModifier
            .bleed(right = cardPadding)
            .offset(y = authorQrTopOffset)
    }

    Column(
        modifier = Modifier()
            .fillMaxWidth()
            .padding(cardPadding)
            .background(config.theme.cardColor)
            .border(cardBorderWidth, cardRadius, config.theme.borderColor)
    ) {
        AuthorContent(
            face = config.image(update.publisher.avatar),
            pendant = update.publisher.pendant?.let { config.image(it) },
            ornament = ornamentImage,
            qrCode = qrCodeImage,
            name = update.publisher.name,
            time = update.occurredAtEpochSeconds.formatTime,
            badge = avatarBadgeImage,
            accentColor = config.theme.primaryColor,
            style = authorStyle,
            modifier = authorModifier,
        )

        title?.let {
            Text(
                text = it,
                color = config.theme.textColor,
                fontSize = titleFontSize,
                fontStyle = FontStyle.BOLD,
                maxLinesCount = 2,
                modifier = Modifier().margin(bottom = if (hasBlocks) contentSpacing else 0.dp),
            )
        }

        if (hasBlocks) {
            drawDynamicBlocks(blocks, config, DynamicRenderMode.ROOT)
        }
    }
}

private fun Layout.MinimalLiveView(
    update: SourceUpdate,
    config: DrawConfig,
) {
    val live = update.payload as? LivePayload ?: return
    val cover = live.cover?.let(config::image)
    val liveTime = live.startedAtEpochSeconds ?: update.occurredAtEpochSeconds
    val avatarBadgeImage = update.publisher.avatarBadgeKey?.let {
        config.platformAssetImage(it, width = 100, height = 100)
    }
    val link = update.link.orEmpty()
    val qrCodeImage = if (config.settings.ornament == DrawOrnament.QRCODE && link.isNotBlank()) {
        qrCode(link, config.theme.primaryColor.withAlpha(1f))
    } else {
        null
    }
    val ornamentImage = when (config.settings.ornament) {
        DrawOrnament.LOGO -> config.platformAssetImage(PlatformDrawAssetKeys.PRIMARY_LOGO)
        DrawOrnament.QRCODE -> config.platformAssetImage(PlatformDrawAssetKeys.TEXT_LOGO)
            ?: config.platformAssetImage(PlatformDrawAssetKeys.PRIMARY_LOGO)
        DrawOrnament.NONE -> null
    }
    val authorStyle = minimalAuthorContentStyle(qrCodeImage != null)
    val authorBottomSpacing = if (qrCodeImage != null) authorQrBottomSpacing else authorLogoBottomSpacing
    val authorModifier = Modifier()
        .fillMaxWidth()
        .height(authorStyle.height)
        .margin(bottom = authorBottomSpacing)
    if (qrCodeImage != null) {
        authorModifier
            .bleed(right = cardPadding)
            .offset(y = authorQrTopOffset)
    }

    Column(
        modifier = Modifier()
            .fillMaxWidth()
            .padding(cardPadding)
            .background(config.theme.cardColor)
            .border(cardBorderWidth, cardRadius, config.theme.borderColor)
    ) {
        AuthorContent(
            face = config.image(update.publisher.avatar),
            pendant = update.publisher.pendant?.let { config.image(it) },
            ornament = ornamentImage,
            qrCode = qrCodeImage,
            name = update.publisher.name,
            time = liveTime.formatTime,
            badge = avatarBadgeImage,
            accentColor = config.theme.primaryColor,
            style = authorStyle,
            modifier = authorModifier,
        )

        drawLiveMediaCard(
            live = live,
            cover = cover,
            config = config,
            modifier = Modifier().fillMaxWidth(),
        )
    }
}
