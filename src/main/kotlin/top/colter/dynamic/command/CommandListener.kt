package top.colter.dynamic.command

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.MainConfigForms
import top.colter.dynamic.CommandReceiveMode
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.core.command.CommandPermissionResolver
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.IncomingProcessingStage
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.TextMatchMode
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.incoming.IncomingBotAccountSelector
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.PublisherFollowPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherLookupPlugin
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.draw.DefaultPublisherThemeInitializer
import top.colter.dynamic.draw.PublisherDrawThemeService
import top.colter.dynamic.draw.PublisherThemeInitializer
import top.colter.dynamic.repository.DynamicFilterRuleRepository
import top.colter.dynamic.repository.IncomingMessageAuditRepository
import top.colter.dynamic.repository.IncomingProcessingWriteRequest
import top.colter.dynamic.repository.MessageDeliveryRepository
import top.colter.dynamic.repository.PublisherDrawThemeRepository
import top.colter.dynamic.repository.PublisherRepository
import top.colter.dynamic.repository.SubscriberRepository
import top.colter.dynamic.repository.SubscriptionRepository
import top.colter.dynamic.link.LinkParseService
import top.colter.dynamic.link.LinkParseConfigCommandHandler
import top.colter.dynamic.link.LinkParseCommandHandler
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.message.RENDER_VARIANT_MANUAL_FORWARD
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

private val commandListenerLogger = loggerFor<CommandListener>()

