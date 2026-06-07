package top.colter.dynamic.plugin

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class PluginHookRunner(
    private val timeoutMs: Long,
) {
    fun run(pluginId: String, operation: String, block: suspend () -> Unit) {
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                block()
            } ?: throw IllegalStateException(
                "插件 $operation 钩子执行超时：pluginId=$pluginId，timeoutMs=$timeoutMs",
            )
        }
    }
}
