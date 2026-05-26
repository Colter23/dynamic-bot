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
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandExecutionStatus
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandParser
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.DeliveryStatus
import top.colter.dynamic.core.data.DynamicElementType
import top.colter.dynamic.core.data.DynamicFilterMatcher
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.Subscriber
import top.colter.dynamic.core.data.SubscriberType
import top.colter.dynamic.core.data.Subscription
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.plugin.FollowActionStatus
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PlatformPublisherPlugin
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.repository.DynamicFilterRuleRepository
import top.colter.dynamic.core.repository.MessageDeliveryRepository
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscriptionRepository
import top.colter.dynamic.link.DynamicLinkForwarder
import top.colter.dynamic.link.ParseDynamicLinkCommandHandler
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

public class CommandListener(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val dynamicLinkForwarder: DynamicLinkForwarder = DynamicLinkForwarder { emptyList() },
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val stopRequester: ((String) -> Unit)? = null,
    private val configService: ConfigService = DefaultConfigService,
) : Listener<CommandEvent> {
    private companion object {
        private const val MAIN_OWNER: String = "main"
    }

    private val fixedConfig: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
    }
    private val runtimeConfigProvider: () -> MainDynamicConfig = configProvider ?: { fixedConfig }
    private val runtimeConfig: MainDynamicConfig
        get() = runtimeConfigProvider()

    private val commandPrefix: String
        get() = runtimeConfig.command.prefix
    private val commandPrefixProvider: () -> String = { commandPrefix }

    init {
        registerBuiltins()
    }

    override suspend fun onMessage(event: CommandEvent) {
        val parsed = CommandParser.parse(event.rawText, commandPrefix) ?: return
        val tokens = parsed.tokens

        val match = CommandRegistry.match(tokens)
        if (match == null) {
            reply(
                event,
                CommandExecutionResult(
                    CommandExecutionStatus.FAILED,
                    "unknown command: ${tokens.joinToString(" ")}",
                )
            )
            return
        }

        val role = CommandPermissionResolver(runtimeConfig.command.permissions).resolve(event.context)
        if (!role.satisfies(match.spec.requiredRole)) {
            reply(event, CommandExecutionResult(CommandExecutionStatus.REJECTED, "permission denied"))
            return
        }

        val invocation = CommandInvocation(
            sourcePlugin = event.sourcePlugin,
            context = event.context,
            rawText = event.rawText,
            traceId = event.traceId,
            tokens = tokens,
            matchedPath = match.matchedPath,
            args = match.args,
            role = role,
        )

        val result = runCatching { match.handler.handle(invocation) }
            .getOrElse { CommandExecutionResult(CommandExecutionStatus.FAILED, "command failed: ${it.message}") }

        reply(event, result)
        runCatching { result.afterReply?.invoke() }
    }

    private fun reply(event: CommandEvent, result: CommandExecutionResult) {
        val status = when (result.status) {
            CommandExecutionStatus.SUCCESS -> CommandStatus.SUCCESS
            CommandExecutionStatus.REJECTED -> CommandStatus.REJECTED
            CommandExecutionStatus.FAILED -> CommandStatus.FAILED
        }
        CommandResultEvent(
            sourcePlugin = MAIN_OWNER,
            target = CommandTarget(
                platform = event.context.platform,
                chatType = event.context.chatType,
                chatId = event.context.chatId,
                senderId = event.context.senderId,
            ),
            chain = result.chain ?: listOf(MessageChain(listOf(MessageContent.Text(result.message)))),
            inReplyTo = event.traceId,
            status = status,
            errorMessage = if (status == CommandStatus.FAILED) result.message else null,
        ).broadcast()
    }

    private fun registerBuiltins() {
        CommandRegistry.unregisterByOwner(MAIN_OWNER)
        CommandRegistry.register(HelpCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(StatusCommandHandler(), MAIN_OWNER)
        stopRequester?.let { CommandRegistry.register(StopApplicationCommandHandler(it), MAIN_OWNER) }
        CommandRegistry.register(ParseDynamicLinkCommandHandler(dynamicLinkForwarder, commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(SubscribeCommandHandler(platformPluginResolver, commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(LoginCommandHandler(platformPluginResolver, commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(UnsubscribeCommandHandler(platformPluginResolver, { runtimeConfig }, commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(ListCommandHandler(), MAIN_OWNER)
        CommandRegistry.register(TemplateListCommandHandler { runtimeConfig }, MAIN_OWNER)
        CommandRegistry.register(TemplateSetCommandHandler({ runtimeConfig }, commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(TemplateRemoveCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(FilterAddElementCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(FilterAddContentCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(FilterListCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(FilterRemoveCommandHandler(commandPrefixProvider), MAIN_OWNER)
        CommandRegistry.register(FilterClearCommandHandler(commandPrefixProvider), MAIN_OWNER)
    }
}

private fun ChatType.toSubscriberType(): SubscriberType {
    return when (this) {
        ChatType.GROUP -> SubscriberType.GROUP
        ChatType.PRIVATE -> SubscriberType.USER
        ChatType.CHANNEL -> SubscriberType.CHANNEL
    }
}

private fun CommandInvocation.currentSubscriber(): Subscriber? {
    return SubscriberRepository.findByPlatformAndTarget(
        platformId = context.platform,
        type = context.chatType.toSubscriberType(),
        targetId = context.chatId,
    )
}

private fun success(message: String): CommandExecutionResult {
    return CommandExecutionResult(CommandExecutionStatus.SUCCESS, message)
}

private fun failed(message: String): CommandExecutionResult {
    return CommandExecutionResult(CommandExecutionStatus.FAILED, message)
}

private fun parseDynamicElementType(value: String): DynamicElementType? {
    return DynamicElementType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
}

private fun parseContentMatcher(value: String): DynamicFilterMatcher? {
    return when (value.lowercase()) {
        "keyword" -> DynamicFilterMatcher.KEYWORD
        "regex" -> DynamicFilterMatcher.REGEX
        else -> null
    }
}

private fun resolveFilterTarget(
    invocation: CommandInvocation,
    platform: String,
    publisherUserId: String,
): FilterTargetResolveResult {
    val normalizedPlatform = platform.lowercase()
    val publisher = PublisherRepository.findByPlatformAndExternalId(normalizedPlatform, publisherUserId)
        ?: return FilterTargetResolveResult.Failed(failed("publisher not found: $normalizedPlatform:$publisherUserId"))
    val subscriber = invocation.currentSubscriber()
        ?: return FilterTargetResolveResult.Failed(failed("not subscribed: $normalizedPlatform:$publisherUserId"))
    val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)
        ?: return FilterTargetResolveResult.Failed(failed("not subscribed: ${publisher.platformId}:${publisher.externalId}"))

    return FilterTargetResolveResult.Found(
        ResolvedFilterTarget(
            publisher = publisher,
            subscriber = subscriber,
            subscription = subscription,
        )
    )
}

private fun formatFilterRule(rule: DynamicFilterRule, publisher: Publisher? = null): String {
    val owner = publisher?.let { "${it.platformId}:${it.externalId}" } ?: "subscriptionId=${rule.subscriptionId}"
    val value = if (rule.matcher == DynamicFilterMatcher.HAS_ELEMENT) {
        rule.value.lowercase()
    } else {
        rule.value
    }
    return "#${rule.id} $owner ${rule.ruleType.name.lowercase()} ${rule.matcher.name.lowercase()} $value"
}

private data class ResolvedFilterTarget(
    val publisher: Publisher,
    val subscriber: Subscriber,
    val subscription: Subscription,
)

private sealed interface FilterTargetResolveResult {
    data class Found(val target: ResolvedFilterTarget) : FilterTargetResolveResult
    data class Failed(val result: CommandExecutionResult) : FilterTargetResolveResult
}

private class HelpCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("help"),
        description = "show available commands",
        usage = "help",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val visibleCommands = CommandRegistry.visibleCommandsFor(invocation.role)
        if (visibleCommands.isEmpty()) {
            return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no commands available")
        }

        val content = visibleCommands.joinToString("\n") { command ->
            val usage = "$commandPrefix ${command.usage}".trim()
            if (command.description.isBlank()) usage else "$usage - ${command.description}"
        }
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, content)
    }
}

private class LoginCommandHandler(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val commandPrefixProvider: () -> String,
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
        description = "login a publisher platform account",
        usage = "login <platform> <cookie|qr> [cookie]",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return failedUsage()
        }

        val platform = invocation.args[0].lowercase()
        val method = invocation.args[1].lowercase()
        val plugin = platformPluginResolver(platform)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "platform plugin not found: $platform")

        return when (method) {
            "cookie" -> handleCookieLogin(plugin, platform, invocation.args.drop(2).joinToString(" ").trim())
            "qr", "qrcode", "qr-code" -> handleQrLogin(plugin, platform, invocation)
            else -> failedUsage()
        }
    }

    private suspend fun handleCookieLogin(
        plugin: PlatformPublisherPlugin,
        platform: String,
        cookie: String,
    ): CommandExecutionResult {
        if (!plugin.supportedLoginMethods.contains(PublisherLoginMethod.COOKIE)) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "cookie login is unsupported on $platform")
        }
        if (cookie.isBlank()) {
            return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "usage: $commandPrefix login $platform cookie <cookie>",
            )
        }

        val result = runCatching { plugin.loginByCookie(cookie) }
            .getOrElse { throwable ->
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = throwable.message ?: "cookie login failed",
                )
            }
        return toCommandResult(platform, result)
    }

    private suspend fun handleQrLogin(
        plugin: PlatformPublisherPlugin,
        platform: String,
        invocation: CommandInvocation,
    ): CommandExecutionResult {
        if (!plugin.supportedLoginMethods.contains(PublisherLoginMethod.QR_CODE)) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "QR code login is unsupported on $platform")
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
                    message = throwable.message ?: "QR code login failed",
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
                    message = "failed to start QR code login",
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
        val expiresText = challenge.expiresAtEpochSeconds?.let { "expiresAt=$it" } ?: "expiresAt=unknown"
        val message = buildString {
            appendLine("$platform QR login started.")
            appendLine("scan the QR code in Bilibili app; the login result will be sent automatically.")
            append(expiresText)
            challenge.message?.takeIf { it.isNotBlank() }?.let { appendLine().append(it) }
        }

        val imagePath = runCatching {
            qrCodeRenderer.render(challenge.qrContent)
        }.getOrNull()

        val contents = buildList {
            imagePath?.let { path ->
                add(MessageContent.Image(fallbackText = "", image = LazyImage(path)))
            }
            add(MessageContent.Text(message))
        }
        return CommandExecutionResult(
            status = CommandExecutionStatus.SUCCESS,
            message = message,
            chain = listOf(MessageChain(contents)),
            afterReply = afterReply,
        )
    }

    private fun toCommandResult(platform: String, result: PublisherLoginResult): CommandExecutionResult {
        val status = when (result.status) {
            PublisherLoginStatus.SUCCESS,
            PublisherLoginStatus.PENDING -> CommandExecutionStatus.SUCCESS
            PublisherLoginStatus.CANCELED,
            PublisherLoginStatus.EXPIRED,
            PublisherLoginStatus.FAILED,
            PublisherLoginStatus.UNSUPPORTED -> CommandExecutionStatus.FAILED
        }
        val account = result.account?.let { account ->
            listOfNotNull(account.name, account.userId?.let { "($it)" }).joinToString(" ")
        }?.takeIf { it.isNotBlank() }
        val message = buildString {
            append("$platform login ${result.status.name.lowercase()}: ${result.message}")
            account?.let { append(" $it") }
        }
        return CommandExecutionResult(status, message)
    }

    private fun broadcastLoginResult(invocation: CommandInvocation, platform: String, result: PublisherLoginResult) {
        val commandResult = toCommandResult(platform, result)
        val status = when (commandResult.status) {
            CommandExecutionStatus.SUCCESS -> CommandStatus.SUCCESS
            CommandExecutionStatus.REJECTED -> CommandStatus.REJECTED
            CommandExecutionStatus.FAILED -> CommandStatus.FAILED
        }
        CommandResultEvent(
            sourcePlugin = "main",
            target = CommandTarget(
                platform = invocation.context.platform,
                chatType = invocation.context.chatType,
                chatId = invocation.context.chatId,
                senderId = invocation.context.senderId,
            ),
            chain = commandResult.chain ?: listOf(MessageChain(listOf(MessageContent.Text(commandResult.message)))),
            inReplyTo = invocation.traceId,
            status = status,
            errorMessage = if (status == CommandStatus.FAILED) commandResult.message else null,
        ).broadcast()
    }

    private fun failedUsage(): CommandExecutionResult {
        return CommandExecutionResult(
            CommandExecutionStatus.FAILED,
            "usage: $commandPrefix ${spec.usage}",
        )
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

private class StatusCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("status"),
        description = "show runtime status",
        usage = "status",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val commandCount = CommandRegistry.listCommands().size
        val subscriptionCount = SubscriptionRepository.countAll()
        val pendingDeliveries = MessageDeliveryRepository.countByStatus(DeliveryStatus.PENDING)
        val failedDeliveries = MessageDeliveryRepository.countByStatus(DeliveryStatus.FAILED)
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "ok, commands=$commandCount, subscriptions=$subscriptionCount, deliveryPending=$pendingDeliveries, deliveryFailed=$failedDeliveries",
        )
    }
}

private class StopApplicationCommandHandler(
    private val stopRequester: (String) -> Unit,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("stop"),
        aliases = listOf(listOf("shutdown")),
        description = "stop the main application",
        usage = "stop",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.isNotEmpty()) {
            return failed("usage: ${invocation.matchedPath.joinToString(" ")}")
        }
        return CommandExecutionResult(
            status = CommandExecutionStatus.SUCCESS,
            message = "stop requested; application is shutting down",
            afterReply = {
                stopRequester("command:${invocation.context.platform}:${invocation.context.chatId}")
            },
        )
    }
}