public class CommandListener(
    private val publisherLookupResolver: (String) -> PublisherLookupPlugin?,
    private val publisherFollowResolver: (String) -> PublisherFollowPlugin? = { platform ->
        publisherLookupResolver(platform) as? PublisherFollowPlugin
    },
    private val publisherLoginResolver: (String) -> PublisherLoginProvider? = { platform ->
        publisherLookupResolver(platform) as? PublisherLoginProvider
    },
    private val linkParseService: LinkParseService = LinkParseService(resolversProvider = { emptyList() }),
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val stopRequester: ((String) -> Unit)? = null,
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val publisherDrawThemeService: PublisherDrawThemeService = PublisherDrawThemeService(),
    private val incomingBotAccountSelector: IncomingBotAccountSelector = IncomingBotAccountSelector(),
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(),
    publisherThemeInitializer: PublisherThemeInitializer? = null,
    private val incomingProcessingRecorder: (IncomingProcessingWriteRequest) -> Boolean = {
        IncomingMessageAuditRepository.recordProcessing(it)
    },
) : Listener<CommandEvent> {
    private companion object {
        private const val MAIN_OWNER: String = "main"
    }

    private val fixedConfig: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID, MainConfigForms.migrations) {
            MainDynamicConfig()
        }
    }
    private val runtimeConfigProvider: () -> MainDynamicConfig = configProvider ?: { fixedConfig }
    private val runtimeConfig: MainDynamicConfig
        get() = runtimeConfigProvider()
    private val runtimePublisherThemeInitializer: PublisherThemeInitializer by lazy {
        publisherThemeInitializer ?: DefaultPublisherThemeInitializer(configProvider = runtimeConfigProvider)
    }

    private val commandPrefix: String
        get() = runtimeConfig.command.prefix
    private val commandPrefixProvider: () -> String = { commandPrefix }

    init {
        registerBuiltins()
    }

    override suspend fun onMessage(event: CommandEvent) {
        val parsed = CommandParser.parse(event.rawText, commandPrefix) ?: return
        val tokens = parsed.tokens
        if (!shouldAcceptCommand(event)) {
            recordCommandProcessing(
                event = event,
                stage = IncomingProcessingStage.COMMAND_PARSE,
                handlerId = "command-receive-mode",
                result = IncomingProcessingResult.IGNORED,
                commandPath = tokens.joinToString(" "),
                errorMessage = "命令接收模式忽略",
            )
            return
        }

        val match = commandRegistry.match(tokens)
        if (match == null) {
            recordCommandProcessing(
                event = event,
                stage = IncomingProcessingStage.COMMAND_PARSE,
                handlerId = "command-registry",
                result = IncomingProcessingResult.FAILED,
                commandPath = tokens.joinToString(" "),
                errorMessage = "未知命令",
            )
            reply(event, CommandExecutionResult.failed("未知命令：${tokens.joinToString(" ")}"))
            return
        }
        val commandPath = match.matchedPath.joinToString(" ")
        recordCommandProcessing(
            event = event,
            stage = IncomingProcessingStage.COMMAND_PARSE,
            handlerId = "command-registry",
            result = IncomingProcessingResult.MATCHED,
            commandPath = commandPath,
        )

        val commandConfig = runtimeConfig.command
        val defaultRole = if (commandConfig.requirePermissionRule) CommandRole.NONE else CommandRole.USER
        val role = CommandPermissionResolver(commandConfig.permissions).resolve(
            context = event.context,
            commandPath = match.spec.path,
            defaultRole = defaultRole,
        )
        if (!role.satisfies(match.spec.requiredRole)) {
            recordCommandProcessing(
                event = event,
                stage = IncomingProcessingStage.COMMAND_EXECUTE,
                handlerId = match.spec.path.joinToString("/"),
                result = IncomingProcessingResult.REJECTED,
                commandPath = commandPath,
                role = role.name,
                errorMessage = "权限不足",
            )
            reply(event, CommandExecutionResult.rejected("权限不足"))
            return
        }

        val invocation = CommandInvocation(
            sourcePlugin = event.sourcePlugin,
            context = event.context,
            rawText = event.rawText,
            traceId = event.traceId,
            replyToMessageId = event.replyToMessageId,
            tokens = tokens,
            matchedPath = match.matchedPath,
            args = match.args,
            role = role,
        )

        val started = System.nanoTime()
        val result = runCatching { match.handler.handle(invocation) }
            .getOrElse { CommandExecutionResult.failed("命令执行失败：${it.message}") }
        recordCommandProcessing(
            event = event,
            stage = IncomingProcessingStage.COMMAND_EXECUTE,
            handlerId = match.spec.path.joinToString("/"),
            result = result.status.toIncomingProcessingResult(),
            commandPath = commandPath,
            role = role.name,
            errorMessage = result.errorMessage,
            durationMs = elapsedMillis(started),
        )

        reply(event, result)
        runCatching { result.afterReply?.invoke() }
    }

    private fun reply(event: CommandEvent, result: CommandExecutionResult) {
        CommandResultEvent(
            sourcePlugin = MAIN_OWNER,
            target = CommandTarget(
                address = event.context.target.withPreferredAccount(event.context.botAccountId),
                senderId = event.context.senderId,
            ),
            chain = result.reply,
            inReplyTo = event.replyToMessageId,
            traceId = event.traceId,
            status = result.status,
            errorMessage = result.errorMessage,
        ).let { eventBus.broadcast(it) }
    }

    private fun recordCommandProcessing(
        event: CommandEvent,
        stage: IncomingProcessingStage,
        handlerId: String,
        result: IncomingProcessingResult,
        commandPath: String? = null,
        role: String? = null,
        errorMessage: String? = null,
        durationMs: Long? = null,
    ) {
        runCatching {
            incomingProcessingRecorder(
                IncomingProcessingWriteRequest(
                    traceId = event.traceId,
                    stage = stage,
                    handlerId = handlerId,
                    result = result,
                    commandPath = commandPath,
                    role = role,
                    errorMessage = errorMessage,
                    durationMs = durationMs,
                ),
            )
        }.onFailure { error ->
            commandListenerLogger.warn(error) { "命令入站处理审计记录失败：traceId=${event.traceId}" }
        }
    }

    private fun registerBuiltins() {
        commandRegistry.unregisterByOwner(MAIN_OWNER)
        commandRegistry.register(HelpCommandHandler(commandPrefixProvider, commandRegistry), MAIN_OWNER)
        commandRegistry.register(StatusCommandHandler(commandRegistry), MAIN_OWNER)
        stopRequester?.let { commandRegistry.register(StopApplicationCommandHandler(it), MAIN_OWNER) }
        commandRegistry.register(
            LinkParseCommandHandler(
                linkParseService = linkParseService,
                commandPrefixProvider = commandPrefixProvider,
                maxLinksProvider = { runtimeConfig.linkParsing.maxLinksPerMessage },
            ),
            MAIN_OWNER,
        )
        LinkParseConfigCommandHandler.handlers({ runtimeConfig }, commandPrefixProvider)
            .forEach { handler -> commandRegistry.register(handler, MAIN_OWNER) }
        commandRegistry.register(
            SubscribeCommandHandler(
                publisherLookupResolver,
                publisherFollowResolver,
                { runtimeConfig },
                commandPrefixProvider,
                eventBus,
                runtimePublisherThemeInitializer,
            ),
            MAIN_OWNER,
        )
        commandRegistry.register(LoginCommandHandler(publisherLoginResolver, commandPrefixProvider, eventBus), MAIN_OWNER)
        commandRegistry.register(ForwardCommandHandler(commandPrefixProvider, outboundMessageService), MAIN_OWNER)
        commandRegistry.register(
            UnsubscribeCommandHandler(publisherFollowResolver, { runtimeConfig }, commandPrefixProvider, eventBus),
            MAIN_OWNER,
        )
        commandRegistry.register(ListCommandHandler(), MAIN_OWNER)
        commandRegistry.register(TemplateListCommandHandler { runtimeConfig }, MAIN_OWNER)
        commandRegistry.register(FilterAddElementCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterAddContentCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterListCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterRemoveCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterClearCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(ThemeCommandHandler(commandPrefixProvider, publisherDrawThemeService), MAIN_OWNER)
    }

    private suspend fun shouldAcceptCommand(event: CommandEvent): Boolean {
        val mode = runtimeConfig.command.receiveMode
        if (mode == CommandReceiveMode.ANY) return true
        val selection = incomingBotAccountSelector.select(event.context)
        if (selection.currentBotAccountId == null) return true
        if (selection.currentBotMentioned) return true
        if (mode == CommandReceiveMode.MENTIONED_ONLY) return false
        return selection.acceptsCanonical()
    }
}

