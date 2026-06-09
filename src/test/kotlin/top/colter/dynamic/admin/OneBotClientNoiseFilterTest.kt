package top.colter.dynamic.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.spi.FilterReply
import kotlin.test.Test
import kotlin.test.assertEquals

class OneBotClientNoiseFilterTest {
    private val filter = OneBotClientNoiseFilter().apply { start() }

    @Test
    fun `filter should deny styled onebot sdk logs`() {
        val event = loggingEvent(
            loggerName = "OneBot Client",
            message = "▌ §c服务器ws://127.0.0.1:6700因Connection refused: connect已关闭",
        )

        assertEquals(FilterReply.DENY, filter.decide(event))
    }

    @Test
    fun `filter should keep normal application logs`() {
        val event = loggingEvent(
            loggerName = "top.colter.dynamic.onebot.ForwardWsOneBotGateway",
            message = "OneBot 正向连接账号不可用",
        )

        assertEquals(FilterReply.NEUTRAL, filter.decide(event))
    }

    private fun loggingEvent(loggerName: String, message: String): LoggingEvent {
        return LoggingEvent().apply {
            this.loggerName = loggerName
            this.message = message
            level = Level.INFO
        }
    }
}
