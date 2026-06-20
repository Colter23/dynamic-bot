package top.colter.dynamic.incoming

import java.util.UUID
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.command.CommandParser
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessageAuditMode
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageRecordPolicy
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.IncomingMessagePublishRequest
import top.colter.dynamic.event.CommandEvent
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.event.IncomingMessageEvent
import top.colter.dynamic.event.IncomingTextMessageEvent
import top.colter.dynamic.link.LinkParseService
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
    private val auditRecorder: IncomingMessageAuditRecorder = IncomingMessageAuditRecorder {
        IncomingMessageAuditRepository.recordMessage(it)
    },
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
        val rawText = message.commandText().trim()
        val commandContext = message.toCommandContext()
        val commandPrefix = configProvider().command.prefix
        val isCommand = rawText.isNotBlank() && CommandParser.parse(rawText, commandPrefix) != null
        val hasSupportedLinks = if (rawText.isBlank() || isCommand) {
            false
        } else {
            linkParseService.hasSupportedLinkCandidate(
                text = rawText,
                maxLinks = configProvider().linkParsing.maxLinksPerMessage,
            )
        }
        val intent = when {
            isCommand -> IncomingMessageIntent.Command
            hasSupportedLinks -> IncomingMessageIntent.LinkText
            rawText.isNotBlank() -> IncomingMessageIntent.PlainText
            else -> IncomingMessageIntent.NonText
        }

        eventBus.broadcast(
            IncomingMessageEvent(
                sourcePlugin = sourcePlugin,
                message = message,
                traceId = traceId,
                replyToMessageId = replyToMessageId,
            ),
        )

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
        }
        if (intent == IncomingMessageIntent.Command) {
            eventBus.broadcast(
                CommandEvent(
                    sourcePlugin = sourcePlugin,
                    context = commandContext,
                    rawText = rawText,
                    traceId = traceId,
                    replyToMessageId = replyToMessageId,
                ),
            )
        } else if (rawText.isNotBlank()) {
            eventBus.broadcast(
                IncomingTextMessageEvent(
                    sourcePlugin = sourcePlugin,
                    message = message,
                    context = commandContext,
                    rawText = rawText,
                    traceId = traceId,
                    replyToMessageId = replyToMessageId,
                    hasSupportedLinks = hasSupportedLinks,
                ),
            )
        }

        incomingConsumerDispatcher(dispatchContext)
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

    private fun stripLeadingMentionSyntax(text: String): String {
        return text
            .replace(LEADING_OFFICIAL_MENTION_PATTERN, "")
            .replace(LEADING_PLAIN_MENTION_PATTERN, "")
            .trimStart()
    }
}

private val LEADING_OFFICIAL_MENTION_PATTERN: Regex = Regex("""^(?:\s*<@[^>]+>\s*)+""")
private val LEADING_PLAIN_MENTION_PATTERN: Regex = Regex("""^(?:\s*@\S+\s*)+""")
