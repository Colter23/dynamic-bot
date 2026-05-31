package top.colter.dynamic.admin

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

public class OneBotClientNoiseFilter : Filter<ILoggingEvent>() {
    override fun decide(event: ILoggingEvent?): FilterReply {
        if (event == null) return FilterReply.NEUTRAL
        if (event.loggerName == ONEBOT_CLIENT_LOGGER &&
            event.formattedMessage.contains(UNSUPPORTED_COMMAND_SYSTEM_MESSAGE)
        ) {
            return FilterReply.DENY
        }
        return FilterReply.NEUTRAL
    }

    private companion object {
        const val ONEBOT_CLIENT_LOGGER: String = "OneBot Client"
        const val UNSUPPORTED_COMMAND_SYSTEM_MESSAGE: String = "命令系统尚未支持"
    }
}