private fun CommandInvocation.currentSubscriber(): Subscriber? {
    return SubscriberRepository.findEffectiveByAddress(context.target)
}

private fun top.colter.dynamic.core.data.TargetAddress.withPreferredAccount(
    accountId: String?,
): top.colter.dynamic.core.data.TargetAddress {
    val normalized = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return this
    return copy(accountId = normalized)
}

private fun success(message: String): CommandExecutionResult {
    return CommandExecutionResult.success(message)
}

private fun failed(message: String): CommandExecutionResult {
    return CommandExecutionResult.failed(message)
}

private fun CommandStatus.toIncomingProcessingResult(): IncomingProcessingResult {
    return when (this) {
        CommandStatus.SUCCESS -> IncomingProcessingResult.SUCCEEDED
        CommandStatus.REJECTED -> IncomingProcessingResult.REJECTED
        CommandStatus.FAILED -> IncomingProcessingResult.FAILED
    }
}

private fun elapsedMillis(startedNanos: Long): Long {
    return ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
}

private fun parseDynamicElementType(value: String): DynamicBlockKind? {
    return DynamicBlockKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
}

private fun parseContentCondition(value: String, pattern: String): FilterCondition? {
    return when (value.lowercase()) {
        "keyword" -> FilterCondition.TextMatch(pattern, TextMatchMode.CONTAINS, ignoreCase = true)
        "regex" -> FilterCondition.TextMatch(pattern, TextMatchMode.REGEX, ignoreCase = false)
        else -> null
    }
}

private fun resolveFilterTarget(
    invocation: CommandInvocation,
    platform: String,
    publisherUserId: String,
): FilterTargetResolveResult {
    val normalizedPlatform = platform.lowercase()
    val publisher = PublisherRepository.findByKey(
        PublisherKey.of(normalizedPlatform, PublisherKind.USER, publisherUserId),
    ) ?: return FilterTargetResolveResult.Failed(failed("未找到发布者：$normalizedPlatform:$publisherUserId"))
    val subscriber = invocation.currentSubscriber()
        ?: return FilterTargetResolveResult.Failed(failed("未订阅：$normalizedPlatform:$publisherUserId"))
    val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)
        ?: return FilterTargetResolveResult.Failed(
            failed("未订阅：${publisher.platformId.value}:${publisher.externalId}"),
        )

    return FilterTargetResolveResult.Found(
        ResolvedFilterTarget(
            publisher = publisher,
            subscriber = subscriber,
            subscription = subscription,
        ),
    )
}

private fun formatFilterRule(rule: DynamicFilterRule, publisher: Publisher? = null): String {
    val owner = publisher?.let { "${it.platformId.value}:${it.externalId}" } ?: "subscriptionId=${rule.subscriptionId}"
    return "#${rule.id} $owner ${rule.action.name.lowercase()} ${rule.condition}"
}

private data class ResolvedFilterTarget(
    val publisher: Publisher,
    val subscriber: Subscriber,
    val subscription: Subscription,
)

private data class CommandAutoFollowOutcome(
    val followed: Boolean,
    val warning: String? = null,
)

private sealed interface FilterTargetResolveResult {
    data class Found(val target: ResolvedFilterTarget) : FilterTargetResolveResult
    data class Failed(val result: CommandExecutionResult) : FilterTargetResolveResult
}

