package top.colter.dynamic

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.Logger.ROOT_LOGGER_NAME
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.status.Status

class LogbackConfigTest {
    @Test
    fun shouldConfigureRollingFileAppenderDirectory() {
        val logDir = Files.createTempDirectory("dynamic-bot-logs-test")
        val previousLogDir = System.getProperty("LOG_DIR")
        System.setProperty("LOG_DIR", logDir.toString())

        val context = LoggerContext()
        try {
            JoranConfigurator().apply {
                this.context = context
                doConfigure(resource.resolve("logback.xml"))
            }
            context.start()

            val logger = context.getLogger(ROOT_LOGGER_NAME)
            assertEquals(ch.qos.logback.classic.Level.INFO, logger.level)

            val fileAppender = logger.getAppender("FILE") as? RollingFileAppender<*>
            assertNotNull(fileAppender, "应该注册文件日志 appender")
            assertTrue(fileAppender.isStarted, "文件日志 appender 应该成功启动")
            assertEquals(
                logDir.resolve("dynamic-bot.log").normalizedAbsolutePath(),
                Path.of(fileAppender.file).normalizedAbsolutePath(),
            )
            val errors = context.statusManager.copyOfStatusList
                .filter { it.level == Status.ERROR }
                .joinToString("\n") { it.message }
            assertTrue(errors.isBlank(), errors)
        } finally {
            if (previousLogDir == null) {
                System.clearProperty("LOG_DIR")
            } else {
                System.setProperty("LOG_DIR", previousLogDir)
            }
            context.stop()
        }
    }

    private fun Path.normalizedAbsolutePath(): Path = toAbsolutePath().normalize()
}
