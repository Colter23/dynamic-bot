package top.colter.dynamic.incoming

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.colter.dynamic.CommandReceiveMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandBootstrapService
import top.colter.dynamic.command.CommandBootstrapStatus
import top.colter.dynamic.command.CommandClaimOutcome
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.command.ClaimCommandHandler
import top.colter.dynamic.command.PendingSubscriptionSelection
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.data.CommandRole
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.IncomingMessageAuditMode
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageRecordPolicy
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.CommandResultEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingMessageEvent
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.link.LinkParseService
import top.colter.dynamic.link.LinkUrlExtractor
import top.colter.dynamic.repository.IncomingAuditWriteRequest
import top.colter.dynamic.repository.IncomingMessageAuditRepository
import top.colter.dynamic.repository.SubscriberStateCache

internal fun interface IncomingMessageAuditRecorder {
    fun recordMessage(request: IncomingAuditWriteRequest): Boolean
    fun tryBeginDispatch(traceId: String): Boolean = true
    fun markDispatchCompleted(traceId: String) {}
}

internal class IncomingMessagePipeline(
    private val configProvider: () -> MainDynamicConfig,
    private val linkParseService: LinkParseService,
    private val eventBus: EventBus,
    private val incomingConsumerDispatcher: suspend (IncomingMessageDispatchContext) -> Unit,
    private val auditModeResolver: suspend (IncomingMessageDispatchContext) -> IncomingMessageAuditMode = { IncomingMessageAuditMode.NONE },
    private val incomingBotAccountSelector: IncomingBotAccountSelector = IncomingBotAccountSelector(),
    private val auditRecorder: IncomingMessageAuditRecorder = object : IncomingMessageAuditRecorder {
        override fun recordMessage(request: IncomingAuditWriteRequest): Boolean =
            IncomingMessageAuditRepository.recordMessage(request)
        override fun tryBeginDispatch(traceId: String): Boolean =
            IncomingMessageAuditRepository.tryBeginDispatch(traceId)
        override fun markDispatchCompleted(traceId: String) =
            IncomingMessageAuditRepository.markDispatchCompleted(traceId)
    },
    private val commandBootstrapService: CommandBootstrapService? = null,
    private val pendingSelectionResolver: ((CommandContext, String) -> PendingSubscriptionSelection?)? = null,
    private val claimDeduplicator: ClaimMessageDeduplicator = ClaimMessageDeduplicator(),
) {
    private val processingLocks = Array(64) { Mutex() }
    private val startedTraces = LinkedHashSet<String>()

    private val claimHandler: ClaimCommandHandler? by lazy {
        commandBootstrapService?.let {
            ClaimCommandHandler(it, commandPrefixProvider = { configProvider().command.prefix })
        }
    }

    suspend fun handle(sourcePlugin: String, request: IncomingMessagePublishRequest) {
        val message = request.message
        if (SubscriberStateCache.stateOf(message.target).blocksInbound) return

        val traceId = request.traceId.trim().takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val replyToMessageId = request.replyToMessageId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: message.messageId.trim().takeIf { it.isNotBlank() }
            ?: ""
        val config = configProvider()
        val rawText = message.commandText().trim()
        val commandContext = message.toCommandContext()
        val commandPrefix = config.command.prefix
        // 将候选批次带入内部命令，防止异步执行时误用后来搜索的候选。
        val parsed = CommandParser.parse(rawText, commandPrefix)
        val selectionText = if ((parsed?.commandName == "subscribe" || parsed?.commandName == "订阅") && parsed.args.size == 1) {
            parsed.args.single()
        } else {
            rawText
        }
        val selection = pendingSelectionResolver?.invoke(commandContext, selectionText)
        val commandText = selection?.let { "$commandPrefix subscribe #${it.index} ${it.batchId}" } ?: rawText
        val isCommand = commandText.isNotBlank() && CommandParser.parse(commandText, commandPrefix) != null
        val linkUrls = if (isCommand) {
            emptyList()
        } else {
            message.linkUrls(config.linkParsing.platformCardLinksEnabled)
        }
        val hasSupportedLinks = if (linkUrls.isEmpty() || isCommand) {
            false
        } else {
            linkParseService.hasSupportedLinkCandidate(
                urls = linkUrls,
                maxLinks = config.linkParsing.maxLinksPerMessage,
            )
        }
        val intent = when {
            isCommand -> IncomingMessageIntent.Command
            hasSupportedLinks -> IncomingMessageIntent.LinkText
            rawText.isNotBlank() -> IncomingMessageIntent.PlainText
            else -> IncomingMessageIntent.NonText
        }

        // 验证码消息在观测与审计之前拦截：它是一次性凭据，不该落库或进入插件消费链路。
        if (handleBootstrapClaim(sourcePlugin, request, commandContext, rawText, traceId, replyToMessageId)) {
            return
        }

        val dispatchContext = IncomingMessageDispatchContext(
            message = message,
            sourcePlugin = sourcePlugin,
            traceId = traceId,
            replyToMessageId = replyToMessageId,
            commandContext = commandContext,
            rawText = rawText,
            intent = intent,
        )
        val recordPolicy = resolveRecordPolicy(intent, request.recordPolicyHint, auditModeResolver(dispatchContext))
        if (recordPolicy == IncomingMessageRecordPolicy.None) {
            dispatch(dispatchContext, commandText, linkUrls, hasSupportedLinks)
            return
        }
        // 审计存在只表示收到了消息，不表示命令/链接已经执行。串行化同一 trace 的重投，
        // 未开始的消息可在重投时恢复；已经开始的处理可能有副作用，不自动重放。
        val started = processingLocks[(traceId.hashCode() and Int.MAX_VALUE) % processingLocks.size].withLock {
            if (synchronized(startedTraces) { traceId in startedTraces }) return@withLock false
            auditRecorder.recordMessage(
                IncomingAuditWriteRequest(
                    sourcePlugin = sourcePlugin,
                    message = message,
                    traceId = traceId,
                    replyToMessageId = replyToMessageId,
                    intent = intent,
                    recordPolicy = recordPolicy,
                    receivedAtEpochSeconds = request.receivedAtEpochSeconds ?: System.currentTimeMillis() / 1000,
                    dedupeKey = request.dedupeKey,
                    sourceEventId = request.sourceEventId,
                ),
            )
            currentCoroutineContext().ensureActive()
            if (!auditRecorder.tryBeginDispatch(traceId)) {
                return@withLock false
            }
            synchronized(startedTraces) {
                if (startedTraces.size >= 4_096) {
                    startedTraces.iterator().also { it.next(); it.remove() }
                }
                startedTraces += traceId
            }
            true
        }
        if (!started) return
        try {
            dispatch(dispatchContext, commandText, linkUrls, hasSupportedLinks)
            currentCoroutineContext().ensureActive()
            auditRecorder.markDispatchCompleted(traceId)
        } catch (error: Throwable) {
            loggerFor<IncomingMessagePipeline>().warn {
                "入站消息处理中断，可能已产生副作用，不自动重放：traceId=$traceId，原因=${error.message.orEmpty()}"
            }
            throw error
        }
    }

    private suspend fun dispatch(
        dispatchContext: IncomingMessageDispatchContext,
        commandText: String,
        linkUrls: List<String>,
        hasSupportedLinks: Boolean,
    ) {
        val (message, sourcePlugin, traceId, replyToMessageId, commandContext, rawText, intent) = dispatchContext
        eventBus.broadcast(
            IncomingMessageEvent(
                sourcePlugin = sourcePlugin,
                message = message,
                traceId = traceId,
                replyToMessageId = replyToMessageId,
            ),
        )

        if (intent == IncomingMessageIntent.Command) {
            if (shouldDispatchCommand(commandContext)) {
                eventBus.dispatch(
                    CommandEvent(
                        sourcePlugin = sourcePlugin,
                        context = commandContext,
                        rawText = commandText,
                        traceId = traceId,
                        replyToMessageId = replyToMessageId,
                    ),
                )
            }
        } else if (rawText.isNotBlank() || hasSupportedLinks) {
            eventBus.dispatch(
                IncomingTextMessageEvent(
                    sourcePlugin = sourcePlugin,
                    message = message,
                    context = commandContext,
                    rawText = rawText,
                    linkUrls = linkUrls,
                    traceId = traceId,
                    replyToMessageId = replyToMessageId,
                    hasSupportedLinks = hasSupportedLinks,
                ),
            )
        }

        incomingConsumerDispatcher(dispatchContext)
    }

    /**
     * 在所有观测、插件和审计之前处理认领凭据。
     *
     * 显式 claim 命令无论状态和参数是否有效都在此消费，避免凭据流入插件或审计。
     * 裸六位数字只在未初始化的私聊中拦截，除认领成功与过期提示外一律静默。
     *
     * @return true 表示消息已被消费，不再进入后续分发。
     */
    private suspend fun handleBootstrapClaim(
        sourcePlugin: String,
        request: IncomingMessagePublishRequest,
        context: CommandContext,
        rawText: String,
        traceId: String,
        replyToMessageId: String,
    ): Boolean {
        val message = request.message
        val parsed = CommandParser.parse(rawText, configProvider().command.prefix)
        if (parsed?.commandName == "claim") {
            val handler = claimHandler ?: return true
            if (!shouldDispatchCommand(context)) return true
            if (!claimDeduplicator.firstObservation(sourcePlugin, request, traceId)) return true
            val result = handler.handle(
                CommandInvocation(
                    sourcePlugin = "main",
                    context = context,
                    rawText = rawText,
                    traceId = traceId,
                    replyToMessageId = replyToMessageId,
                    tokens = parsed.tokens,
                    matchedPath = listOf("claim"),
                    args = parsed.args,
                    role = CommandRole.USER,
                ),
            )
            if (result.reply.isNotEmpty()) {
                eventBus.dispatch(
                    CommandResultEvent(
                        sourcePlugin = "main",
                        target = CommandTarget(message.target.withPreferredAccount(context.botAccountId), context.senderId),
                        chain = result.reply,
                        inReplyTo = replyToMessageId,
                        traceId = traceId,
                        status = result.status,
                        errorMessage = result.errorMessage,
                    ),
                )
            }
            return true
        }
        val service = commandBootstrapService ?: return false
        if (message.target.kind != TargetKind.USER) return false
        if (!service.isCandidate(rawText)) return false
        if (service.snapshot().status == CommandBootstrapStatus.CLAIMED) {
            // 成功认领后的重复裸验证码仍须拦截，不能重新进入普通消息分发。
            return claimDeduplicator.wasSeen(sourcePlugin, request, traceId)
        }
        if (!claimDeduplicator.firstObservation(sourcePlugin, request, traceId)) return true

        val replyText = when (service.claim(context, rawText)) {
            CommandClaimOutcome.CLAIMED -> "已认领系统管理员，现在可以使用管理命令。"
            CommandClaimOutcome.EXPIRED -> "验证码已失效，请在后台「系统」页重新生成，或重启主程序。"
            else -> return true
        }
        replyToUser(message, context, replyText, traceId, replyToMessageId)
        return true
    }

    private suspend fun replyToUser(
        message: IncomingMessage,
        context: CommandContext,
        text: String,
        traceId: String,
        replyToMessageId: String,
    ) {
        eventBus.dispatch(
            CommandResultEvent(
                sourcePlugin = "main",
                target = CommandTarget(
                    address = message.target.withPreferredAccount(context.botAccountId),
                    senderId = context.senderId,
                ),
                chain = listOf(MessageBatch(listOf(MessageContent.Text(text)))),
                inReplyTo = replyToMessageId,
                traceId = traceId,
                status = CommandStatus.SUCCESS,
            ),
        )
    }

    private fun TargetAddress.withPreferredAccount(accountId: String?): TargetAddress {
        val normalized = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return this
        return copy(accountId = normalized)
    }

    private suspend fun shouldDispatchCommand(context: CommandContext): Boolean {
        val mode = configProvider().command.receiveMode
        if (mode == CommandReceiveMode.ANY) return true
        val selection = incomingBotAccountSelector.select(context)
        if (selection.currentBotAccountId == null) return true
        if (selection.currentBotMentioned) return true
        if (mode == CommandReceiveMode.MENTIONED_ONLY) return false
        return selection.acceptsCanonical()
    }

    private fun resolveRecordPolicy(
        intent: IncomingMessageIntent,
        requestHint: IncomingMessageRecordPolicy,
        auditMode: IncomingMessageAuditMode,
    ): IncomingMessageRecordPolicy {
        val defaultPolicy = when (intent) {
            IncomingMessageIntent.Command -> IncomingMessageRecordPolicy.Audit
            IncomingMessageIntent.LinkText -> IncomingMessageRecordPolicy.Trace()
            IncomingMessageIntent.PlainText,
            IncomingMessageIntent.NonText -> IncomingMessageRecordPolicy.None
        }
        return listOf(defaultPolicy, requestHint, auditMode.toRecordPolicy()).maxBy { it.priority() }
    }

    private fun IncomingMessageAuditMode.toRecordPolicy(): IncomingMessageRecordPolicy {
        return when (this) {
            IncomingMessageAuditMode.NONE -> IncomingMessageRecordPolicy.None
            IncomingMessageAuditMode.TRACE -> IncomingMessageRecordPolicy.Trace()
            IncomingMessageAuditMode.AUDIT -> IncomingMessageRecordPolicy.Audit
        }
    }

    private fun IncomingMessageRecordPolicy.priority(): Int {
        return when (this) {
            IncomingMessageRecordPolicy.None -> 0
            is IncomingMessageRecordPolicy.Trace -> 1
            IncomingMessageRecordPolicy.Audit -> 2
        }
    }

    private fun IncomingMessage.toCommandContext(): CommandContext {
        return CommandContext(
            target = target,
            senderId = senderId,
            botAccountId = botAccountId?.trim()?.takeIf { it.isNotBlank() },
            mentionedAccountIds = commandMentionedAccountIds(),
        )
    }

    private fun IncomingMessage.commandMentionedAccountIds(): Set<String> {
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() }
        if (target.kind != TargetKind.USER || botId == null) {
            return mentions.mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotBlank) }
        }
        return mentions.mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotBlank) }
            .also { it += botId }
    }

    private fun IncomingMessage.commandText(): String {
        val normalizedText = text.trim()
        val botId = botAccountId?.trim()?.takeIf { it.isNotBlank() } ?: return normalizedText
        if (botId !in mentions) return normalizedText

        val segments = segments
        if (segments.isEmpty()) return stripLeadingMentionSyntax(normalizedText)
        var seenBotMention = false
        val builder = StringBuilder()
        for (segment in segments) {
            when (segment) {
                is IncomingMessageSegment.Mention -> {
                    if (builder.isEmpty() && segment.targetId == botId) {
                        seenBotMention = true
                    } else {
                        return normalizedText
                    }
                }
                is IncomingMessageSegment.Text -> {
                    if (builder.isEmpty() && !seenBotMention && segment.text.isBlank()) {
                        continue
                    }
                    if (builder.isEmpty() && !seenBotMention && segment.text.isNotBlank()) {
                        return stripLeadingMentionSyntax(normalizedText)
                    }
                    builder.append(segment.text)
                }
                else -> {
                    if (builder.isEmpty() && !seenBotMention) return normalizedText
                }
            }
        }
        return if (seenBotMention) builder.toString().trimStart() else normalizedText
    }

    private fun IncomingMessage.linkUrls(includePlatformCardLinks: Boolean): List<String> {
        val urls = linkedSetOf<String>()
        segments.forEach { segment ->
            when (segment) {
                is IncomingMessageSegment.Text -> LinkUrlExtractor.extract(segment.text).forEach(urls::add)
                is IncomingMessageSegment.Link -> if (includePlatformCardLinks) urls += segment.url.trim()
                else -> Unit
            }
        }
        LinkUrlExtractor.extract(text).forEach(urls::add)
        return urls.filter(String::isNotBlank)
    }

    private fun stripLeadingMentionSyntax(text: String): String {
        return text
            .replace(LEADING_OFFICIAL_MENTION_PATTERN, "")
            .replace(LEADING_PLAIN_MENTION_PATTERN, "")
            .trimStart()
    }
}

private val LEADING_OFFICIAL_MENTION_PATTERN: Regex = Regex("""^(?:\s*<@[^>]+>\s*)+""")
private val LEADING_PLAIN_MENTION_PATTERN: Regex = Regex("""^(?:\s*@\S+\s*)+""")
