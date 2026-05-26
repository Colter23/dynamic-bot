package top.colter.dynamic

import java.util.concurrent.CountDownLatch

public fun main() {
    val shutdownLatch = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("Shutdown signal received, stopping application...")
            DynamicApplication.shutdown()
        }
    )

    DynamicApplication.onShutdown {
        shutdownLatch.countDown()
    }
    DynamicApplication.run()
    println("Application is running. Press Ctrl+C to exit.")
    shutdownLatch.await()
}
