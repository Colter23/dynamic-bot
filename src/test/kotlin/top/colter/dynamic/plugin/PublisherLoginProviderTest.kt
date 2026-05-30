package top.colter.dynamic.plugin

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginStatus

class PublisherLoginProviderTest {
    @Test
    fun `checkLoginState should default to unsupported`() = runBlocking {
        val provider = object : PublisherLoginProvider {
            override val platformId: PlatformId = PlatformId.of("demo")
        }

        val result = provider.checkLoginState()

        assertEquals(PublisherLoginStatus.UNSUPPORTED, result.status)
        assertEquals("不支持登录状态检查", result.message)
    }
}
