package top.colter.dynamic.draw.tools

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

public val Long.formatTime: String
    get() = formatTime()

public fun Long.formatTime(template: String = "yyyy年MM月dd日 HH:mm:ss"): String = DateTimeFormatter.ofPattern(template)
    .format(LocalDateTime.ofEpochSecond(this, 0, OffsetDateTime.now().offset))