package top.colter.dynamic

import io.ktor.server.application.*
import top.colter.dynamic.plugins.configureHTTP
import top.colter.dynamic.plugins.configureRouting
import top.colter.dynamic.plugins.configureSerialization
import top.colter.dynamic.plugins.configureSockets
import top.colter.dynamic.plugins.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureSockets()
    configureRouting()
}