private class HelpCommandHandler(
    private val commandPrefixProvider: () -> String,
    private val commandRegistry: CommandRegistry,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("help"),
        description = "显示可用命令",
        usage = "help",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val visibleCommands = commandRegistry.visibleCommandsFor(invocation.role)
        if (visibleCommands.isEmpty()) {
            return CommandExecutionResult.success("没有可用命令")
        }

        val content = visibleCommands.joinToString("\n") { command ->
            val usage = "$commandPrefix ${command.usage}".trim()
            if (command.description.isBlank()) usage else "$usage - ${command.description}"
        }
        return CommandExecutionResult.success(content)
    }
}

private class LoginCommandHandler(
    private val loginProviderResolver: (String) -> PublisherLoginProvider?,
    private val commandPrefixProvider: () -> String,
    private val eventBus: EventBus,
    private val qrCodeRenderer: LoginQrCodeRenderer = LoginQrCodeRenderer(),
    private val loginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    private companion object {
        private const val QR_CHALLENGE_TIMEOUT_MS: Long = 10_000
    }

    override val spec: CommandSpec = CommandSpec(
        path = listOf("login"),
        description = "登录来源平台账号",
        usage = "login <platform> <cookie|qr> [cookie]",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return failedUsage()
        }

        val platform = invocation.args[0].lowercase()
        val method = invocation.args[1].lowercase()
        val plugin = loginProviderResolver(platform)
            ?: return CommandExecutionResult.failed("未找到平台登录插件：$platform")

        return when (method) {
            "cookie" -> handleCookieLogin(plugin, platform, invocation.args.drop(2).joinToString(" ").trim())
            "qr", "qrcode", "qr-code" -> handleQrLogin(plugin, platform, invocation)
            else -> failedUsage()
        }
    }

    private suspend fun handleCookieLogin(
        plugin: PublisherLoginProvider,
        platform: String,
        cookie: String,
    ): CommandExecutionResult {
        if (!plugin.supportedLoginMethods.contains(PublisherLoginMethod.COOKIE)) {
            return CommandExecutionResult.failed("$platform 不支持 cookie 登录")
        }
        if (cookie.isBlank()) {
            return CommandExecutionResult.failed("用法：$commandPrefix login $platform cookie <cookie>")
        }

        val result = runCatching { plugin.loginByCookie(cookie) }
            .getOrElse { throwable ->
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = throwable.message ?: "cookie 登录失败",
                )
            }
        return toCommandResult(platform, result)
    }

    private suspend fun handleQrLogin(
        plugin: PublisherLoginProvider,
        platform: String,
        invocation: CommandInvocation,
    ): CommandExecutionResult {
        if (!plugin.supportedLoginMethods.contains(PublisherLoginMethod.QR_CODE)) {
            return CommandExecutionResult.failed("$platform 不支持二维码登录")
        }
        if (invocation.args.size > 2) {
            return failedUsage()
        }

        val challenge = CompletableDeferred<PublisherQrLoginChallenge?>()
        val finalResult = CompletableDeferred<PublisherLoginResult>()
        val shouldBroadcastFinal = CompletableDeferred<Boolean>()

        loginScope.launch {
            val result = runCatching {
                plugin.loginByQrCode(
                    onQrCode = { qrChallenge ->
                        if (!challenge.isCompleted) {
                            challenge.complete(qrChallenge)
                        }
                    },
                    onStatusChanged = {},
                )
            }.getOrElse { throwable ->
                if (!challenge.isCompleted) {
                    challenge.complete(null)
                }
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = throwable.message ?: "二维码登录失败",
                )
            }

            if (!challenge.isCompleted) {
                challenge.complete(null)
            }
            finalResult.complete(result)
            if (shouldBroadcastFinal.await()) {
                broadcastLoginResult(invocation, platform, result)
            }
        }

        val qrChallenge = withTimeoutOrNull(QR_CHALLENGE_TIMEOUT_MS) { challenge.await() }
        if (qrChallenge == null) {
            shouldBroadcastFinal.complete(false)
            val result = if (finalResult.isCompleted) {
                finalResult.await()
            } else {
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = "二维码登录启动失败",
                )
            }
            return toCommandResult(platform, result)
        }

        return renderQrChallenge(
            platform = platform,
            challenge = qrChallenge,
            afterReply = {
                if (!shouldBroadcastFinal.isCompleted) {
                    shouldBroadcastFinal.complete(true)
                }
            },
        )
    }

    private fun renderQrChallenge(
        platform: String,
        challenge: PublisherQrLoginChallenge,
        afterReply: suspend () -> Unit,
    ): CommandExecutionResult {
        val expiresText = challenge.expiresAtEpochSeconds?.let { "过期时间=$it" } ?: "过期时间=未知"
        val message = buildString {
            appendLine("$platform 二维码登录已启动。")
            appendLine("请使用对应平台 App 扫描二维码，登录结果会自动发送。")
            append(expiresText)
            challenge.message?.takeIf { it.isNotBlank() }?.let { appendLine().append(it) }
        }

        val imagePath = runCatching {
            qrCodeRenderer.render(challenge.qrContent)
        }.getOrNull()

        val contents = buildList {
            imagePath?.let { path ->
                add(MessageContent.Image(fallbackText = "", image = MediaRef(uri = path, kind = MediaKind.IMAGE)))
            }
            add(MessageContent.Text(message))
        }
        return CommandExecutionResult(
            status = CommandStatus.SUCCESS,
            reply = listOf(MessageBatch(contents)),
            errorMessage = null,
            afterReply = afterReply,
        )
    }

    private fun toCommandResult(platform: String, result: PublisherLoginResult): CommandExecutionResult {
        val status = when (result.status) {
            PublisherLoginStatus.SUCCESS,
            PublisherLoginStatus.PENDING -> CommandStatus.SUCCESS
            PublisherLoginStatus.CANCELED,
            PublisherLoginStatus.EXPIRED,
            PublisherLoginStatus.FAILED,
            PublisherLoginStatus.UNSUPPORTED -> CommandStatus.FAILED
        }
        val account = result.account?.let { account ->
            listOfNotNull(account.name, account.userId?.let { "($it)" }).joinToString(" ")
        }?.takeIf { it.isNotBlank() }
        val message = buildString {
            append("$platform 登录 ${result.status.name.lowercase()}：${result.message}")
            account?.let { append(" $it") }
        }
        return CommandExecutionResult.text(status, message)
    }

    private fun broadcastLoginResult(invocation: CommandInvocation, platform: String, result: PublisherLoginResult) {
        val commandResult = toCommandResult(platform, result)
        CommandResultEvent(
            sourcePlugin = "main",
            target = CommandTarget(
                address = invocation.context.target.withPreferredAccount(invocation.context.botAccountId),
                senderId = invocation.context.senderId,
            ),
            chain = commandResult.reply,
            inReplyTo = invocation.replyToMessageId,
            traceId = invocation.traceId,
            status = commandResult.status,
            errorMessage = commandResult.errorMessage,
        ).let { eventBus.broadcast(it) }
    }

    private fun failedUsage(): CommandExecutionResult {
        return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
    }
}

