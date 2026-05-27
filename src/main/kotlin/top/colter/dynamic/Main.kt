package top.colter.dynamic

import java.util.concurrent.CountDownLatch
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor("top.colter.dynamic.Main")

public fun main() {
    val shutdownLatch = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info { "收到停止信号，正在关闭应用" }
            DynamicApplication.shutdown()
        }
    )

    DynamicApplication.onShutdown {
        shutdownLatch.countDown()
    }
    DynamicApplication.run()
    shutdownLatch.await()
}