private class SubscribeCommandHandler(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("subscribe"),
        description = "subscribe to a publisher on a supported platform",
        usage = "subscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val plugin = platformPluginResolver(platform)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "platform plugin not found: $platform")

        val profile = plugin.fetchPublisherProfile(publisherUserId)
            ?: return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "publisher not found on $platform: $publisherUserId",
            )

        val followState = plugin.queryFollowState(publisherUserId)
        val autoFollowed = when (followState) {
            FollowState.FOLLOWING -> false
            FollowState.NOT_FOLLOWING -> {
                val followResult = plugin.followPublisher(publisherUserId)
                when (followResult.status) {
                    FollowActionStatus.FOLLOWED -> true
                    FollowActionStatus.ALREADY_FOLLOWING -> false
                    FollowActionStatus.FAILED -> {
                        return CommandExecutionResult(
                            CommandExecutionStatus.FAILED,
                            followResult.message ?: "failed to follow publisher on $platform",
                        )
                    }
                    FollowActionStatus.UNSUPPORTED -> {
                        return CommandExecutionResult(
                            CommandExecutionStatus.FAILED,
                            "follow action is unsupported on $platform",
                        )
                    }
                }
            }
            FollowState.UNSUPPORTED -> {
                return CommandExecutionResult(
                    CommandExecutionStatus.FAILED,
                    "follow check is unsupported on $platform",
                )
            }
        }

        val publisherUpsert = PublisherRepository.upsertProfile(profile)
        val subscriberType = invocation.context.chatType.toSubscriberType()
        val subscriber = SubscriberRepository.ensure(
            platformId = invocation.context.platform,
            targetId = invocation.context.chatId,
            name = invocation.context.chatId,
            type = subscriberType,
        )
        val created = SubscriptionRepository.subscribe(subscriber.id, publisherUpsert.value.id)

        val publisherState = if (publisherUpsert.created) "new" else "existing"
        val subscriptionState = if (created) "new" else "existing"
        val followStateText = if (autoFollowed) "yes" else "no"
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "subscribed: ${publisherUpsert.value.name} " +
                "(auto-followed=$followStateText, publisher=$publisherState, subscription=$subscriptionState)",
        )
    }
}

