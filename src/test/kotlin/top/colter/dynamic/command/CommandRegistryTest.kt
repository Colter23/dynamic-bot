package top.colter.dynamic.command

import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandRegistryTest {

    @Test
    fun `match should prefer longest path`() {
        val registry = CommandRegistry()
        registry.register(FakeCommandHandler(CommandSpec(path = listOf("subscribe"))), "main")
        registry.register(
            FakeCommandHandler(
                CommandSpec(
                    path = listOf("subscribe", "check"),
                )
            ),
            "main",
        )

        val match = registry.match(listOf("subscribe", "check", "bilibili", "123"))

        assertNotNull(match)
        assertEquals(listOf("subscribe", "check"), match.matchedPath)
        assertEquals(listOf("bilibili", "123"), match.args)
    }

    @Test
    fun `visibleCommandsFor should filter admin commands`() {
        val registry = CommandRegistry()
        registry.register(
            FakeCommandHandler(
                CommandSpec(
                    path = listOf("help"),
                )
            ),
            "main",
        )
        registry.register(
            FakeCommandHandler(
                CommandSpec(
                    path = listOf("status"),
                    requiredRole = CommandRole.ADMIN,
                )
            ),
            "main",
        )

        val userCommands = registry.visibleCommandsFor(CommandRole.USER).map { it.path.joinToString(" ") }
        val adminCommands = registry.visibleCommandsFor(CommandRole.ADMIN).map { it.path.joinToString(" ") }

        assertEquals(listOf("help"), userCommands)
        assertEquals(listOf("help", "status"), adminCommands)
    }

    @Test
    fun `unregisterByOwner should remove contributed commands`() {
        val registry = CommandRegistry()
        registry.register(FakeCommandHandler(CommandSpec(path = listOf("help"))), "main")
        registry.register(FakeCommandHandler(CommandSpec(path = listOf("sync"))), "plugin-a")

        val removed = registry.unregisterByOwner("plugin-a")

        assertEquals(1, removed)
        assertNull(registry.match(listOf("sync")))
        assertNotNull(registry.match(listOf("help")))
    }

    @Test
    fun `register should reject duplicate aliases inside one spec`() {
        val registry = CommandRegistry()

        assertFailsWith<IllegalArgumentException> {
            registry.register(
                FakeCommandHandler(
                    CommandSpec(
                        path = listOf("help"),
                        aliases = listOf(listOf("HELP")),
                    )
                ),
                "main",
            )
        }
    }

    private class FakeCommandHandler(
        override val spec: CommandSpec,
    ) : CommandHandler {
        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            return CommandExecutionResult.success("ok")
        }
    }
}
