package top.colter.dynamic.command

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandExecutionStatus
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.DefaultConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.LazyImage
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
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
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.PublisherTemplateRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscribeRepository
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

public class CommandListener(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
    private val commandPrefix: String = "/db",
    config: MainDynamicConfig? = null,
    private val configService: ConfigService = DefaultConfigService,
) : Listener<CommandEvent> {
    private companion object {
        private const val MAIN_OWNER: String = "main"
    }

    private val config: MainDynamicConfig by lazy {
        config ?: configService.loadOrCreate(MainDynamicConfig.CONFIG_ID) { MainDynamicConfig() }
    }

    init {
        registerBuiltins()
    }

    override suspend fun onMessage(event: CommandEvent) {
        val tokens = event.commandTokens.ifEmpty {
            buildList {
                if (event.commandName.isNotBlank()) {
                    add(event.commandName)
                }
                addAll(event.args)
            }
        }
        if (tokens.isEmpty()) return

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

        if (!event.context.role.satisfies(match.spec.requiredRole)) {
            reply(event, CommandExecutionResult(CommandExecutionStatus.REJECTED, "permission denied"))
            return
        }

        val invocationEvent = event.copy(
            commandName = match.spec.path.last(),
            args = match.args,
            matchedPath = match.matchedPath,
            commandTokens = tokens,
        )

        val result = runCatching { match.handler.handle(invocationEvent) }
            .getOrElse { CommandExecutionResult(CommandExecutionStatus.FAILED, "command failed: ${it.message}") }

        reply(invocationEvent, result)
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
        CommandRegistry.register(HelpCommandHandler(commandPrefix), MAIN_OWNER)
        CommandRegistry.register(StatusCommandHandler(), MAIN_OWNER)
        CommandRegistry.register(SubscribeCommandHandler(platformPluginResolver), MAIN_OWNER)
        CommandRegistry.register(LoginCommandHandler(platformPluginResolver, commandPrefix), MAIN_OWNER)
        CommandRegistry.register(UnsubscribeCommandHandler(), MAIN_OWNER)
        CommandRegistry.register(ListCommandHandler(), MAIN_OWNER)
        CommandRegistry.register(TemplateListCommandHandler { config }, MAIN_OWNER)
        CommandRegistry.register(TemplateSetCommandHandler({ config }, commandPrefix), MAIN_OWNER)
        CommandRegistry.register(TemplateRemoveCommandHandler(commandPrefix), MAIN_OWNER)
    }
}

private class HelpCommandHandler(
    private val commandPrefix: String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("help"),
        description = "show available commands",
        usage = "help",
        requiredRole = CommandRole.USER,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        val visibleCommands = CommandRegistry.visibleCommandsFor(event.context.role)
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
    private val commandPrefix: String,
    private val qrCodeRenderer: LoginQrCodeRenderer = LoginQrCodeRenderer(),
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("login"),
        description = "login a publisher platform account",
        usage = "login <platform> <cookie|qr> [cookie|sessionId]",
        requiredRole = CommandRole.ADMIN,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return failedUsage()
        }

        val platform = event.args[0].lowercase()
        val method = event.args[1].lowercase()
        val plugin = platformPluginResolver(platform)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "platform plugin not found: $platform")

        return when (method) {
            "cookie" -> handleCookieLogin(plugin, platform, event.args.drop(2).joinToString(" ").trim())
            "qr", "qrcode", "qr-code" -> handleQrLogin(plugin, platform, event.args.drop(2).firstOrNull())
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
        sessionId: String?,
    ): CommandExecutionResult {
        if (!plugin.supportedLoginMethods.contains(PublisherLoginMethod.QR_CODE)) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "QR code login is unsupported on $platform")
        }

        if (sessionId.isNullOrBlank()) {
            val challenge = runCatching { plugin.startQrLogin() }
                .getOrElse { throwable ->
                    return CommandExecutionResult(
                        CommandExecutionStatus.FAILED,
                        throwable.message ?: "failed to start QR code login",
                    )
                }
                ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "failed to start QR code login")
            return renderQrChallenge(platform, challenge)
        }

        val result = runCatching { plugin.pollQrLogin(sessionId) }
            .getOrElse { throwable ->
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = throwable.message ?: "QR code login failed",
                )
            }
        return toCommandResult(platform, result)
    }

    private fun renderQrChallenge(platform: String, challenge: PublisherQrLoginChallenge): CommandExecutionResult {
        val expiresText = challenge.expiresAtEpochSeconds?.let { "expiresAt=$it" } ?: "expiresAt=unknown"
        val message = buildString {
            appendLine("$platform QR login started.")
            appendLine("sessionId=${challenge.sessionId}")
            appendLine("scan the QR code, then run: $commandPrefix login $platform qr ${challenge.sessionId}")
            append(expiresText)
            challenge.message?.takeIf { it.isNotBlank() }?.let { appendLine().append(it) }
        }

        val imagePath = runCatching {
            qrCodeRenderer.render(challenge.qrContent, challenge.sessionId)
        }.getOrNull()

        val contents = buildList {
            imagePath?.let { path ->
                add(MessageContent.Image(text = "", image = LazyImage(path)))
            }
            add(MessageContent.Text(message))
        }
        return CommandExecutionResult(
            status = CommandExecutionStatus.SUCCESS,
            message = message,
            chain = listOf(MessageChain(contents)),
        )
    }

    private fun toCommandResult(platform: String, result: PublisherLoginResult): CommandExecutionResult {
        val status = when (result.status) {
            PublisherLoginStatus.SUCCESS,
            PublisherLoginStatus.PENDING -> CommandExecutionStatus.SUCCESS
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
    fun render(content: String, sessionId: String): String {
        Files.createDirectories(outputDir)
        val safeSessionId = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outputPath = outputDir.resolve("$safeSessionId.png").toAbsolutePath()
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
        description = "show command registry status",
        usage = "status",
        requiredRole = CommandRole.ADMIN,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        val commandCount = CommandRegistry.listCommands().size
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "ok, commands=$commandCount")
    }
}

