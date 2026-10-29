package top.colter.dynamic.plugin

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginHookRunnerTest {

    /**
     * 钩子内部阻塞在不可取消的调用上时，必须只放弃等待并抛超时，
     * 而不是跟着一起卡死（否则停止项目会一直退不出去）。
     */
    @Test
    fun runShouldGiveUpWhenHookBlocks() {
        val runner = PluginHookRunner(200)
        val startAt = System.currentTimeMillis()

        val error = assertFailsWith<IllegalStateException> {
            runner.run("test-plugin", "stop") {
                Thread.sleep(2_000)
            }
        }

        assertTrue(error.message.orEmpty().contains("超时"), "message=${error.message}")
        val elapsed = System.currentTimeMillis() - startAt
        assertTrue(elapsed < 1_500, "run() should not wait for the blocked hook: elapsed=${elapsed}ms")
    }

    @Test
    fun runShouldCompleteWhenHookFinishes() {
        PluginHookRunner(1_000).run("test-plugin", "stop") {
            // 正常结束，不应抛异常
        }
    }
}