private class UnsubscribeCommandHandler(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("unsubscribe"),
        description = "unsubscribe from a publisher",
        usage = "unsubscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val publisher = PublisherRepository.findByPlatformAndExternalId(platform, publisherUserId)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher not found: $platform:$publisherUserId")

        val subscriber = SubscriberRepository.findByPlatformAndTarget(
            platformId = invocation.context.platform,
            type = invocation.context.chatType.toSubscriberType(),
            targetId = invocation.context.chatId,
        )
            ?: return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")
        val removed = SubscriptionRepository.unsubscribe(subscriber.id, publisher.id)
        if (removed && configProvider().subscription.unfollowWhenNoSubscribers && SubscriptionRepository.countByPublisherId(publisher.id) == 0L) {
            platformPluginResolver(platform)?.unfollowPublisher(publisher.externalId)
        }
        return if (removed) {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "unsubscribed: ${publisher.name}")
        } else {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "not subscribed: ${publisher.name}")
        }
    }
}

private class ListCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("list"),
        description = "list subscriptions for the current sender",
        usage = "list",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val subscriber = SubscriberRepository.findByPlatformAndTarget(
            platformId = invocation.context.platform,
            type = invocation.context.chatType.toSubscriberType(),
            targetId = invocation.context.chatId,
        )
            ?: return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")
        val publisherIds = SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id)
        if (publisherIds.isEmpty()) return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")

        val lines = publisherIds
            .mapNotNull { PublisherRepository.findById(it) }
            .map { "${it.platformId}:${it.externalId} (${it.name})" }

        if (lines.isEmpty()) return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, lines.joinToString("\n"))
    }
}

