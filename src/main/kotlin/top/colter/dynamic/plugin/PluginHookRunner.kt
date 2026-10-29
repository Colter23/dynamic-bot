package top.colter.dynamic.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class PluginHookRunner(
    private val timeoutMs: Long,
) {
    // 钩子可能在不可取消的阻塞调用上永久卡住，因此放到独立 scope 执行：
    // 既不受应用/插件 scope 取消的影响，也不会把关闭流程一起拖住。
    private val hookScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun run(pluginId: String, operation: String, block: suspend () -> Unit) {
        // 只等待“完成到超时为止”，不再等钩子协程真正结束。
        // 原先 runBlocking + withTimeoutOrNull 无法打断阻塞调用，
        // 会导致停止项目时永久卡住、进程退不掉。
        val hookJob = hookScope.async { block() }
        val completed = runBlocking {
            withTimeoutOrNull(timeoutMs) { hookJob.await() }
        }
        if (completed == null) {
            hookJob.cancel()
            throw IllegalStateException(
                "插件 $operation 钩子执行超时：pluginId=$pluginId，timeoutMs=$timeoutMs",
            )
        }
    }
}
