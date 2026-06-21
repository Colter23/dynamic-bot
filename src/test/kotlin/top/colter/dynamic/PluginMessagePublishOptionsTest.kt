package top.colter.dynamic

import kotlin.test.Test
import kotlin.test.assertEquals
import top.colter.dynamic.core.data.MessageDeliveryPolicy
import top.colter.dynamic.core.data.MessageRecordPolicy
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions

class PluginMessagePublishOptionsTest {
    @Test
    fun `delivery policy should follow record policy retry default`() {
        val transientPolicy = PluginMessagePublishOptions(
            recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60),
        ).toDeliveryPolicy(nowEpochSeconds = 10)
        val durablePolicy = PluginMessagePublishOptions(
            recordPolicy = MessageRecordPolicy.Durable,
        ).toDeliveryPolicy(nowEpochSeconds = 10)

        assertEquals(MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 70), transientPolicy)
        assertEquals(MessageDeliveryPolicy(retry = true), durablePolicy)
    }

    @Test
    fun `delivery policy should allow durable retry override`() {
        val policy = PluginMessagePublishOptions(
            retry = false,
            recordPolicy = MessageRecordPolicy.Durable,
        ).toDeliveryPolicy(nowEpochSeconds = 10)

        assertEquals(MessageDeliveryPolicy(retry = false), policy)
    }

    @Test
    fun `delivery policy should prefer explicit expiration`() {
        val policy = PluginMessagePublishOptions(
            expiresInSeconds = 30,
            recordPolicy = MessageRecordPolicy.Transient(retentionSeconds = 60),
        ).toDeliveryPolicy(nowEpochSeconds = 10)

        assertEquals(MessageDeliveryPolicy(retry = false, expiresAtEpochSeconds = 40), policy)
    }
}
