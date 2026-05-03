package top.colter.dynamic.command

import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandExecutionStatus
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandRegistry
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.CommandTarget
import top.colter.dynamic.core.data.MessageChain
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.repository.PublisherRepository
import top.colter.dynamic.core.repository.SubscriberRepository
import top.colter.dynamic.core.repository.SubscribeRepository

public class CommandListener(
    private val adminUsers: Set<String>,
) : Listener<CommandEvent> {

    init {
        registerBuiltins()
    }

    override suspend fun onMessage(event: CommandEvent) {
        if (event.commandName.isBlank()) return

        if (adminUsers.isNotEmpty() && !adminUsers.contains(event.context.senderId)) {
            reply(event, CommandExecutionResult(CommandExecutionStatus.REJECTED, "permission denied"))
            return
        }

        val handler = CommandRegistry.find(event.commandName)
        if (handler == null) {
            reply(event, CommandExecutionResult(CommandExecutionStatus.FAILED, "unknown command: ${event.commandName}"))
            return
        }

        val result = runCatching { handler.handle(event) }
            .getOrElse { CommandExecutionResult(CommandExecutionStatus.FAILED, "command failed: ${it.message}") }

        reply(event, result)
    }

    private fun reply(event: CommandEvent, result: CommandExecutionResult) {
        val status = when (result.status) {
            CommandExecutionStatus.SUCCESS -> CommandStatus.SUCCESS
            CommandExecutionStatus.REJECTED -> CommandStatus.REJECTED
            CommandExecutionStatus.FAILED -> CommandStatus.FAILED
        }
        CommandResultEvent(
            sourcePlugin = "main",
            target = CommandTarget(
                platform = event.context.platform,
                chatType = event.context.chatType,
                chatId = event.context.chatId,
                senderId = event.context.senderId,
            ),
            chain = listOf(MessageChain(listOf(MessageContent.Text(result.message)))),
            inReplyTo = event.traceId,
            status = status,
            errorMessage = if (status == CommandStatus.FAILED) result.message else null,
        ).broadcast()
    }

    private fun registerBuiltins() {
        CommandRegistry.clear()
        CommandRegistry.register(HelpCommandHandler())
        CommandRegistry.register(StatusCommandHandler())
        CommandRegistry.register(SubscribeCommandHandler())
        CommandRegistry.register(UnsubscribeCommandHandler())
        CommandRegistry.register(ListCommandHandler())
    }
}

private class HelpCommandHandler : CommandHandler {
    override val command: String = "help"

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        return CommandExecutionResult(
            CommandExecutionStatus.SUCCESS,
            "/db help | /db status | /db subscribe <platform> <publisherUserId> | /db unsubscribe <platform> <publisherUserId> | /db list",
        )
    }
}

private class StatusCommandHandler : CommandHandler {
    override val command: String = "status"

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        val commandCount = CommandRegistry.listCommands().size
        return CommandExecutionResult(CommandExecutionStatus.SUCCESS, "ok, commands=$commandCount")
    }
}

private class SubscribeCommandHandler : CommandHandler {
    override val command: String = "subscribe"

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: /db subscribe <platform> <publisherUserId>")
        }
        val platform = event.args[0]
        val publisherUserId = event.args[1]
        val publisher = PublisherRepository.findByUserId(publisherUserId)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher not found: $publisherUserId")
        if (!platform.equals(publisher.platform, ignoreCase = true)) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher platform mismatch, actual=${publisher.platform}")
        }

        val subscriber = SubscriberRepository.ensure(
            platform = event.context.platform,
            userId = event.context.senderId,
            name = event.context.senderId,
        )
        val created = SubscribeRepository.subscribe(subscriber.id.toString(), publisher.id.toString())
        return if (created) {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "subscribed: ${publisher.name ?: publisher.userId}")
        } else {
            CommandExecutionResult(CommandExecutionStatus.SUCCESS, "already subscribed: ${publisher.name ?: publisher.userId}")
        }
    }
}

private class UnsubscribeCommandHandler : CommandHandler {
    override val command: String = "unsubscribe"

    override suspend fun handle(event: CommandEvent): CommandExecutionResult {
        if (event.args.size < 2) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "usage: /db unsubscribe <platform> <publisherUserId>")
        }
        val platform = event.args[0]
        val publisherUserId = event.args[1]
        val publisher = PublisherRepository.findByUserId(publisherUserId)
            ?: return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher not found: $publisherUserId")
        if (!platform.equals(publisher.platform, ignoreCase = true)) {
            return CommandExecutionResult(CommandExecutionStatus.FAILED, "publisher platform mismatch, actual=${publisher.platform}")
        }

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
    override val command: String = "list"

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
