package top.colter.dynamic.listener

import top.colter.dynamic.core.data.Dynamic
import top.colter.dynamic.draw.tools.formatTime

public class DynamicTemplateRenderer {
    public fun render(template: String, dynamic: Dynamic): String {
        val values = mapOf(
            "platform.id" to dynamic.platform.id,
            "platform.name" to dynamic.platform.name,
            "publisher.id" to dynamic.publisher.id.toString(),
            "publisher.name" to dynamic.publisher.name.orEmpty(),
            "publisher.userId" to dynamic.publisher.userId.orEmpty(),
            "dynamic.id" to dynamic.dynamicId,
            "dynamic.title" to dynamic.title.orEmpty(),
            "dynamic.notice" to dynamic.notice.orEmpty(),
            "dynamic.text" to dynamic.content?.text.orEmpty(),
            "dynamic.link" to dynamic.link,
            "dynamic.time" to dynamic.time.formatTime(),
            "stats.like" to dynamic.stats?.like.orEmpty(),
            "stats.comment" to dynamic.stats?.comment.orEmpty(),
            "stats.forward" to dynamic.stats?.forward.orEmpty(),
        )

        return PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            values[key] ?: match.value
        }.trim()
    }

    private companion object {
        private val PLACEHOLDER_REGEX: Regex = Regex("\\{([a-zA-Z0-9_.]+)\\}")
    }
}
