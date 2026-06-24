package top.colter.dynamic.incoming

import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.TargetAddress

public data class IncomingBotAccountSelection(
    val currentBotAccountId: String?,
    val selectedBotAccountId: String?,
    val currentBotMentioned: Boolean = false,
    val hasExplicitBotSelection: Boolean = false,
) {
    fun acceptsCanonical(): Boolean {
        val currentBot = currentBotAccountId ?: return true
        return when {
            selectedBotAccountId != null -> currentBot == selectedBotAccountId
            hasExplicitBotSelection -> false
            else -> true
        }
    }

    fun acceptsMentionedOnly(): Boolean = currentBotMentioned
}

public class IncomingBotAccountSelector(
    private val primaryBotAccountResolver: suspend (TargetAddress) -> String? = { null },
    private val knownBotAccountIdsResolver: suspend (TargetAddress) -> Set<String> = { emptySet() },
) {
    suspend fun isKnownBotSender(context: CommandContext): Boolean {
        val sender = context.senderId.trim().takeIf { it.isNotBlank() } ?: return false
        val currentBot = context.botAccountId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (sender == currentBot) return true

        return knownBotAccountIdsResolver(context.target.withoutAccountPreference())
            .mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotBlank) }
            .contains(sender)
    }

    suspend fun select(context: CommandContext): IncomingBotAccountSelection {
        val currentBot = context.botAccountId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (currentBot == null) {
            return IncomingBotAccountSelection(currentBotAccountId = null, selectedBotAccountId = null)
        }

        val mentionedAccounts = context.mentionedAccountIds
            .mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotBlank) }
        val currentBotMentioned = currentBot in mentionedAccounts
        val mentionedKnownBots = if (currentBotMentioned || mentionedAccounts.isEmpty()) {
            emptySet()
        } else {
            knownBotAccountIdsResolver(context.target.withoutAccountPreference())
                .mapNotNullTo(linkedSetOf()) { it.trim().takeIf(String::isNotBlank) }
                .let { knownBotAccounts -> mentionedAccounts.filterTo(linkedSetOf()) { it in knownBotAccounts } }
        }
        val mentionedSelected = when {
            currentBotMentioned -> currentBot
            mentionedKnownBots.size == 1 -> mentionedKnownBots.single()
            else -> null
        }
        val selectedBot = mentionedSelected
            ?: if (currentBotMentioned || mentionedKnownBots.isNotEmpty()) {
                null
            } else {
                primaryBotAccountResolver(context.target.withoutAccountPreference())
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }

        return IncomingBotAccountSelection(
            currentBotAccountId = currentBot,
            selectedBotAccountId = selectedBot,
            currentBotMentioned = currentBotMentioned,
            hasExplicitBotSelection = currentBotMentioned || mentionedKnownBots.isNotEmpty(),
        )
    }

    private fun TargetAddress.withoutAccountPreference(): TargetAddress = copy(accountId = null)
}
