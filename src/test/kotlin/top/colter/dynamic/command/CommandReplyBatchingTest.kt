package top.colter.dynamic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.MessageContent

class CommandReplyBatchingTest {

    @Test
    fun emptyLinesProduceNoBatch() {
        assertTrue(splitIntoBatches(emptyList()).isEmpty())
    }

    @Test
    fun shortLinesStayInOneBatch() {
        val batches = splitIntoBatches(listOf("a", "b", "c"))

        assertEquals(1, batches.size)
        assertEquals("a\nb\nc", batches.single().content.single().text())
    }

    @Test
    fun longListSplitsIntoMultipleBatches() {
        val lines = (1..200).map { "第 $it 行内容，用于验证自动分条逻辑是否生效" }

        val batches = splitIntoBatches(lines)

        assertTrue(batches.size > 1, "应拆成多条消息：${batches.size}")
        assertTrue(
            batches.all { it.content.single().text().length <= MAX_REPLY_CHARS_PER_BATCH },
            "每条消息都不应超过上限",
        )
        // 拼回去内容不能丢
        val joined = batches.joinToString("\n") { it.content.single().text() }
        assertEquals(lines.joinToString("\n"), joined)
    }

    @Test
    fun oversizedSingleLineIsChunked() {
        val line = "x".repeat(MAX_REPLY_CHARS_PER_BATCH * 2 + 10)

        val batches = splitIntoBatches(listOf(line))

        assertEquals(3, batches.size)
        assertEquals(line, batches.joinToString("") { it.content.single().text() })
    }

    private fun MessageContent.text(): String {
        return (this as? MessageContent.Text)?.fallbackText ?: error("expected text content")
    }
}
