package top.colter.dynamic.filter

import top.colter.dynamic.core.data.DynamicBlock
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.DynamicFilterAction
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TextBlock
import top.colter.dynamic.core.data.TextMatchMode

public object DynamicFilterEvaluator {
    public fun isBlocked(update: SourceUpdate, rules: Iterable<DynamicFilterRule>): Boolean {
        if (update.payload !is DynamicPayload) return false
        val ruleList = rules.toList()
        if (ruleList.any { it.action == DynamicFilterAction.BLOCK && matches(update, it.condition) }) {
            return true
        }
        val allowRules = ruleList.filter { it.action == DynamicFilterAction.ALLOW }
        return allowRules.isNotEmpty() && allowRules.none { matches(update, it.condition) }
    }

    public fun matches(update: SourceUpdate, rule: DynamicFilterRule): Boolean {
        return matches(update, rule.condition)
    }

    public fun matches(update: SourceUpdate, condition: FilterCondition): Boolean {
        return when (condition) {
            is FilterCondition.HasElement -> hasBlockKind(update, condition.kind)
            is FilterCondition.TextMatch -> matchesText(update, condition)
        }
    }

    private fun matchesText(update: SourceUpdate, condition: FilterCondition.TextMatch): Boolean {
        val fields = filterableTextFields(update)
        return when (condition.mode) {
            TextMatchMode.CONTAINS -> fields.any {
                it.contains(condition.text, ignoreCase = condition.ignoreCase)
            }
            TextMatchMode.REGEX -> runCatching {
                val options = if (condition.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                val regex = Regex(condition.text, options)
                fields.any { regex.containsMatchIn(it) }
            }.getOrDefault(false)
        }
    }

    private fun hasBlockKind(update: SourceUpdate, kind: DynamicBlockKind): Boolean {
        val payload = update.payload as? DynamicPayload ?: return false
        return hasBlockKind(payload, kind, mutableSetOf(update.key.stableValue()))
    }

    private fun hasBlockKind(
        payload: DynamicPayload,
        kind: DynamicBlockKind,
        visitedUpdateKeys: MutableSet<String>,
    ): Boolean {
        if (payload.blocks.any { it.blockKind == kind }) return true
        return payload.blocks
            .filterIsInstance<RepostBlock>()
            .mapNotNull { it.embedded }
            .filter { visitedUpdateKeys.add(it.key.stableValue()) }
            .mapNotNull { it.payload as? DynamicPayload }
            .any { hasBlockKind(it, kind, visitedUpdateKeys) }
    }

    private fun filterableTextFields(update: SourceUpdate): List<String> {
        val payload = update.payload as? DynamicPayload ?: return emptyList()
        return filterableTextFields(payload, mutableSetOf(update.key.stableValue()))
    }

    private fun filterableTextFields(
        payload: DynamicPayload,
        visitedUpdateKeys: MutableSet<String>,
    ): List<String> {
        val fields = mutableListOf<String>()
        payload.title.addTo(fields)
        payload.labels.forEach { label -> label.text.addTo(fields) }
        payload.blocks.forEach { block -> block.addFilterableText(fields) }
        payload.blocks
            .filterIsInstance<RepostBlock>()
            .mapNotNull { it.embedded }
            .filter { visitedUpdateKeys.add(it.key.stableValue()) }
            .mapNotNull { it.payload as? DynamicPayload }
            .forEach { fields += filterableTextFields(it, visitedUpdateKeys) }
        return fields
    }

    private fun DynamicBlock.addFilterableText(fields: MutableList<String>) {
        when (this) {
            is TextBlock -> {
                content.plainText.addTo(fields)
                content.nodes.forEach { node -> node.text.addTo(fields) }
            }
            is ImageGridBlock -> images.forEach { image ->
                image.badge.addTo(fields)
                image.alt.addTo(fields)
                image.image.alt.addTo(fields)
            }
            is MediaCardBlock -> {
                card.title.addTo(fields)
                card.description.addTo(fields)
                card.badge.addTo(fields)
                card.info.addTo(fields)
                card.metrics.forEach { metric -> metric.display.addTo(fields) }
            }
            is PollBlock -> {
                title.addTo(fields)
                options.forEach { option ->
                    option.text.addTo(fields)
                    option.displayVotes.addTo(fields)
                }
            }
            is RepostBlock -> Unit
        }
    }

    private fun String?.addTo(fields: MutableList<String>) {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return
        fields += value
    }
}