private class LoginQrCodeRenderer(
    private val outputDir: Path = Paths.get("data", "login-qr"),
) {
    fun render(content: String): String {
        Files.createDirectories(outputDir)
        val outputPath = outputDir
            .resolve("qr-${System.currentTimeMillis()}-${content.hashCode().toUInt().toString(16)}.png")
            .toAbsolutePath()
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        ImageIO.write(image, "png", outputPath.toFile())
        return outputPath.toString()
    }
}

private class StatusCommandHandler(
    private val commandRegistry: CommandRegistry,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("status"),
        description = "显示运行状态",
        usage = "status",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val commandCount = commandRegistry.listCommands().size
        val subscriptionCount = SubscriptionRepository.countAll()
        val pendingDeliveries = MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING)
        val failedDeliveries = MessageDeliveryRepository.countByStatus(DeliveryStatus.FAILED)
        return CommandExecutionResult.success(
            "运行正常，命令数=$commandCount，订阅数=$subscriptionCount，待发送=$pendingDeliveries，发送失败=$failedDeliveries",
        )
    }
}

private class StopApplicationCommandHandler(
    private val stopRequester: (String) -> Unit,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("stop"),
        aliases = listOf(listOf("shutdown")),
        description = "停止主程序",
        usage = "stop",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.isNotEmpty()) {
            return failed("用法：${invocation.matchedPath.joinToString(" ")}")
        }
        return CommandExecutionResult.success(
            message = "已请求停止，程序正在关闭",
            afterReply = {
                stopRequester("command:${invocation.context.target.stableValue()}")
            },
        )
    }
}

private class ForwardCommandHandler(
    private val commandPrefixProvider: () -> String,
    private val outboundMessageService: OutboundMessageService,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("forward"),
        description = "批量转发文本消息到指定目标",
        usage = "forward <platform> <kind> <targetIds> <message...>",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 4) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].trim().lowercase()
        if (platform.isBlank()) return failed("消息目标平台不能为空")

        val kind = TargetKind.entries.firstOrNull { it.name.equals(invocation.args[1], ignoreCase = true) }
            ?: return failed("目标类型无效：${invocation.args[1]}")
        val targetIds = invocation.args[2]
            .split(',', '，')
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
        if (targetIds.isEmpty()) return failed("消息目标 ID 不能为空")

        val text = invocation.args.drop(3).joinToString(" ").trim()
        if (text.isBlank()) return failed("转发内容不能为空")

        val targets = targetIds.map { targetId ->
            TargetAddress.of(
                platformId = platform,
                kind = kind,
                externalId = targetId,
            )
        }
        val result = outboundMessageService.enqueueText(
            source = "command:${invocation.context.target.stableValue()}",
            targets = targets,
            text = text,
            renderVariant = RENDER_VARIANT_MANUAL_FORWARD,
        )
        return success(
            "已创建转发任务：目标=${result.targetCount}，新增投递=${result.newDeliveryCount}，已存在=${result.existingDeliveryCount}",
        )
    }
}

