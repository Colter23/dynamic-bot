package top.colter.dynamic.filter

import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.FilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.PollAttachment
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.VideoAttachment
import top.colter.dynamic.core.data.DynamicFilterRule

public object DynamicFilterEvaluator {
    public fun isBlocked(update: SourceUpdate, rules: Iterable<DynamicFilterRule>): Boolean {
        val sortedRules = rules.filter { it.enabled }.sortedBy { it.priority }
        var blocked = false
        sortedRules.forEach { rule ->
            if (matches(update, rule.condition)) {
                blocked = rule.action == FilterAction.BLOCK
            }
        }
        return blocked
    }

    public fun matches(update: SourceUpdate, rule: DynamicFilterRule): Boolean {
        return rule.enabled && matches(update, rule.condition)
    }

    public fun matches(update: SourceUpdate, condition: FilterCondition): Boolean {
        return when (condition) {
            is FilterCondition.HasAttachmentKind -> hasAttachmentKind(update, condition.kind)
            is FilterCondition.TextContains -> filterableTextFields(update).any {
                it.contains(condition.value, ignoreCase = condition.ignoreCase)
            }
            is FilterCondition.TextRegex -> runCatching {
                val regex = Regex(condition.pattern)
                filterableTextFields(update).any { regex.containsMatchIn(it) }
            }.getOrDefault(false)
            is FilterCondition.HasReference -> {
                val payload = update.payload as? DynamicPayload ?: return false
                payload.references.any { reference -> condition.kind == null || reference.kind == condition.kind }
            }
            is FilterCondition.AnyOf -> condition.conditions.any { matches(update, it) }
            is FilterCondition.AllOf -> condition.conditions.all { matches(update, it) }
            is FilterCondition.Not -> !matches(update, condition.condition)
        }
    }

    private fun hasAttachmentKind(update: SourceUpdate, kind: DynamicAttachmentKind): Boolean {
        val payload = update.payload as? DynamicPayload ?: return false
        return hasAttachmentKind(payload, kind, mutableSetOf(update.key.stableValue()))
    }

    private fun hasAttachmentKind(
        payload: DynamicPayload,
        kind: DynamicAttachmentKind,
        visitedUpdateKeys: MutableSet<String>,
    ): Boolean {
        if (payload.attachments.any { it.kind == kind }) return true
        return payload.references
            .mapNotNull { it.embedded }
            .filter { visitedUpdateKeys.add(it.key.stableValue()) }
            .mapNotNull { it.payload as? DynamicPayload }
            .any { hasAttachmentKind(it, kind, visitedUpdateKeys) }
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
        payload.content?.plainText.addTo(fields)
        payload.content?.nodes.orEmpty().forEach { node -> node.text.addTo(fields) }

        payload.attachments.forEach { attachment ->
            when (attachment) {
                is ImageAttachment -> attachment.images.forEach { image ->
                    image.badge.addTo(fields)
                    image.alt.addTo(fields)
                    image.image.alt.addTo(fields)
                }
                is VideoAttachment -> {
                    attachment.title.addTo(fields)
                    attachment.description.addTo(fields)
                    attachment.badge.addTo(fields)
                    attachment.metrics.forEach { metric -> metric.display.addTo(fields) }
                }
                is CardAttachment -> {
                    attachment.title.addTo(fields)
                    attachment.description.addTo(fields)
                    attachment.badge.addTo(fields)
                    attachment.info.addTo(fields)
                }
                is PollAttachment -> {
                    attachment.title.addTo(fields)
                    attachment.options.forEach { option ->
                        option.text.addTo(fields)
                        option.displayVotes.addTo(fields)
                    }
                }
            }
        }
        payload.references
            .mapNotNull { it.embedded }
            .filter { visitedUpdateKeys.add(it.key.stableValue()) }
            .mapNotNull { it.payload as? DynamicPayload }
            .forEach { fields += filterableTextFields(it, visitedUpdateKeys) }
        return fields
    }

    private fun String?.addTo(fields: MutableList<String>) {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return
        fields += value
    }
}