private class TemplateListCommandHandler(
    private val configProvider: () -> MainDynamicConfig,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "list"),
        description = "list message templates and publisher bindings",
        usage = "template list",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val templateNames = configProvider().templates.keys.sorted()
        val bindings = PublisherTemplateRepository.findPublisherBindings()
            .sortedBy { it.publisherId }
            .map { binding ->
                val publisher = PublisherRepository.findById(binding.publisherId)
                val publisherName = if (publisher != null) {
                    "${publisher.platformId}:${publisher.externalId} (${publisher.name})"
                } else {
                    "publisherId=${binding.publisherId}"
                }
                "$publisherName -> ${binding.templateName}"
            }

        val lines = mutableListOf<String>()
        lines += "templates: ${templateNames.ifEmpty { listOf("(none)") }.joinToString(", ")}"
        lines += "bindings:"
        lines += bindings.ifEmpty { listOf("(none)") }
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, lines.joinToString("\n"))
    }
}

private class TemplateSetCommandHandler(
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "set"),
        description = "bind a publisher to a message template",
        usage = "template set <platform> <publisherUserId> <templateName>",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 3) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val templateName = invocation.args[2]
        val config = configProvider()
        if (!config.templates.containsKey(templateName)) {
            return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "template not found: $templateName",
            )
        }
        val publisher = PublisherRepository.findByPlatformAndExternalId(platform, publisherUserId)
            ?: return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "publisher not found: $platform:$publisherUserId",
            )

        val changed = PublisherTemplateRepository.setPublisherTemplate(publisher.id, templateName)
        val state = if (changed) "updated" else "unchanged"
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "template binding $state: ${publisher.platformId}:${publisher.externalId} -> $templateName",
        )
    }
}