private class SubscribeCommandHandler(
    private val lookupResolver: (String) -> PublisherLookupPlugin?,
    private val followResolver: (String) -> PublisherFollowPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
    private val eventBus: EventBus,
    private val publisherThemeInitializer: PublisherThemeInitializer,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("subscribe"),
        description = "订阅来源平台发布者",
        usage = "subscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val lookupPlugin = lookupResolver(platform)
            ?: return CommandExecutionResult.failed("未找到发布者查询插件：$platform")
        val autoFollowEnabled = configProvider().subscription.autoFollowPublisherOnSubscribe
        val followPlugin = if (autoFollowEnabled) {
            followResolver(platform)
        } else {
            null
        }

        val publisherInfo = lookupPlugin.fetchPublisherInfo(publisherUserId)
            ?: return CommandExecutionResult.failed("未找到发布者：$platform:$publisherUserId")

        val autoFollowOutcome = if (autoFollowEnabled) {
            tryAutoFollow(followPlugin, platform, publisherUserId)
        } else {
            CommandAutoFollowOutcome(followed = false)
        }

        val publisherUpsert = PublisherRepository.upsertInfo(publisherInfo)
        val previousSubscriptionCount = SubscriptionRepository.countByPublisherId(publisherUpsert.value.id)
        val subscriber = SubscriberRepository.ensure(
            address = invocation.context.target,
            name = invocation.context.chatId,
        )
        val mutation = SubscriptionRepository.subscribe(subscriber.id, publisherUpsert.value.id)
        mutation.event?.let { eventBus.broadcast(it) }
        if (mutation.changed) {
            publisherThemeInitializer.initializeAfterFirstSubscription(
                publisher = publisherUpsert.value,
                previousSubscriptionCount = previousSubscriptionCount,
            )
        }

        val publisherState = if (publisherUpsert.created) "新建" else "已存在"
        val subscriptionState = if (mutation.changed) "新建" else "已存在"
        val followStateText = if (!autoFollowEnabled) "已关闭" else if (autoFollowOutcome.followed) "是" else "否"
        val warningText = autoFollowOutcome.warning?.let { "，自动关注提示=$it" }.orEmpty()
        return CommandExecutionResult.success(
            "已订阅：${publisherUpsert.value.name}（自动关注=$followStateText，发布者=$publisherState，订阅=$subscriptionState$warningText）",
        )
    }

    private suspend fun tryAutoFollow(
        followPlugin: PublisherFollowPlugin?,
        platform: String,
        publisherUserId: String,
    ): CommandAutoFollowOutcome {
        if (followPlugin == null) {
            return CommandAutoFollowOutcome(
                followed = false,
                warning = "未找到发布者关注插件：$platform",
            )
        }
        val followResult = followPlugin.followPublisher(publisherUserId)
        return when (followResult.status) {
            FollowActionStatus.DONE -> CommandAutoFollowOutcome(followed = true)
            FollowActionStatus.NOOP -> CommandAutoFollowOutcome(followed = false)
            FollowActionStatus.FAILED -> CommandAutoFollowOutcome(
                followed = false,
                warning = followResult.message ?: "关注发布者失败：$platform",
            )
            FollowActionStatus.UNSUPPORTED -> CommandAutoFollowOutcome(
                followed = false,
                warning = "$platform 不支持自动关注",
            )
        }
    }
}

private class UnsubscribeCommandHandler(
    private val followResolver: (String) -> PublisherFollowPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
    private val eventBus: EventBus,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("unsubscribe"),
        description = "取消订阅发布者",
        usage = "unsubscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val publisher = PublisherRepository.findByKey(PublisherKey.of(platform, PublisherKind.USER, publisherUserId))
            ?: return CommandExecutionResult.failed("未找到发布者：$platform:$publisherUserId")

        val subscriber = invocation.currentSubscriber()
            ?: return CommandExecutionResult.success("当前会话没有订阅")
        val mutation = SubscriptionRepository.unsubscribe(subscriber.id, publisher.id)
        mutation.event?.let { eventBus.broadcast(it) }
        val removed = mutation.changed
        if (removed && configProvider().subscription.unfollowWhenNoSubscribers && SubscriptionRepository.countByPublisherId(publisher.id) == 0L) {
            followResolver(platform)?.unfollowPublisher(publisher.externalId)
        }
        return if (removed) {
            CommandExecutionResult.success("已取消订阅：${publisher.name}")
        } else {
            CommandExecutionResult.success("未订阅：${publisher.name}")
        }
    }
}

