package top.colter.dynamic.command

public data class ParsedCommand(
    val tokens: List<String>,
) {
    val commandName: String = tokens.firstOrNull().orEmpty()
    val args: List<String> = tokens.drop(1)
}

public object CommandParser {
    public fun parse(rawText: String, prefix: String): ParsedCommand? {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank()) return null

        val trimmed = rawText.trim()
        if (!trimmed.startsWith(normalizedPrefix)) return null

        val bodyWithBoundary = trimmed.removePrefix(normalizedPrefix)
        if (bodyWithBoundary.isNotEmpty() && !bodyWithBoundary.first().isWhitespace()) return null

        val body = bodyWithBoundary.trim()
        if (body.isBlank()) return ParsedCommand(tokens = listOf("help"))

        val parts = body.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return ParsedCommand(tokens = listOf(parts.first().lowercase()) + parts.drop(1))
    }
}
