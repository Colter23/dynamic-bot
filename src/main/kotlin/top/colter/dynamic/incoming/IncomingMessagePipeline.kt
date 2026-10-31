package top.colter.dynamic.incoming

import java.util.UUID
import top.colter.dynamic.CommandReceiveMode
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandBootstrapService
import top.colter.dynamic.command.CommandBootstrapStatus
import top.colter.dynamic.command.CommandClaimOutcome
import top.colter.dynamic.command.CommandParser
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
}

internal class IncomingMessagePipeline(
    private val configProvider: () -> MainDynamicConfig,
    private val linkParseService: LinkParseService,
    private val eventBus: EventBus,
    private val incomingConsumerDispatcher: suspend (IncomingMessageDispatchContext) -> Unit,
    private val auditModeResolver: suspend (IncomingMessageDispatchContext) -> IncomingMessageAuditMode = { IncomingMessageAuditMode.NONE },
    private val incomingBotAccountSelector: IncomingBotAccountSelector = IncomingBotAccountSelector(),
    private val auditRecorder: IncomingMessageAuditRecorder = IncomingMessageAuditRecorder {
        IncomingMessageAuditRepository.recordMessage(it)
    },
    private val commandBootstrapService: CommandBootstrapService? = null,
    private val pendingSelectionHandler: (suspend (CommandContext, String) -> String?)? = null,
) {
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
        val isCommand = rawText.isNotBlank() && CommandParser.parse(rawText, commandPrefix) != null
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
        if (handleBootstrapClaim(message, commandContext, rawText, traceId, replyToMessageId)) {
            return
        }

        // `#序号` 候选选择：命中即消费，不再作为普通文本分发。
        val selectionReply = pendingSelectionHandler?.invoke(commandContext, rawText)
        if (selectionReply != null) {
            replyToUser(message, commandContext, selectionReply, traceId, replyToMessageId)
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
        if (recordPolicy != IncomingMessageRecordPolicy.None) {
            val recorded = auditRecorder.recordMessage(
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
            // recordMessage 以 traceId 去重：同一条平台消息被重复投递时返回 false。
            // 此时整条消息都不再往下走，避免观测、插件消费与命令被执行两遍。
            if (!recorded) {
                return
            }
        }

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
                eventBus.broadcast(
                    CommandEvent(
                        sourcePlugin = sourcePlugin,
                        context = commandContext,
                        rawText = rawText,
                        traceId = traceId,
                        replyToMessageId = replyToMessageId,
                    ),
                )
            }
        } else if (rawText.isNotBlank() || hasSupportedLinks) {
            eventBus.broadcast(
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
     * 命令系统未初始化时，拦截单聊里发来的验证码。
     *
     * 只有"整段消息恰好是 6 位数字"才认为是验证码，避免吞掉正常聊天；
     * 除认领成功与过期提示外一律静默，不向试探者暴露进度。
     *
     * @return true 表示消息已被消费，不再进入后续分发。
     */
    private suspend fun handleBootstrapClaim(
        message: IncomingMessage,
        context: CommandContext,
        rawText: String,
        traceId: String,
        replyToMessageId: String,
    ): Boolean {
        val service = commandBootstrapService ?: return false
        if (message.target.kind != TargetKind.USER) return false
        if (!service.isCandidate(rawText)) return false
        if (service.snapshot().status == CommandBootstrapStatus.CLAIMED) return false

        val replyText = when (service.claim(context, rawText)) {
            CommandClaimOutcome.CLAIMED -> "已认领系统管理员，现在可以使用管理命令。"
            CommandClaimOutcome.EXPIRED -> "验证码已失效，请在后台「系统」页重新生成，或重启主程序。"
            else -> return true
        }
        replyToUser(message, context, replyText, traceId, replyToMessageId)
        return true
    }

    private fun replyToUser(
        message: IncomingMessage,
        context: CommandContext,
        text: String,
        traceId: String,
        replyToMessageId: String,
    ) {
        eventBus.broadcast(
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

    private suspend fun shouldDispatchCommand(context: CommandContext): Boolean {        val mode = configProvider().command.receiveMode
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