private class ListCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("list"),
        description = "列出当前会话订阅",
        usage = "list",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val subscriber = invocation.currentSubscriber()
            ?: return CommandExecutionResult.success("当前会话没有订阅")
        val publisherIds = SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id)
        if (publisherIds.isEmpty()) return CommandExecutionResult.success("当前会话没有订阅")

        val lines = publisherIds
            .mapNotNull { PublisherRepository.findById(it) }
            .map { "${it.platformId.value}:${it.externalId} (${it.name})" }

        if (lines.isEmpty()) return CommandExecutionResult.success("当前会话没有订阅")
        return CommandExecutionResult.success(lines.joinToString("\n"))
    }
}

private class TemplateListCommandHandler(
    private val configProvider: () -> MainDynamicConfig,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "list"),
        description = "列出消息模板",
        usage = "template list",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val templates = configProvider().templates
        val lines = listOf(
            "dynamic:",
            templates.dynamic,
            "liveStarted:",
            templates.liveStarted,
            "liveEnded:",
            templates.liveEnded,
        )
        return CommandExecutionResult.success(lines.joinToString("\n"))
    }
}

private class ThemeCommandHandler(
    private val commandPrefixProvider: () -> String,
    private val themeService: PublisherDrawThemeService,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("theme"),
        description = "查看或设置当前会话已订阅发布者的绘图主题色",
        usage = "theme <show|set|clear> <platform> <publisherUserId> [#FE65A6;#BFFAFF]",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 3) return failedUsage()
        val action = invocation.args[0].lowercase()
        val platform = invocation.args[1]
        val publisherUserId = invocation.args[2]
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }

        return when (action) {
            "show" -> showTheme(target.publisher)
            "set" -> setTheme(invocation, target.publisher)
            "clear" -> clearTheme(target.publisher)
            else -> failedUsage()
        }
    }

    private fun showTheme(publisher: Publisher): CommandExecutionResult {
        val theme = PublisherDrawThemeRepository.findByPublisherId(publisher.id)
            ?: return success("发布者未设置专属主题色：${publisher.name}")
        return success(formatTheme("发布者主题色", publisher, theme.palette.backgroundColors, theme.palette.primaryColor))
    }

    private fun setTheme(invocation: CommandInvocation, publisher: Publisher): CommandExecutionResult {
        val colors = invocation.args.drop(3).joinToString(" ").trim()
        if (colors.isBlank()) return failed("用法：$commandPrefix ${spec.usage}")
        val palette = try {
            themeService.setTheme(publisher.id, colors)
        } catch (e: IllegalArgumentException) {
            return failed("主题色无效：${e.message}")
        }
        return success(formatTheme("发布者主题色已保存", publisher, palette.backgroundColors, palette.primaryColor))
    }

    private fun clearTheme(publisher: Publisher): CommandExecutionResult {
        val changed = themeService.clearTheme(publisher.id)
        return success(
            if (changed) {
                "发布者主题色已清除：${publisher.name}"
            } else {
                "发布者未设置专属主题色：${publisher.name}"
            },
        )
    }

    private fun formatTheme(
        title: String,
        publisher: Publisher,
        backgroundColors: List<String>,
        primaryColor: String,
    ): String {
        return "$title：${publisher.name}\n背景=${backgroundColors.joinToString(";")}\n强调=$primaryColor"
    }

    private fun failedUsage(): CommandExecutionResult {
        return failed("用法：$commandPrefix ${spec.usage}")
    }
}

private class FilterAddElementCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "add", "element"),
        description = "为订阅添加动态元素屏蔽规则",
        usage = "filter add element <platform> <publisherUserId> <text|image|video|card|poll|repost>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 3) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0]
        val publisherUserId = invocation.args[1]
        val element = parseDynamicElementType(invocation.args[2])
            ?: return failed("未知动态元素：${invocation.args[2]}")
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }

        val result = DynamicFilterRuleRepository.addRule(
            subscriptionId = target.subscription.id,
            condition = FilterCondition.HasElement(element),
        )
        val state = if (result.created) "已创建" else "已存在"
        return success("过滤规则$state：${formatFilterRule(result.value, target.publisher)}")
    }
}