private class TemplateRemoveCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "remove"),
        description = "remove a publisher message template binding",
        usage = "template remove <platform> <publisherUserId>",
        requiredRole = CommandRole.ADMIN,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].lowercase()
        val publisherUserId = invocation.args[1]
        val publisher = PublisherRepository.findByPlatformAndExternalId(platform, publisherUserId)
            ?: return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "publisher not found: $platform:$publisherUserId",
            )

        val removed = PublisherTemplateRepository.removePublisherTemplate(publisher.id)
        return if (removed) {
            CommandExecutionResult(
                CommandExecutionStatus.SUCCESS,
                "template binding removed: ${publisher.platformId}:${publisher.externalId}",
            )
        } else {
            CommandExecutionResult(
                CommandExecutionStatus.SUCCESS,
                "template binding not found: ${publisher.platformId}:${publisher.externalId}",
            )
        }
    }
}

private class FilterAddElementCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "add", "element"),
        description = "add an element block filter for a subscription",
        usage = "filter add element <platform> <publisherUserId> <text|image|video|card|origin>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 3) {
            return failed("usage: $commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0]
        val publisherUserId = invocation.args[1]
        val element = parseDynamicElementType(invocation.args[2])
            ?: return failed("unknown dynamic element: ${invocation.args[2]}")
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }

        val result = DynamicFilterRuleRepository.addElementRule(target.subscription.id, element)
        val state = if (result.created) "created" else "existing"
        return success("filter rule $state: ${formatFilterRule(result.value, target.publisher)}")
    }
}

