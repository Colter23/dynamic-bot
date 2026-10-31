package top.colter.dynamic.command

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
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
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.core.config.loadOrCreate
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.DynamicFilterAction
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
import top.colter.dynamic.core.data.SubscriptionEventKind
import top.colter.dynamic.core.data.SubscriptionPolicy
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.TextMatchMode
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.Listener
import top.colter.dynamic.incoming.IncomingBotAccountSelector
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
import top.colter.dynamic.plugin.PluginInfo
import top.colter.dynamic.plugin.PluginState
import top.colter.dynamic.plugin.PluginTaskInfo
import top.colter.dynamic.publisher.PublisherLatestUpdateService
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.util.formatTime
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
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
    private val latestPublisherUpdateService: PublisherLatestUpdateService? = null,
    config: MainDynamicConfig? = null,
    configProvider: (() -> MainDynamicConfig)? = null,
    private val stopRequester: ((String) -> Unit)? = null,
    private val commandBootstrapService: CommandBootstrapService? = null,
    private val subscriptionComponent: SubscriptionSelectionComponent? = null,
    private val configUpdater: (((MainDynamicConfig) -> MainDynamicConfig) -> ConfigApplyResult)? = null,
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val publisherDrawThemeService: PublisherDrawThemeService = PublisherDrawThemeService(),
    private val incomingBotAccountSelector: IncomingBotAccountSelector = IncomingBotAccountSelector(),
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(),
    private val pluginInfoProvider: () -> List<PluginInfo> = { emptyList() },
    private val mainTaskSnapshotProvider: () -> List<TaskSnapshot> = { emptyList() },
    private val pluginTaskInfoProvider: () -> List<PluginTaskInfo> = { emptyList() },
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
    publisherThemeInitializer: PublisherThemeInitializer? = null,
    private val incomingProcessingRecorder: (IncomingProcessingWriteRequest) -> Boolean = {
        IncomingMessageAuditRepository.recordProcessing(it)
    },
) : Listener<CommandEvent> {
    private companion object {
        private const val MAIN_OWNER: String = "main"
        private const val PERMISSION_DENIED_NOTIFY_INTERVAL_MILLIS: Long = 10 * 60 * 1000L
        private const val PERMISSION_DENIED_NOTIFY_MAX_ENTRIES: Int = 512

        /** 即使没有命中任何权限规则也必须可用的命令。 */
        private val PERMISSION_FREE_COMMAND_PATHS: Set<List<String>> = setOf(
            listOf("help"),
            listOf("whoami"),
            listOf("claim"),
        )
    }

    /** 权限拒绝提示的去重时间戳，键 = senderId|commandPath。 */
    private val permissionDeniedNotifiedAt: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

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

    /** 订阅命令与"#序号 选择"共用同一个候选缓存；未注入时按默认依赖自建。 */
    private val runtimeSubscriptionComponent: SubscriptionSelectionComponent by lazy {
        subscriptionComponent ?: SubscriptionSelectionComponent(
            SubscriptionService(
                publisherLookupResolver = publisherLookupResolver,
                publisherFollowResolver = publisherFollowResolver,
                configProvider = runtimeConfigProvider,
                eventBus = eventBus,
                themeInitializer = runtimePublisherThemeInitializer,
            ),
        )
    }

    /** `list` 输出的顺序快照，供 `unsubscribe #序号` 使用。 */
    private val listSnapshots: SubscriptionListSnapshotStore = SubscriptionListSnapshotStore()

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
        val commandConfig = runtimeConfig.command
        val defaultRole = if (commandConfig.requirePermissionRule) CommandRole.NONE else CommandRole.USER
        val permissionResolver = CommandPermissionResolver(commandConfig.permissions)
        if (match == null) {
            val role = permissionResolver.resolve(
                context = event.context,
                commandPath = tokens,
                defaultRole = defaultRole,
            )
            if (!role.satisfies(CommandRole.USER)) {
                recordCommandProcessing(
                    event = event,
                    stage = IncomingProcessingStage.COMMAND_PARSE,
                    handlerId = "command-permission",
                    result = IncomingProcessingResult.REJECTED,
                    commandPath = tokens.joinToString(" "),
                    role = role.name,
                    errorMessage = "权限不足",
                )
                return
            }
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

        val role = permissionResolver.resolve(
            context = event.context,
            commandPath = match.spec.path,
            defaultRole = defaultRole,
        )
        val permissionFree = isPermissionFree(match.spec.path)
        if (!permissionFree && !role.satisfies(match.spec.requiredRole)) {
            recordCommandProcessing(
                event = event,
                stage = IncomingProcessingStage.COMMAND_EXECUTE,
                handlerId = match.spec.path.joinToString("/"),
                result = IncomingProcessingResult.REJECTED,
                commandPath = commandPath,
                role = role.name,
                errorMessage = "权限不足",
            )
            replyPermissionDenied(event, match.spec)
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
            // 免权限命令至少要按普通用户执行，否则 help 会因为角色是 NONE 而列出空列表。
            role = if (permissionFree && role.level < CommandRole.USER.level) CommandRole.USER else role,
        )

        val started = System.nanoTime()
        val result = try {
            match.handler.handle(invocation)
        } catch (e: CancellationException) {
            // 取消必须原样抛出：关机/超时时不能被当成"命令执行失败"再走一遍审计与回复。
            throw e
        } catch (error: Throwable) {
            CommandExecutionResult.failed("命令执行失败：${error.message}")
        }
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
        try {
            result.afterReply?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            commandListenerLogger.warn(error) { "命令收尾动作失败：commandPath=$commandPath" }
        }
    }

    private suspend fun reply(event: CommandEvent, result: CommandExecutionResult) {
        // 空回复表示"不回复"：用于去重后的静默处理，避免向出口发一条空消息。
        if (result.reply.isEmpty()) return
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
        ).let { eventBus.dispatch(it) }
    }

    private fun isPermissionFree(path: List<String>): Boolean {
        return path.map { it.lowercase() } in PERMISSION_FREE_COMMAND_PATHS
    }

    /**
     * 权限不足必须给出反馈，否则用户会以为 Bot 坏了。
     * 但同一个人对同一条命令 10 分钟内只提示一次，避免被用来刷屏。
     */
    private suspend fun replyPermissionDenied(event: CommandEvent, spec: CommandSpec) {
        val commandPath = spec.path.joinToString(" ")
        val key = "${event.context.senderId}|$commandPath"
        val now = System.currentTimeMillis()
        val previous = permissionDeniedNotifiedAt[key]
        if (previous != null && now - previous < PERMISSION_DENIED_NOTIFY_INTERVAL_MILLIS) return
        if (permissionDeniedNotifiedAt.size >= PERMISSION_DENIED_NOTIFY_MAX_ENTRIES) {
            permissionDeniedNotifiedAt.entries.removeIf { now - it.value >= PERMISSION_DENIED_NOTIFY_INTERVAL_MILLIS }
        }
        permissionDeniedNotifiedAt[key] = now
        reply(
            event,
            CommandExecutionResult.failed(
                "没有该命令的权限：$commandPrefix $commandPath（需要${commandRoleLabel(spec.requiredRole)}）",
            ),
        )
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
        val rateLimiter = CommandRateLimiter()
        commandRegistry.register(
            HelpCommandHandler(commandPrefixProvider, commandRegistry, commandBootstrapService),
            MAIN_OWNER,
        )
        commandRegistry.register(
            StatusCommandHandler(
                commandRegistry = commandRegistry,
                pluginInfoProvider = pluginInfoProvider,
                mainTaskSnapshotProvider = mainTaskSnapshotProvider,
                pluginTaskInfoProvider = pluginTaskInfoProvider,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
            MAIN_OWNER,
        )
        stopRequester?.let {
            commandRegistry.register(StopApplicationCommandHandler(it, commandPrefixProvider), MAIN_OWNER)
        }
        commandRegistry.register(
            RateLimitedCommandHandler(
                delegate = LinkParseCommandHandler(
                    linkParseService = linkParseService,
                    commandPrefixProvider = commandPrefixProvider,
                    maxLinksProvider = { runtimeConfig.linkParsing.maxLinksPerMessage },
                ),
                limiter = rateLimiter,
                perSenderLimit = 5,
                perTargetLimit = 20,
            ),
            MAIN_OWNER,
        )
        latestPublisherUpdateService?.let { service ->
            commandRegistry.register(
                RateLimitedCommandHandler(
                    delegate = PublisherLatestUpdateCommandHandler(service, commandPrefixProvider),
                    limiter = rateLimiter,
                    perSenderLimit = 3,
                    perTargetLimit = 20,
                ),
                MAIN_OWNER,
            )
        }
        LinkParseConfigCommandHandler.handlers({ runtimeConfig }, commandPrefixProvider)
            .forEach { handler -> commandRegistry.register(handler, MAIN_OWNER) }
        commandRegistry.register(
            RateLimitedCommandHandler(
                delegate = SubscribeCommandHandler(runtimeSubscriptionComponent, commandPrefixProvider),
                limiter = rateLimiter,
                perSenderLimit = 5,
                perTargetLimit = 10,
            ),
            MAIN_OWNER,
        )
        commandRegistry.register(LoginCommandHandler(publisherLoginResolver, commandPrefixProvider, eventBus), MAIN_OWNER)
        commandRegistry.register(ForwardCommandHandler(commandPrefixProvider, outboundMessageService), MAIN_OWNER)
        commandRegistry.register(
            UnsubscribeCommandHandler(
                publisherFollowResolver,
                { runtimeConfig },
                commandPrefixProvider,
                eventBus,
                listSnapshots,
            ),
            MAIN_OWNER,
        )
        commandRegistry.register(ListCommandHandler(listSnapshots, commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(TemplateListCommandHandler { runtimeConfig }, MAIN_OWNER)
        commandRegistry.register(FilterAddElementCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterAddContentCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterListCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterRemoveCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(FilterClearCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(ThemeCommandHandler(commandPrefixProvider, publisherDrawThemeService), MAIN_OWNER)
        commandRegistry.register(PolicyCommandHandler(commandPrefixProvider), MAIN_OWNER)
        commandRegistry.register(WhoamiCommandHandler(), MAIN_OWNER)
        commandBootstrapService?.let { service ->
            commandRegistry.register(ClaimCommandHandler(service, commandPrefixProvider), MAIN_OWNER)
        }
        configUpdater?.let { updater ->
            commandRegistry.register(
                GrantCommandHandler(updater, commandPrefixProvider),
                MAIN_OWNER,
            )
            commandRegistry.register(
                RevokeCommandHandler(updater, commandPrefixProvider),
                MAIN_OWNER,
            )
        }
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

/** 单条回复的字符上限；超过就拆成多条消息，避免超出平台单条消息长度限制。 */
internal const val MAX_REPLY_CHARS_PER_BATCH: Int = 1_200

/** 按行拆分成长度受控的多条消息。 */
internal fun splitIntoBatches(lines: List<String>): List<MessageBatch> {
    if (lines.isEmpty()) return emptyList()
    val batches = mutableListOf<MessageBatch>()
    val current = StringBuilder()

    fun flush() {
        if (current.isEmpty()) return
        batches += MessageBatch(listOf(MessageContent.Text(current.toString())))
        current.setLength(0)
    }

    lines.forEach { line ->
        if (line.length > MAX_REPLY_CHARS_PER_BATCH) {
            flush()
            line.chunked(MAX_REPLY_CHARS_PER_BATCH).forEach { chunk ->
                batches += MessageBatch(listOf(MessageContent.Text(chunk)))
            }
            return@forEach
        }
        val needed = if (current.isEmpty()) line.length else line.length + 1
        if (current.length + needed > MAX_REPLY_CHARS_PER_BATCH) flush()
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
    }
    flush()
    return batches
}

/** 长文本回复：超过上限时自动分成多条消息。 */
private fun successBatched(lines: List<String>): CommandExecutionResult {
    return CommandExecutionResult(
        status = CommandStatus.SUCCESS,
        reply = splitIntoBatches(lines),
    )
}

private fun commandRoleLabel(role: CommandRole): String {
    return when (role) {
        CommandRole.ADMIN -> "系统管理员"
        CommandRole.MANAGER -> "目标管理员"
        CommandRole.USER -> "普通用户"
        CommandRole.NONE -> "配置授权"
    }
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

private fun formatFilterRule(
    rule: DynamicFilterRule,
    publisher: Publisher? = null,
    commandPrefix: String? = null,
): String {
    val owner = publisher?.let { "${it.platformId.value}:${it.externalId}" } ?: "subscriptionId=${rule.subscriptionId}"
    val head = "#${rule.id} $owner ${rule.action.name.lowercase()} ${rule.condition}"
    // 附上可直接复制的重建命令：规则删掉也能照着加回来，不必重写正则。
    val rebuild = commandPrefix?.let { rebuildFilterCommand(rule, it, publisher) } ?: return head
    return "$head\n    重建：$rebuild"
}

private fun rebuildFilterCommand(
    rule: DynamicFilterRule,
    commandPrefix: String,
    publisher: Publisher?,
): String? {
    if (publisher == null || rule.action != DynamicFilterAction.BLOCK) return null
    val platform = publisher.platformId.value
    val externalId = publisher.externalId
    return when (val condition = rule.condition) {
        is FilterCondition.HasElement ->
            "$commandPrefix filter add element $platform $externalId ${condition.kind.name.lowercase()}"
        is FilterCondition.TextMatch -> {
            val matcher = when (condition.mode) {
                TextMatchMode.REGEX -> "regex"
                TextMatchMode.CONTAINS -> "keyword"
            }
            "$commandPrefix filter add content $platform $externalId $matcher ${condition.text}"
        }
    }
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
    private val commandRegistry: CommandRegistry,
    private val commandBootstrapService: CommandBootstrapService? = null,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("help"),
        aliases = listOf(listOf("帮助")),
        description = "显示可用命令；加命令名可查看详细用法",
        usage = "help [命令]",
        requiredRole = CommandRole.USER,
        category = "系统",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val initialized = commandBootstrapService?.snapshot()?.status == CommandBootstrapStatus.CLAIMED
        val visibleCommands = commandRegistry.visibleCommandsFor(invocation.role)
            // 已初始化后不再展示认领命令，避免用户误以为还能认领。
            .filterNot { initialized && it.path.map { segment -> segment.lowercase() } == listOf("claim") }
        if (visibleCommands.isEmpty()) {
            return CommandExecutionResult.success("没有可用命令")
        }

        // /db help <命令>：查看单条命令的用法、说明与所需权限
        if (invocation.args.isNotEmpty()) {
            val matched = commandRegistry.match(invocation.args)
            if (matched == null) {
                // 允许用前缀查看一组命令，例如 `help filter`
                val children = subCommandsOf(invocation.args, visibleCommands)
                if (children.isEmpty()) {
                    return failed("未找到命令：${invocation.args.joinToString(" ")}")
                }
                return successBatched(children.map { formatCommand(it) })
            }
            if (!invocation.role.satisfies(matched.spec.requiredRole)) {
                return failed("没有该命令的权限：${matched.spec.path.joinToString(" ")}")
            }
            return success(describeCommand(matched.spec))
        }

        return successBatched(buildGroupedHelp(visibleCommands))
    }

    /** 按路径前缀过滤出一组子命令，用于 `help filter` 这类查询。 */
    private fun subCommandsOf(prefixTokens: List<String>, commands: List<CommandSpec>): List<CommandSpec> {
        val prefix = prefixTokens.map { it.lowercase() }
        return commands.filter { spec ->
            val path = spec.path.map { it.lowercase() }
            path.size > prefix.size && path.take(prefix.size) == prefix
        }
    }

    private fun buildGroupedHelp(commands: List<CommandSpec>): List<String> {
        val lines = mutableListOf<String>()
        // 主程序保留的分组按固定顺序展示；插件自定义分组按名称排序；未分组归入「其它」。
        val known = KNOWN_COMMAND_CATEGORIES.filter { name -> commands.any { it.category == name } }
        val extra = commands.map { it.category }
            .filter { it.isNotBlank() && it !in KNOWN_COMMAND_CATEGORIES }
            .distinct()
            .sorted()
        (known + extra).forEach { category ->
            val items = commands.filter { it.category == category }
            if (items.isEmpty()) return@forEach
            if (lines.isNotEmpty()) lines += ""
            lines += "【$category】"
            items.forEach { lines += formatCommand(it) }
        }
        val others = commands.filter { it.category.isBlank() }
        if (others.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += "【其它】"
            others.forEach { lines += formatCommand(it) }
        }
        return lines
    }

    private fun formatCommand(spec: CommandSpec): String {
        val usage = "$commandPrefix ${spec.usage}".trim()
        return if (spec.description.isBlank()) usage else "$usage - ${spec.description}"
    }

    private fun describeCommand(spec: CommandSpec): String {
        return buildString {
            appendLine("命令：$commandPrefix ${spec.path.joinToString(" ")}")
            if (spec.aliases.isNotEmpty()) {
                appendLine("别名：${spec.aliases.joinToString("、") { "$commandPrefix ${it.joinToString(" ")}" }}")
            }
            appendLine("用法：$commandPrefix ${spec.usage}")
            appendLine("说明：${spec.description.ifBlank { "-" }}")
            append("权限：${commandRoleLabel(spec.requiredRole)}")
        }
    }

}

/** 主程序保留的分组与展示顺序；插件应使用自己的分组名，不要占用这些名字。 */
private val KNOWN_COMMAND_CATEGORIES: List<String> = listOf("订阅", "链接", "内容", "过滤", "管理", "系统")

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
        category = "管理",
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
            challenge.qrImageBytes?.let(qrCodeRenderer::writePng)
                ?: challenge.qrContent?.let(qrCodeRenderer::render)
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

    fun writePng(bytes: ByteArray): String {
        Files.createDirectories(outputDir)
        val outputPath = outputDir
            .resolve("qr-${System.currentTimeMillis()}-${bytes.contentHashCode().toUInt().toString(16)}.png")
            .toAbsolutePath()
        Files.write(outputPath, bytes)
        return outputPath.toString()
    }
}

private class StatusCommandHandler(
    private val commandRegistry: CommandRegistry,
    private val pluginInfoProvider: () -> List<PluginInfo>,
    private val mainTaskSnapshotProvider: () -> List<TaskSnapshot>,
    private val pluginTaskInfoProvider: () -> List<PluginTaskInfo>,
    private val startedAtEpochMillis: Long,
) : CommandHandler {
    override val spec: CommandSpec = CommandSpec(
        path = listOf("status"),
        aliases = listOf(listOf("health"), listOf("状态")),
        description = "显示项目运行状态",
        usage = "status",
        requiredRole = CommandRole.ADMIN,
        category = "系统",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val commandCount = commandRegistry.listCommands().size
        val publisherCount = PublisherRepository.countAll()
        val subscriberCount = SubscriberRepository.countAll()
        val subscriptionCount = SubscriptionRepository.countAll()
        val plugins = pluginInfoProvider()
        val mainTasks = mainTaskSnapshotProvider()
        val pluginTasks = pluginTaskInfoProvider()
        val allTasks = mainTasks + pluginTasks.map { it.task }
        val deliveryStatusCounts = MessageDeliveryRepository.countsByStatus(includeInternalRecords = false)
        val deliveryHealth = MessageDeliveryRepository.healthSummary(
            recentWindowSeconds = STATUS_DELIVERY_HEALTH_WINDOW_SECONDS,
            includeInternalRecords = false,
            statusCounts = deliveryStatusCounts,
        )
        val summary = overallStatusText(plugins, allTasks, deliveryHealth.needsAttentionCount)
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0)
        val startedAt = (startedAtEpochMillis / 1_000).formatTime()
        val uptime = formatDurationMillis(System.currentTimeMillis() - startedAtEpochMillis)
        val taskText = if (allTasks.isEmpty()) {
            "无任务"
        } else {
            "主任务 ${mainTasks.countByStatus(TaskStatus.RUNNING)}/${mainTasks.size} 运行，" +
                "插件任务 ${pluginTasks.count { it.task.status == TaskStatus.RUNNING }}/${pluginTasks.size} 运行，" +
                "失败 ${allTasks.countByStatus(TaskStatus.FAILED)}"
        }

        return CommandExecutionResult.success(
            listOf(
                "动态 Bot 状态：$summary",
                "运行：$uptime，启动=$startedAt",
                "系统：Java ${System.getProperty("java.version").orEmpty()}，内存 ${formatBytes(usedMemory)}/${formatBytes(runtime.maxMemory())}",
                "插件：${plugins.size} 个，运行 ${plugins.count { it.state == PluginState.ACTIVE }}，已加载 ${plugins.count { it.state == PluginState.LOADED }}，失败 ${plugins.count { it.state == PluginState.FAILED }}",
                "任务：$taskText",
                "数据：发布者=$publisherCount，消息目标=$subscriberCount，订阅=$subscriptionCount，命令=$commandCount",
                "投递：待发=${deliveryHealth.pendingCount}，发送中=${deliveryHealth.sendingCount}，近24小时失败=${deliveryHealth.recentFailedCount}，不确定=${deliveryHealth.sendUnknownCount}，部分成功=${deliveryHealth.partiallySentCount}",
            ).joinToString("\n"),
        )
    }

    private fun overallStatusText(
        plugins: List<PluginInfo>,
        tasks: List<TaskSnapshot>,
        deliveryNeedsAttention: Long,
    ): String {
        return when {
            plugins.any { it.state == PluginState.FAILED } || tasks.any { it.status == TaskStatus.FAILED } -> "异常"
            deliveryNeedsAttention > 0 -> "需要关注"
            else -> "正常"
        }
    }

    private fun List<TaskSnapshot>.countByStatus(status: TaskStatus): Int {
        return count { it.status == status }
    }

    private fun formatDurationMillis(value: Long): String {
        var seconds = (value / 1_000).coerceAtLeast(0)
        if (seconds < 60) return "${seconds}秒"
        val days = seconds / 86_400
        seconds %= 86_400
        val hours = seconds / 3_600
        seconds %= 3_600
        val minutes = seconds / 60
        return buildList {
            if (days > 0) add("${days}天")
            if (hours > 0) add("${hours}小时")
            if (minutes > 0 || isEmpty()) add("${minutes}分钟")
        }.joinToString("")
    }

    private fun formatBytes(value: Long): String {
        val megabytes = (value / BYTES_PER_MEGABYTE).coerceAtLeast(0)
        return if (megabytes >= 1_024) {
            "${megabytes / 1_024}GB"
        } else {
            "${megabytes}MB"
        }
    }

    private companion object {
        private const val STATUS_DELIVERY_HEALTH_WINDOW_SECONDS: Long = 24L * 60L * 60L
        private const val BYTES_PER_MEGABYTE: Long = 1_024L * 1_024L
    }
}

private class StopApplicationCommandHandler(
    private val stopRequester: (String) -> Unit,
    private val commandPrefixProvider: () -> String,
    private val confirmWindowMillis: Long = STOP_CONFIRM_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    /** 待确认的停止请求，键 = 目标会话 + 发送者，值是过期时间。 */
    private val pendingConfirmation = ConcurrentHashMap<String, Long>()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("stop"),
        aliases = listOf(listOf("shutdown")),
        description = "停止主程序",
        usage = "stop",
        requiredRole = CommandRole.ADMIN,
        category = "系统",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val confirmKey = "${invocation.context.target.stableValue()}|${invocation.context.senderId}"
        val args = invocation.args.map { it.lowercase() }

        if (args == listOf("confirm")) {
            val expiresAt = pendingConfirmation.remove(confirmKey)
            if (expiresAt == null || clock() > expiresAt) {
                return CommandExecutionResult.failed("没有待确认的停止请求，已过期或未发起。")
            }
            return CommandExecutionResult.success(
                message = "已请求停止，程序正在关闭",
                afterReply = {
                    stopRequester("command:${invocation.context.target.stableValue()}")
                },
            )
        }

        if (args.isNotEmpty()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }

        pendingConfirmation[confirmKey] = clock() + confirmWindowMillis
        return CommandExecutionResult.success(
            "确认停止？请在 ${confirmWindowMillis / 1000} 秒内回复 $commandPrefix stop confirm",
        )
    }

    private companion object {
        private const val STOP_CONFIRM_WINDOW_MILLIS: Long = 60_000L
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
        category = "管理",
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
        if (targetIds.size > MAX_FORWARD_TARGETS) {
            return failed("一次最多转发到 $MAX_FORWARD_TARGETS 个目标，请分批操作")
        }

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

    private companion object {
        /** 单条命令允许的目标上限，避免误贴一大段 ID 时瞬间产生海量投递。 */
        private const val MAX_FORWARD_TARGETS: Int = 50
    }
}

private class SubscribeCommandHandler(
    private val subscriptionComponent: SubscriptionSelectionComponent,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("subscribe"),
        aliases = listOf(listOf("订阅")),
        description = "订阅来源平台发布者",
        usage = "subscribe <platform> <用户ID|用户名|ID1,ID2...>",
        requiredRole = CommandRole.MANAGER,
        category = "订阅",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size in 1..2 && SubscriptionSelectionComponent.parseSelection(invocation.args.first()) != null) {
            // 第二个参数由入口绑定；缺少批次时不能回退到执行时的最新候选。
            val batchId = invocation.args.getOrNull(1)
                ?: return CommandExecutionResult.failed("候选已失效，请重新搜索后选择。")
            return subscriptionComponent.handleSelection(invocation.context, invocation.args.first(), batchId)
                ?: CommandExecutionResult.failed("候选已失效，请重新搜索后选择。")
        }
        if (invocation.args.size < 2) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val platform = invocation.args[0].lowercase()
        val query = invocation.args.drop(1).joinToString(" ").trim()
        if (query.isBlank()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val service = subscriptionComponent.service()
        val lookupPlugin = service.lookup(platform)
            ?: return CommandExecutionResult.failed("未找到发布者查询插件：$platform")

        // 纯数字按 ID 直接提交；其它文本按用户名搜索，必须再选一次。
        val batchIds = parseBatchIds(query)
        if (batchIds != null && batchIds.size > 1) {
            return subscribeBatch(invocation, platform, lookupPlugin, batchIds)
        }
        if (query.toLongOrNull() != null) {
            val publisherInfo = runCatching { lookupPlugin.fetchPublisherInfo(query) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    return CommandExecutionResult.failed("获取发布者资料失败：${it.message ?: "未知错误"}")
                }
                ?: return CommandExecutionResult.failed("未找到发布者：$platform:$query")
            return CommandExecutionResult.success(
                service.subscribeResolved(invocation.context, platform, publisherInfo),
            )
        }

        val candidates = runCatching {
            lookupPlugin.searchPublisherInfo(query, subscriptionComponent.maxCandidates())
        }.getOrElse {
            if (it is CancellationException) throw it
            return CommandExecutionResult.failed("搜索发布者失败：${it.message ?: "未知错误"}")
        }
        if (candidates.isEmpty()) {
            return CommandExecutionResult.failed("未找到发布者：$query。可改用平台用户 ID 直接订阅。")
        }
        return CommandExecutionResult.success(
            subscriptionComponent.offerCandidates(invocation.context, platform, candidates),
        )
    }

    /** 逗号分隔且每段都是数字时视为批量 ID；含非数字则整体当用户名查询。 */
    private fun parseBatchIds(query: String): List<String>? {
        val parts = query.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        if (parts.any { it.toLongOrNull() == null }) return null
        return parts
    }

    private suspend fun subscribeBatch(
        invocation: CommandInvocation,
        platform: String,
        lookupPlugin: PublisherLookupPlugin,
        ids: List<String>,
    ): CommandExecutionResult {
        val distinctIds = ids.distinct()
        if (distinctIds.size > MAX_BATCH_SUBSCRIBE) {
            return CommandExecutionResult.failed("一次最多订阅 $MAX_BATCH_SUBSCRIBE 个，请分批操作")
        }
        val service = subscriptionComponent.service()
        val done = mutableListOf<String>()
        val failed = mutableListOf<String>()
        distinctIds.forEach { id ->
            val info = runCatching { lookupPlugin.fetchPublisherInfo(id) }.getOrElse {
                if (it is CancellationException) throw it
                null
            }
            if (info == null) {
                failed += "$id：未找到发布者"
                return@forEach
            }
            runCatching { service.subscribeResolved(invocation.context, platform, info) }
                .onSuccess { done += info.name }
                .onFailure {
                    if (it is CancellationException) throw it
                    failed += "$id：${it.message ?: "订阅失败"}"
                }
        }

        if (failed.isEmpty()) {
            return CommandExecutionResult.success("已订阅 ${done.size} 个：${done.joinToString("、")}")
        }
        val message = buildList {
            add("已订阅 ${done.size} 个，失败 ${failed.size} 个")
            done.forEach { add("✔ $it") }
            failed.forEach { add("✘ $it") }
        }.joinToString("\n")
        return if (done.isEmpty()) {
            CommandExecutionResult.failed(message)
        } else {
            CommandExecutionResult.success(message)
        }
    }

    private companion object {
        private const val MAX_BATCH_SUBSCRIBE: Int = 10
    }
}

private class UnsubscribeCommandHandler(
    private val followResolver: (String) -> PublisherFollowPlugin?,
    private val configProvider: () -> MainDynamicConfig,
    private val commandPrefixProvider: () -> String,
    private val eventBus: EventBus,
    private val listSnapshots: SubscriptionListSnapshotStore,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("unsubscribe"),
        aliases = listOf(listOf("退订")),
        description = "取消订阅发布者",
        usage = "unsubscribe <platform> <ID[,ID...]> | unsubscribe <platform>:<ID>[,...] | unsubscribe #序号[,序号...]",
        requiredRole = CommandRole.MANAGER,
        category = "订阅",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val args = invocation.args
        if (args.isEmpty()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        val subscriber = invocation.currentSubscriber()
            ?: return CommandExecutionResult.success("当前会话没有订阅")

        val first = args[0].trim().replace('＃', '#')
        // unsubscribe #1 / #1,#3：按 list 的序号退订
        if (first.startsWith("#")) {
            if (args.size > 1) {
                return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
            }
            return unsubscribeByIndex(invocation, subscriber, first)
        }

        val references = parseReferences(args)
            ?: return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        if (references.isEmpty()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        if (references.size == 1) {
            val reference = references.single()
            return singleResult(
                unsubscribeByReference(invocation, subscriber, reference.platformId, reference.externalId),
            )
        }
        return batchUnsubscribe(invocation, subscriber, references)
    }

    /** 支持 `<platform> <id[,id...]>` 与 `<platform>:<id>[,...]`（后者可直接从 list 复制）。 */
    private fun parseReferences(args: List<String>): List<UnsubscribeReference>? {
        if (args.size >= 2) {
            val platform = args[0].trim().lowercase()
            if (platform.isBlank() || platform.contains(':')) return null
            return args.drop(1)
                .flatMap { it.split(',', '，') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { UnsubscribeReference(platform, it) }
        }
        return args.single()
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { part ->
                val separator = part.indexOf(':')
                if (separator <= 0 || separator == part.length - 1) return null
                UnsubscribeReference(part.substring(0, separator).lowercase(), part.substring(separator + 1))
            }
    }

    private fun singleResult(outcome: UnsubscribeOutcome): CommandExecutionResult {
        return when (outcome) {
            is UnsubscribeOutcome.Done -> CommandExecutionResult.success(outcome.message)
            is UnsubscribeOutcome.NotSubscribed -> CommandExecutionResult.success(outcome.message)
            is UnsubscribeOutcome.NotFound -> CommandExecutionResult.failed(outcome.message)
        }
    }

    private suspend fun batchUnsubscribe(
        invocation: CommandInvocation,
        subscriber: Subscriber,
        references: List<UnsubscribeReference>,
    ): CommandExecutionResult {
        val done = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        references.distinct().forEach { reference ->
            when (
                val outcome = unsubscribeByReference(
                    invocation,
                    subscriber,
                    reference.platformId,
                    reference.externalId,
                )
            ) {
                is UnsubscribeOutcome.Done -> done += outcome.name
                is UnsubscribeOutcome.NotSubscribed -> skipped += outcome.name
                is UnsubscribeOutcome.NotFound -> missing += "${reference.platformId}:${reference.externalId}"
            }
        }
        val message = buildList {
            if (done.isNotEmpty()) add("已取消 ${done.size} 个：${done.joinToString("、")}")
            if (skipped.isNotEmpty()) add("未订阅 ${skipped.size} 个：${skipped.joinToString("、")}")
            if (missing.isNotEmpty()) add("未找到 ${missing.size} 个：${missing.joinToString("、")}")
        }.joinToString("\n")
        // 一个都没处理成功才算失败；部分成功仍返回成功并逐条说明。
        return if (done.isEmpty() && skipped.isEmpty()) {
            CommandExecutionResult.failed(message.ifBlank { "没有可取消的订阅" })
        } else {
            CommandExecutionResult.success(message)
        }
    }

    private suspend fun unsubscribeByIndex(
        invocation: CommandInvocation,
        subscriber: Subscriber,
        input: String,
    ): CommandExecutionResult {
        val indices = input.split(',', '，')
            .map { it.trim().removePrefix("#") }
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
        if (indices.isEmpty()) {
            return CommandExecutionResult.failed("用法：$commandPrefix ${spec.usage}")
        }
        if (indices.size == 1) {
            val index = indices.single()
            val entry = listSnapshots.resolve(invocation.context, index)
            if (entry == null) {
                val total = listSnapshots.size(invocation.context)
                return CommandExecutionResult.failed(
                    if (total == null) {
                        listSnapshotMissingMessage(invocation)
                    } else {
                        "序号超出范围：当前共 $total 项，请回复 #1 到 #$total"
                    },
                )
            }
            return when (val outcome = unsubscribeByReference(invocation, subscriber, entry.platformId, entry.externalId)) {
                is UnsubscribeOutcome.Done -> CommandExecutionResult.success(outcome.message)
                is UnsubscribeOutcome.NotSubscribed -> CommandExecutionResult.success(outcome.message)
                is UnsubscribeOutcome.NotFound -> CommandExecutionResult.failed(outcome.message)
            }
        }

        val done = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val invalid = mutableListOf<Int>()
        indices.distinct().forEach { index ->
            val entry = listSnapshots.resolve(invocation.context, index)
            if (entry == null) {
                invalid += index
                return@forEach
            }
            when (unsubscribeByReference(invocation, subscriber, entry.platformId, entry.externalId)) {
                is UnsubscribeOutcome.Done -> done += entry.name
                is UnsubscribeOutcome.NotSubscribed -> skipped += entry.name
                is UnsubscribeOutcome.NotFound -> invalid += index
            }
        }
        if (done.isEmpty() && skipped.isEmpty()) {
            val total = listSnapshots.size(invocation.context)
            return CommandExecutionResult.failed(
                if (total == null) {
                    listSnapshotMissingMessage(invocation)
                } else {
                    "序号无效：${invalid.joinToString("、") { "#$it" }}，当前共 $total 项"
                },
            )
        }
        return CommandExecutionResult.success(
            buildList {
                if (done.isNotEmpty()) add("已取消 ${done.size} 个：${done.joinToString("、")}")
                if (skipped.isNotEmpty()) add("未订阅 ${skipped.size} 个：${skipped.joinToString("、")}")
                if (invalid.isNotEmpty()) add("序号无效：${invalid.joinToString("、") { "#$it" }}")
            }.joinToString("\n"),
        )
    }

    private fun listSnapshotMissingMessage(invocation: CommandInvocation): String {
        return "没有可用的订阅列表快照，请先发送 $commandPrefix list 再按序号退订"
    }

    private suspend fun unsubscribeByReference(
        invocation: CommandInvocation,
        subscriber: Subscriber,
        platform: String,
        externalId: String,
    ): UnsubscribeOutcome {
        val publisher = PublisherRepository.findByKey(PublisherKey.of(platform, PublisherKind.USER, externalId))
            ?: return UnsubscribeOutcome.NotFound("未找到发布者：$platform:$externalId")

        val mutation = SubscriptionRepository.unsubscribe(subscriber.id, publisher.id)
        mutation.event?.let { eventBus.broadcast(it) }
        val removed = mutation.changed
        if (removed &&
            configProvider().subscription.unfollowWhenNoSubscribers &&
            SubscriptionRepository.countByPublisherId(publisher.id) == 0L
        ) {
            followResolver(platform)?.unfollowPublisher(publisher.externalId)
        }
        return if (removed) {
            UnsubscribeOutcome.Done("已取消订阅：${publisher.name}", publisher.name)
        } else {
            UnsubscribeOutcome.NotSubscribed("未订阅：${publisher.name}", publisher.name)
        }
    }

    private data class UnsubscribeReference(
        val platformId: String,
        val externalId: String,
    )

    private sealed interface UnsubscribeOutcome {
        val message: String
        val name: String

        data class Done(override val message: String, override val name: String) : UnsubscribeOutcome
        data class NotSubscribed(override val message: String, override val name: String) : UnsubscribeOutcome
        data class NotFound(override val message: String) : UnsubscribeOutcome {
            override val name: String get() = message
        }
    }
}

private class ListCommandHandler(
    private val listSnapshots: SubscriptionListSnapshotStore,
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("list"),
        aliases = listOf(listOf("列表")),
        description = "列出当前会话订阅",
        usage = "list",
        requiredRole = CommandRole.USER,
        category = "订阅",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val subscriber = invocation.currentSubscriber()
            ?: return CommandExecutionResult.success("当前会话没有订阅")
        val publisherIds = SubscriptionRepository.findPublisherIdsBySubscriberId(subscriber.id)
        if (publisherIds.isEmpty()) return CommandExecutionResult.success("当前会话没有订阅")

        val publishers = publisherIds
            .mapNotNull { PublisherRepository.findById(it) }
        if (publishers.isEmpty()) return CommandExecutionResult.success("当前会话没有订阅")

        // 记录本次顺序，供 `unsubscribe #序号` 使用：数据库查询没有稳定排序保证。
        listSnapshots.save(
            invocation.context,
            publishers.map {
                SubscriptionListSnapshotStore.Entry(
                    platformId = it.platformId.value,
                    externalId = it.externalId,
                    name = it.name,
                )
            },
        )

        val lines = publishers.mapIndexed { index, publisher ->
            "#${index + 1} ${publisher.platformId.value}:${publisher.externalId} (${publisher.name})"
        }.toMutableList()
        lines += ""
        lines += "取消订阅：$commandPrefix unsubscribe #序号"

        return successBatched(lines)
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
        category = "内容",
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
        category = "内容",
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
        category = "过滤",
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
        category = "过滤",
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
        category = "过滤",
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
                .map { rule -> formatFilterRule(rule, target.publisher, commandPrefix) }
        }

        return successBatched(lines.ifEmpty { listOf("过滤规则：（无）") })
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
            .map { rule -> formatFilterRule(rule, target.publisher, commandPrefix) }

        return successBatched(lines.ifEmpty { listOf("过滤规则：（无）") })
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
        category = "过滤",
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
    private val clock: () -> Long = System::currentTimeMillis,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    /** 待确认的清空请求，键 = 会话 + 订阅，值是过期时间。 */
    private val pending = ConcurrentHashMap<String, Long>()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("filter", "clear"),
        description = "清空订阅过滤规则",
        usage = "filter clear <platform> <publisherUserId> [confirm]",
        requiredRole = CommandRole.MANAGER,
        category = "过滤",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        val args = invocation.args
        val confirmed = args.lastOrNull()?.equals("confirm", ignoreCase = true) == true
        val effective = if (confirmed) args.dropLast(1) else args
        if (effective.size != 2) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }
        val target = when (val resolved = resolveFilterTarget(invocation, effective[0], effective[1])) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }
        val confirmKey = "${invocation.context.target.stableValue()}|${invocation.context.senderId}|${target.subscription.id}"

        if (!confirmed) {
            val count = DynamicFilterRuleRepository.findBySubscriptionId(target.subscription.id).size
            if (count == 0) {
                return success("没有可清空的过滤规则：${target.publisher.platformId.value}:${target.publisher.externalId}")
            }
            pending.entries.removeIf { now -> now.value < clock() }
            pending[confirmKey] = clock() + FILTER_CLEAR_CONFIRM_WINDOW_MILLIS
            return success(
                "确认清空 ${target.publisher.name} 的 $count 条过滤规则？" +
                    "请在 ${FILTER_CLEAR_CONFIRM_WINDOW_MILLIS / 1000} 秒内回复 " +
                    "$commandPrefix filter clear ${effective[0]} ${effective[1]} confirm",
            )
        }

        val expiresAt = pending.remove(confirmKey)
        if (expiresAt == null || clock() > expiresAt) {
            return failed("没有待确认的清空请求，已过期或未发起。")
        }
        val removed = DynamicFilterRuleRepository.clearBySubscriptionId(target.subscription.id)
        return success("已清空过滤规则：数量=$removed")
    }

    private companion object {
        private const val FILTER_CLEAR_CONFIRM_WINDOW_MILLIS: Long = 60_000L
    }
}

/**
 * 查看与修改当前会话对某个发布者的推送策略。
 *
 * 对应 `PATCH /api/subscriptions/{id}` 里的事件类型与 @全体 部分。
 */
private class PolicyCommandHandler(
    private val commandPrefixProvider: () -> String,
) : CommandHandler {
    private val commandPrefix: String
        get() = commandPrefixProvider()

    override val spec: CommandSpec = CommandSpec(
        path = listOf("policy"),
        aliases = listOf(listOf("策略")),
        description = "查看或修改当前会话的推送策略",
        usage = "policy <platform> <publisherUserId> [事件类型...]",
        requiredRole = CommandRole.MANAGER,
        category = "订阅",
    )

    override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
        if (invocation.args.size < 2) {
            return failed("用法：$commandPrefix ${spec.usage}")
        }
        val target = when (val resolved = resolveFilterTarget(invocation, invocation.args[0], invocation.args[1])) {
            is FilterTargetResolveResult.Failed -> return resolved.result
            is FilterTargetResolveResult.Found -> resolved.target
        }
        val rest = invocation.args.drop(2)
        val policy = target.subscription.policy
        if (rest.isEmpty()) {
            return success(describe(target.publisher, policy))
        }

        if (rest.first().equals("mention", ignoreCase = true)) {
            if (rest.size > 2) {
                return failed("用法：$commandPrefix ${spec.usage}")
            }
            val mentionAllEvents = when (rest.getOrNull(1)?.lowercase()) {
                "all", "全部" -> SubscriptionEventKind.entries.toSet()
                "none", "无" -> emptySet()
                else -> return failed(
                    "mention 只能是 all 或 none，例如 $commandPrefix policy bilibili 123 mention all",
                )
            }
            val updated = SubscriptionRepository.updatePolicy(
                target.subscription.id,
                policy.copy(mentionAllEvents = mentionAllEvents),
            )
            return success(describe(target.publisher, updated.policy))
        }

        val events = parseSubscriptionEvents(rest)
            ?: return failed("事件类型无效：${rest.joinToString(" ")}（可选 dynamic / live / live_end / all）")
        val updated = SubscriptionRepository.updatePolicy(
            target.subscription.id,
            policy.copy(enabledEvents = events),
        )
        val warning = if (events.isEmpty()) "\n注意：当前没有启用任何事件，该订阅不会推送任何内容。" else ""
        return success(describe(target.publisher, updated.policy) + warning)
    }

    private fun describe(publisher: Publisher, policy: SubscriptionPolicy): String {
        val events = policy.enabledEvents.sortedBy { it.ordinal }.map { it.label() }
        // 展示用户实际配置的值；未启用的事件就算配了 @全体 也不会触发，不必在展示层做裁剪。
        val mentions = policy.mentionAllEvents.sortedBy { it.ordinal }.map { it.label() }
        return listOf(
            "推送策略：${publisher.platformId.value}:${publisher.externalId} ${publisher.name}",
            "事件：${events.ifEmpty { listOf("无") }.joinToString("、")}",
            "@全体：${mentions.ifEmpty { listOf("无") }.joinToString("、")}",
        ).joinToString("\n")
    }
}

private fun SubscriptionEventKind.label(): String {
    return when (this) {
        SubscriptionEventKind.DYNAMIC -> "动态"
        SubscriptionEventKind.LIVE_STARTED -> "开播"
        SubscriptionEventKind.LIVE_ENDED -> "下播"
    }
}

/** 解析事件类型参数，支持逗号分隔与中文别名；无法识别时返回 null。 */
private fun parseSubscriptionEvents(tokens: List<String>): Set<SubscriptionEventKind>? {
    val normalized = tokens
        .flatMap { it.split(',', '，') }
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    if (normalized.isEmpty()) return null

    val result = linkedSetOf<SubscriptionEventKind>()
    normalized.forEach { token ->
        when (token) {
            "all", "全部" -> result += SubscriptionEventKind.entries
            "dynamic", "动态" -> result += SubscriptionEventKind.DYNAMIC
            "live", "live_started", "开播" -> result += SubscriptionEventKind.LIVE_STARTED
            "live_end", "live_ended", "下播" -> result += SubscriptionEventKind.LIVE_ENDED
            "none", "无" -> Unit
            else -> return null
        }
    }
    return result
}

private fun DynamicFilterRule.belongsToCurrentSubscriber(invocation: CommandInvocation): Boolean {
    val subscriber = invocation.currentSubscriber() ?: return false
    val subscription = SubscriptionRepository.findById(subscriptionId) ?: return false
    return subscription.subscriberId == subscriber.id
}