private class FilterAddContentCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "add", "content"),
        description = "为订阅添加内容屏蔽规则",
        usage = "filter add content <platform> <publisherUserId> <keyword|regex> <pattern...>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 4) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0]
        val publisherUserId = invocation.args[1]
        val pattern = invocation.args.drop(3).joinToString(" ").trim()
        if (pattern.isBlank()) {
            return failed("过滤内容不能为空")
        }
        val condition = parseContentCondition(invocation.args[2], pattern)
            ?: return failed("未知内容匹配器：${invocation.args[2]}")
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }

        val result = try {
            DynamicFilterRuleRepository.addRule(
                subscriptionId = target.subscription.id,
                condition = condition,
            )
        } catch (e: IllegalArgumentException) {
            return failed("过滤规则被拒绝：${e.message}")
        }
        val state = if (result.created) "已创建" else "已存在"
        return success("过滤规则$state：${formatFilterRule(result.value, target.publisher)}")
    }
}

private class FilterListCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "list"),
        description = "列出当前会话订阅过滤规则",
        usage = "filter list [platform] [publisherUserId]",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        return when (invocation.args.size) {
            0 -> listAll(invocation)
            2 -> listOne(invocation, invocation.args[0], invocation.args[1])
            else -> failed("用法：$commandPrefix ${spec.usage}")
        }
    }

    private fun listAll(invocation: CommandInvocation): CommandExecutionResult {
        val subscriber = invocation.currentSubscriber() ?: return success("过滤规则：（无）")
        val targets = SubscriptionRepository
            .findPublisherIdsBySubscriberId(subscriber.id)
            .mapNotNull { publisherId ->
                val publisher = PublisherRepository.findById(publisherId) ?: return@mapNotNull null
                val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)
                    ?: return@mapNotNull null
                ResolvedFilterTarget(publisher, subscriber, subscription)
            }
            .sortedWith(compareBy<ResolvedFilterTarget> { it.publisher.platformId.value }.thenBy { it.publisher.externalId })

        if (targets.isEmpty()) return success("过滤规则：（无）")

        val rulesBySubscriptionId = DynamicFilterRuleRepository.findBySubscriptionIds(targets.map { it.subscription.id })
        val lines = targets.flatMap { target ->
            rulesBySubscriptionId[target.subscription.id]
                .orEmpty()
                .sortedBy { it.id }
                .map { rule -> formatFilterRule(rule, target.publisher) }
        }

        return success(lines.ifEmpty { listOf("过滤规则：（无）") }.joinToString("\n"))
    }

    private fun listOne(
        invocation: CommandInvocation,
        platform: String,
        publisherUserId: String,
    ): CommandExecutionResult {
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }
        val lines = DynamicFilterRuleRepository
            .findBySubscriptionId(target.subscription.id)
            .sortedBy { it.id }
            .map { rule -> formatFilterRule(rule, target.publisher) }

        return success(lines.ifEmpty { listOf("过滤规则：（无）") }.joinToString("\n"))
    }
}

private class FilterRemoveCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "remove"),
        description = "删除订阅过滤规则",
        usage = "filter remove <ruleId>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 1) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }
        val ruleId = invocation.args.single().toIntOrNull()
            ?: return failed("过滤规则 ID 无效：${invocation.args.single()}")
        val rule = DynamicFilterRuleRepository.findById(ruleId)
            ?: return failed("未找到过滤规则：#$ruleId")
        if (!rule.belongsToCurrentSubscriber(invocation)) {
            return failed("未找到过滤规则：#$ruleId")
        }

        val removed = DynamicFilterRuleRepository.removeById(ruleId)
        return if (removed) {
            success("已删除过滤规则：#$ruleId")
        } else {
            failed("未找到过滤规则：#$ruleId")
        }
    }
}

private class FilterClearCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "clear"),
        description = "清空订阅过滤规则",
        usage = "filter clear <platform> <publisherUserId>",
        requiredRole = CommandRole.MANAGER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 2) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }
        val target = when (val resolved = resolveFilterTarget(invocation, invocation.args[0], invocation.args[1])) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(target.subscription.id)
        return success("已清空过滤规则：数量=$removed")
    }
}

private fun DynamicFilterRule.belongsToCurrentSubscriber(invocation: CommandInvocation): Boolean {
    val subscriber = invocation.currentSubscriber() ?: return false
    val subscription = SubscriptionRepository.findById(subscriptionId) ?: return false
    return subscription.subscriberId == subscriber.id
}
