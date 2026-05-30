package top.colter.dynamic.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandParserTest {

    @Test
    fun parseShouldReturnNullWhenPrefixNotMatched() {
        assertNull(CommandParser.parse("help", "/db"))
    }

    @Test
    fun parseShouldReturnNullWhenPrefixIsOnlyPartialMatch() {
        assertNull(CommandParser.parse("/dbx status", "/db"))
    }

    @Test
    fun parseShouldReturnHelpWhenNoSubCommand() {
        val parsed = CommandParser.parse("/db", "/db")
        assertNotNull(parsed)
        assertEquals("help", parsed.commandName)
        assertEquals(emptyList(), parsed.args)
        assertEquals(listOf("help"), parsed.tokens)
    }

    @Test
    fun parseShouldSplitArgsByWhitespace() {
        val parsed = CommandParser.parse("/db subscribe bilibili 123", "/db")
        assertNotNull(parsed)
        assertEquals("subscribe", parsed.commandName)
        assertEquals(listOf("bilibili", "123"), parsed.args)
        assertEquals(listOf("subscribe", "bilibili", "123"), parsed.tokens)
    }
}
