package top.colter.dynamic

import kotlinx.coroutines.*


object DynamicApplication : CoroutineScope {
    private val job = Job()
    override val coroutineContext = Dispatchers.Default + job
    fun run() {

        // 加载插件
        //    检测前后端

        // 加载数据库

        // 初始化插件

        // 运行插件

        // 启动后端

        // 启动前端

        launch {
            delay(1000)
        }

    }
}