private class SubscribeCommandHandler(
    private val platformPluginResolver: (String) -> PlatformPublisherPlugin?,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("subscribe"),
        description = "subscribe to a publisher on a supported platform",
        usage = "subscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.USER,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: /db ${spec.usage}")
        }

        val platform = event.args[0].lowercase()
        val publisherUserId = event.args[1]
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

        val publisherUpsert = PublisherRepository.upsert(profile)
        val subscriber = SubscriberRepository.ensure(
            platform = event.context.platform,
            userId = event.context.senderId,
            name = event.context.senderId,
        )
        val created = SubscribeRepository.subscribe(subscriber.id.toString(), publisherUpsert.value.id.toString())

        val publisherState = if (publisherUpsert.created) "new" else "existing"
        val subscriptionState = if (created) "new" else "existing"
        val followStateText = if (autoFollowed) "yes" else "no"
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "subscribed: ${publisherUpsert.value.name ?: publisherUpsert.value.userId} " +
                "(auto-followed=$followStateText, publisher=$publisherState, subscription=$subscriptionState)",
        )
    }
}

private class UnsubscribeCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("unsubscribe"),
        description = "unsubscribe from a publisher",
        usage = "unsubscribe <platform> <publisherUserId>",
        requiredRole = CommandRole.USER,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: /db ${spec.usage}")
        }
        val platform = event.args[0].lowercase()
        val publisherUserId = event.args[1]
        val publisher = PublisherRepository.findByPlatformAndUserId(platform, publisherUserId)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher not found: $platform:$publisherUserId")

        val subscriber = SubscriberRepository.findByPlatformAndUserId(event.context.platform, event.context.senderId)
            ?: return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")
        val removed = SubscribeRepository.unsubscribe(subscriber.id.toString(), publisher.id.toString())
        return if (removed) {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "unsubscribed: ${publisher.name ?: publisher.userId}")
        } else {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "not subscribed: ${publisher.name ?: publisher.userId}")
        }
    }
}

private class ListCommandHandler : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("list"),
        description = "list subscriptions for the current sender",
        usage = "list",
        requiredRole = CommandRole.USER,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        val subscriber = SubscriberRepository.findByPlatformAndUserId(event.context.platform, event.context.senderId)
            ?: return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")
        val publisherIds = SubscribeRepository.findPublisherIdsBySubscriberId(subscriber.id.toString())
        if (publisherIds.isEmpty()) return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "no subscriptions")

        val lines = publisherIds.mapNotNull { id -> id.toIntOrNull() }
            .mapNotNull { PublisherRepository.findById(it) }
            .map { "${it.platform}:${it.userId} (${it.name ?: "unknown"})" }

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
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        val templateNames = configProvider().templates.keys.sorted()
        val bindings = PublisherTemplateRepository.findAll()
            .sortedWith(compareBy({ it.publisherId.toIntOrNull() ?: Int.MAX_VALUE }, { it.publisherId }))
            .map { binding ->
                val publisher = binding.publisherId.toIntOrNull()?.let { PublisherRepository.findById(it) }
                val publisherName = if (publisher != null) {
                    "${publisher.platform}:${publisher.userId} (${publisher.name ?: "unknown"})"
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
    private val commandPrefix: String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "set"),
        description = "bind a publisher to a message template",
        usage = "template set <platform> <publisherUserId> <templateName>",
        requiredRole = CommandRole.ADMIN,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 3) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }
        val platform = event.args[0].lowercase()
        val publisherUserId = event.args[1]
        val templateName = event.args[2]
        val config = configProvider()
        if (!config.templates.containsKey(templateName)) {
            return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "template not found: $templateName",
            )
        }
        val publisher = PublisherRepository.findByPlatformAndUserId(platform, publisherUserId)
            ?: return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "publisher not found: $platform:$publisherUserId",
            )

        val changed = PublisherTemplateRepository.setTemplate(publisher.id.toString(), templateName)
        val state = if (changed) "updated" else "unchanged"
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "template binding $state: ${publisher.platform}:${publisher.userId} -> $templateName",
        )
    }
}

private class TemplateRemoveCommandHandler(
    private val commandPrefix: String,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("template", "remove"),
        description = "remove a publisher message template binding",
        usage = "template remove <platform> <publisherUserId>",
        requiredRole = CommandRole.ADMIN,
        ownerPluginId = "main",
    )

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: $commandPrefix ${spec.usage}")
        }
        val platform = event.args[0].lowercase()
        val publisherUserId = event.args[1]
        val publisher = PublisherRepository.findByPlatformAndUserId(platform, publisherUserId)
            ?: return CommandExecutionResult(
                CommandExecutionStatus.FAILED,
                "publisher not found: $platform:$publisherUserId",
            )

        val removed = PublisherTemplateRepository.removeTemplate(publisher.id.toString())
        return if (removed) {
            CommandExecutionResult(
                CommandExecutionStatus.SUCCESS,
                "template binding removed: ${publisher.platform}:${publisher.userId}",
            )
        } else {
            CommandExecutionResult(
                CommandExecutionStatus.SUCCESS,
                "template binding not found: ${publisher.platform}:${publisher.userId}",
            )
        }
    }
}
