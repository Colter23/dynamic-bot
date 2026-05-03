package top.colter.dynamic.command

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.ChatType
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.event.CommandEvent
import top.colter.dynamic.core.event.CommandResultEvent
import top.colter.dynamic.core.event.EventManger
import top.colter.dynamic.core.event.Listener
import top.colter.dynamic.core.event.broadcast
import top.colter.dynamic.core.event.register
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandListenerTest {

    @Test
    fun `non admin should be rejected`() = runBlocking {
        EventManger.shutdown()
        val listener = CommandListener(adminUsers = setOf("admin"))
        listener.register<CommandEvent>()

        val received = CompletableDeferred<CommandResultEvent>()
        object : Listener<CommandResultEvent> {
            override suspend fun onMessage(event: CommandResultEvent) {
                received.complete(event)
            }
        }.register<CommandResultEvent>()

        CommandEvent(
            sourcePlugin = "test",
            context = CommandContext("onebot", ChatType.GROUP, "100", "not-admin"),
            rawText = "/db status",
            commandName = "status",
            args = emptyList(),
            traceId = "trace-1",
        ).broadcast()

        val result = received.await()
        assertEquals(CommandStatus.REJECTED, result.status)
    }
}
