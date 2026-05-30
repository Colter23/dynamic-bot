package top.colter.dynamic.command

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandMatch
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole

public class CommandRegistry {
    private data class RegisteredCommand(
        val handler: CommandHandler,
        val spec: CommandSpec,
        val ownerPluginId: String,
        val order: Long,
    )

    private val registrations: MutableList<RegisteredCommand> = CopyOnWriteArrayList()
    private val sequence: AtomicLong = AtomicLong(0)

    public fun register(handler: CommandHandler, ownerPluginId: String) {
        val spec = handler.spec
        validateOwner(ownerPluginId)
        validateSpec(spec)
        val newPaths = normalizedPaths(spec)
        require(newPaths.distinct().size == newPaths.size) {
            "命令 path 与 alias 不能重复：${spec.path.joinToString(" ")}"
        }

        synchronized(this) {
            registrations.forEach { existing ->
                val duplicatedPath = newPaths.firstOrNull { candidate ->
                    normalizedPaths(existing.spec).any { it == candidate }
                }
                require(duplicatedPath == null) {
                    "命令路径已注册：${duplicatedPath?.joinToString(" ")}"
                }
            }
            registrations += RegisteredCommand(
                handler = handler,
                spec = spec,
                ownerPluginId = ownerPluginId,
                order = sequence.incrementAndGet(),
            )
        }
    }

    public fun unregisterByOwner(ownerPluginId: String): Int {
        val removed = registrations.count { it.ownerPluginId == ownerPluginId }
        registrations.removeIf { it.ownerPluginId == ownerPluginId }
        return removed
    }

    public fun match(tokens: List<String>): CommandMatch? {
        if (tokens.isEmpty()) return null
        val loweredTokens = tokens.map { it.lowercase() }
        val orderedRegistrations = registrations.sortedBy { it.order }

        var bestMatch: CommandMatch? = null
        var bestLength = -1
        var bestAlias = true

        orderedRegistrations.forEach { registration ->
            val candidates = listOf(registration.spec.path to false) + registration.spec.aliases.map { it to true }
            candidates.forEach { (candidatePath, alias) ->
                val normalizedPath = normalizePath(candidatePath)
                if (loweredTokens.size < normalizedPath.size) return@forEach
                if (loweredTokens.take(normalizedPath.size) != normalizedPath) return@forEach

                if (normalizedPath.size > bestLength || (normalizedPath.size == bestLength && bestAlias && !alias)) {
                    bestMatch = CommandMatch(
                        spec = registration.spec,
                        handler = registration.handler,
                        matchedPath = candidatePath,
                        args = tokens.drop(normalizedPath.size),
                    )
                    bestLength = normalizedPath.size
                    bestAlias = alias
                }
            }
        }

        return bestMatch
    }

    public fun visibleCommandsFor(role: CommandRole): List<CommandSpec> {
        return listCommands().filter { role.satisfies(it.requiredRole) }
    }

    public fun listCommands(): List<CommandSpec> {
        return registrations
            .map { it.spec }
            .distinctBy { normalizePath(it.path).joinToString(" ") }
            .sortedBy { normalizePath(it.path).joinToString(" ") }
    }

    private fun validateOwner(ownerPluginId: String) {
        require(ownerPluginId.isNotBlank()) { "命令 ownerPluginId 不能为空" }
    }

    private fun validateSpec(spec: CommandSpec) {
        require(spec.path.isNotEmpty()) { "命令 path 不能为空" }
        require(spec.path.all { it.isNotBlank() }) { "命令 path 不能包含空片段" }
        require(spec.aliases.all { alias -> alias.isNotEmpty() && alias.all { it.isNotBlank() } }) {
            "命令 alias 不能包含空片段"
        }
    }

    private fun normalizedPaths(spec: CommandSpec): List<List<String>> {
        return (listOf(spec.path) + spec.aliases).map(::normalizePath)
    }

    private fun normalizePath(path: List<String>): List<String> {
        return path.map { it.lowercase() }
    }
}
