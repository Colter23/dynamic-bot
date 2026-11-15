package top.colter.dynamic.template

import top.colter.dynamic.core.data.ForwardNode
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent

public data class TemplateStep(val value: String, val forward: Boolean, val fields: Set<String>) {
    public val dependsOnVideo: Boolean get() = fields.any { it == "video" || it == "size" }
}

public data class CompiledMessageTemplate(val steps: List<TemplateStep>) {
    public val fields: Set<String> get() = steps.flatMap { it.fields }.toSet()
    public val requestsVideo: Boolean get() = "video" in fields
    // 一旦到达依赖边界，后面的步骤必须一起等待，不能越过视频提前发送。
    public val boundary: Int get() = if (requestsVideo) steps.indexOfFirst { it.dependsOnVideo } else steps.size
}

public data class TemplateSender(
    val id: String,
    val name: String,
    val avatar: MediaRef? = null,
    val time: Long = 0,
    val title: String = "$name 的原始内容",
)

/** 统一模板语法；字段取值及绘图、下载、出站策略由调用方提供。 */
public object MessageTemplateEngine {
    private val cache = object : LinkedHashMap<String, CompiledMessageTemplate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompiledMessageTemplate>?): Boolean = size > 256
    }
    private val placeholders = Regex("\\{([a-zA-Z0-9_]+)\\}")
    private val wrappedEscapes = Regex("""\\+[ \t]*\r?\n[ \t]*([nr])""")
    public val knownFields: Set<String> = TemplateCatalog.fields.map { it.key }.toSet()

    @Synchronized
    public fun compile(template: String): CompiledMessageTemplate = cache.getOrPut(template) {
        val value = wrappedEscapes.replace(template) { "\\${it.groupValues[1]}" }
        val steps = mutableListOf<TemplateStep>()
        fun add(text: String, forward: Boolean) {
            (if (forward) listOf(text) else text.split("\\r")).forEach {
                steps += TemplateStep(it, forward, placeholders.findAll(it).map { match -> match.groupValues[1] }.toSet())
            }
        }
        var index = 0
        while (index < value.length) {
            val start = value.indexOf("{>>}", index)
            val end = value.indexOf("{<<}", index)
            require(end < 0 || start >= 0 && start < end) { "合并转发模板结束标记 {<<} 缺少开始标记 {>>}" }
            if (start < 0) { add(value.substring(index), false); break }
            if (start > index) add(value.substring(index, start), false)
            val close = value.indexOf("{<<}", start + 4)
            require(close >= 0) { "合并转发模板开始标记 {>>} 缺少结束标记 {<<}" }
            val nested = value.indexOf("{>>}", start + 4)
            require(nested < 0 || nested > close) { "合并转发模板不支持嵌套 {>>}" }
            add(value.substring(start + 4, close), true)
            index = close + 4
        }
        CompiledMessageTemplate(steps)
    }

    public fun render(
        steps: List<TemplateStep>,
        sender: TemplateSender,
        resolve: (String, Boolean) -> List<MessageContent>?,
        missing: (String) -> Unit = {},
        unknown: (String) -> Unit = {},
    ): List<MessageBatch> {
        fun fragment(template: String, forward: Boolean): MessageBatch? {
            val content = mutableListOf<MessageContent>()
            fun text(value: String) {
                if (value.isEmpty()) return
                val last = content.lastOrNull() as? MessageContent.Text
                if (last == null) content += MessageContent.Text(value)
                else content[content.lastIndex] = last.copy(fallbackText = last.fallbackText + value)
            }
            val input = template.replace("\\n", "\n")
            var valuePrefix = "\u0000TEMPLATE_VALUE_"
            while (valuePrefix in input) valuePrefix += "_"
            val values = mutableListOf<List<MessageContent>>()
            val source = StringBuilder()
            var position = 0
            placeholders.findAll(input).forEach { match ->
                source.append(input.substring(position, match.range.first))
                val field = match.groupValues[1]
                val result = if (forward && field == "atAll") emptyList() else resolve(field, !forward)
                when {
                    result != null && result.isNotEmpty() -> {
                        source.append(valuePrefix).append(values.size).append('\u0000')
                        values += result
                    }
                    field in knownFields -> { missing(field); source.append(MISSING) }
                    else -> { unknown(field); source.append(match.value) }
                }
                position = match.range.last + 1
            }
            source.append(input.substring(position))
            // 只清理模板文本；字段值保持隔离，避免正文、表情、URL 被当成空标签删除。
            val cleaned = cleanMissing(source.toString())
            val valueTokens = Regex("${Regex.escape(valuePrefix)}(\\d+)\u0000")
            position = 0
            valueTokens.findAll(cleaned).forEach { match ->
                text(cleaned.substring(position, match.range.first))
                values[match.groupValues[1].toInt()].forEach { item ->
                    if (item is MessageContent.Text) text(item.fallbackText) else content += item
                }
                position = match.range.last + 1
            }
            text(cleaned.substring(position))
            val normalized = content.toMutableList()
            (normalized.firstOrNull() as? MessageContent.Text)?.let { normalized[0] = it.copy(fallbackText = it.fallbackText.trimStart('\r', '\n')) }
            (normalized.lastOrNull() as? MessageContent.Text)?.let { normalized[normalized.lastIndex] = it.copy(fallbackText = it.fallbackText.trimEnd('\r', '\n')) }
            return normalized.filterNot { it is MessageContent.Text && it.fallbackText.isEmpty() }
                .takeIf { it.any { item -> item !is MessageContent.Text || item.fallbackText.isNotBlank() } }?.let(::MessageBatch)
        }
        return steps.mapNotNull { step ->
            if (!step.forward) fragment(step.value, false)
            else {
                val nodes = step.value.split("\\r").mapNotNull { fragment(it, true) }.map {
                    ForwardNode(senderId = sender.id, senderName = sender.name, senderAvatar = sender.avatar, time = sender.time, batches = listOf(it))
                }
                nodes.takeIf { it.isNotEmpty() }?.let {
                    MessageBatch(listOf(MessageContent.Forward(
                        fallbackText = "[合并转发] ${sender.title}", title = sender.title, summary = "共 ${nodes.size} 条内容",
                        sourceName = sender.name, sourceAvatar = sender.avatar, nodes = nodes,
                    )))
                }
            }
        }
    }

    private fun cleanMissing(text: String): String = if (MISSING !in text) text else text.split('\n').mapNotNull { line ->
        if (MISSING !in line) line else {
            val cleaned = line.replace(Regex("[^\\s\u0000，,；;|/]*[：:][ \\t]*$MISSING"), "")
                .replace(MISSING, "").trim(' ', '\t', '，', ',', '；', ';', '|', '/', '：', ':', '-', '·')
            cleaned.takeIf { it.isNotBlank() }
        }
    }.joinToString("\n")

    private const val MISSING = "\u0000TEMPLATE_MISSING\u0000"
}