private class FilterAddContentCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "add", "content"),
        description = "add a content block filter for a subscription",
        usage = "filter add content <platform> <publisherUserId> <keyword|regex> <pattern...>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 4) {
            return failed("usage: $commandPrefix ${spec.usage}")
        }

        val platform = invocation.args[0]
        val publisherUserId = invocation.args[1]
        val matcher = parseContentMatcher(invocation.args[2])
            ?: return failed("unknown content matcher: ${invocation.args[2]}")
        val pattern = invocation.args.drop(3).joinToString(" ").trim()
        if (pattern.isBlank()) {
            return failed("filter pattern must not be blank")
        }
        val target = when (val resolved = resolveFilterTarget(invocation, platform, publisherUserId)) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }

        val result = try {
            DynamicFilterRuleRepository.addContentRule(target.subscription.id, matcher, pattern)
        } catch (e: IllegalArgumentException) {
            return failed("filter rule rejected: ${e.message}")
        }
        val state = if (result.created) "created" else "existing"
        return success("filter rule $state: ${formatFilterRule(result.value, target.publisher)}")
    }
}

private class FilterListCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "list"),
        description = "list subscription filters for the current target",
        usage = "filter list [platform] [publisherUserId]",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        return when (invocation.args.size) {
            0 -> listAll(invocation)
            2 -> listOne(invocation, invocation.args[0], invocation.args[1])
            else -> failed("usage: $commandPrefix ${spec.usage}")
        }
    }

    private fun listAll(invocation: CommandInvocation): CommandExecutionResult {
        val subscriber = invocation.currentSubscriber() ?: return success("filters: (none)")
        val targets = SubscriptionRepository
            .findPublisherIdsBySubscriberId(subscriber.id)
            .mapNotNull { publisherId ->
                val publisher = PublisherRepository.findById(publisherId) ?: return@mapNotNull null
                val subscription = SubscriptionRepository.findBySubscriberAndPublisher(subscriber.id, publisher.id)
                    ?: return@mapNotNull null
                ResolvedFilterTarget(publisher, subscriber, subscription)
            }
            .sortedWith(compareBy<ResolvedFilterTarget> { it.publisher.platformId }.thenBy { it.publisher.externalId })

        if (targets.isEmpty()) return success("filters: (none)")

        val rulesBySubscriptionId = DynamicFilterRuleRepository.findBySubscriptionIds(targets.map { it.subscription.id })
        val lines = targets.flatMap { target ->
            rulesBySubscriptionId[target.subscription.id]
                .orEmpty()
                .sortedBy { it.id }
                .map { rule -> formatFilterRule(rule, target.publisher) }
        }

        return success(lines.ifEmpty { listOf("filters: (none)") }.joinToString("\n"))
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

        return success(lines.ifEmpty { listOf("filters: (none)") }.joinToString("\n"))
    }
}

private class FilterRemoveCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "remove"),
        description = "remove a subscription filter",
        usage = "filter remove <ruleId>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 1) {
            return failed("usage: $commandPrefix ${spec.usage}")
        }
        val ruleId = invocation.args.single().toIntOrNull()
            ?: return failed("invalid filter rule id: ${invocation.args.single()}")
        val rule = DynamicFilterRuleRepository.findById(ruleId)
            ?: return failed("filter rule not found: #$ruleId")
        if (!rule.belongsToCurrentSubscriber(invocation)) {
            return failed("filter rule not found: #$ruleId")
        }

        val removed = DynamicFilterRuleRepository.removeById(ruleId)
        return if (removed) {
            success("filter rule removed: #$ruleId")
        } else {
            failed("filter rule not found: #$ruleId")
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
        description = "clear subscription filters",
        usage = "filter clear <platform> <publisherUserId>",
        requiredRole = CommandRole.USER,
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size != 2) {
            return failed("usage: $commandPrefix ${spec.usage}")
        }
        val target = when (val resolved = resolveFilterTarget(invocation, invocation.args[0], invocation.args[1])) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(target.subscription.id)
        return success("filter rules cleared: count=$removed")
    }
}

private fun DynamicFilterRule.belongsToCurrentSubscriber(invocation: CommandInvocation): Boolean {
    val subscriber = invocation.currentSubscriber() ?: return false
    val subscription = SubscriptionRepository.findById(subscriptionId) ?: return false
    return subscription.subscriberId == subscriber.id
}
