package top.colter.dynamic

import java.net.BindException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupFailureTest {
    @Test
    fun shouldDetectNestedPortOccupiedError() {
        val error = IllegalStateException(
            "Netty 启动失败",
            BindException("Address already in use: bind"),
        )

        assertTrue(error.isAddressAlreadyInUse())
    }

    @Test
    fun shouldNotTreatOrdinaryErrorsAsPortOccupied() {
        val error = IllegalStateException("后台启动失败")

        assertFalse(error.isAddressAlreadyInUse())
    }

    @Test
    fun shouldBuildClearWebAdminPortOccupiedMessage() {
        val message = webAdminPortOccupiedMessage(WebAdminConfig(host = "127.0.0.1", port = 2233))

        assertTrue(message.contains("Web 后台端口已被占用"))
        assertTrue(message.contains("127.0.0.1:2233"))
        assertTrue(message.contains("config/main.yml"))
        assertTrue(message.contains("webAdmin.port"))
    }
}
