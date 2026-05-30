package top.colter.dynamic.admin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.WebAdminConfig
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.plugin.PluginManager

public class AdminServer(
    private val config: WebAdminConfig,
    private val pluginManager: PluginManager,
    private val configProvider: () -> MainDynamicConfig,
    private val mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult,
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val stopRequester: ((String) -> Unit)? = null,
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    public fun start() {
        if (engine != null) return
        val context = AdminServerContext(
            token = config.token,
            tokenProvider = { configProvider().webAdmin.token },
            service = AdminService(
                pluginManager = pluginManager,
                configProvider = configProvider,
                mainConfigUpdater = mainConfigUpdater,
                configService = configService,
                commandRegistry = commandRegistry,
                eventBus = eventBus,
            ),
            loginService = AdminLoginService(
                loginProviderResolver = { platformId -> pluginManager.findPublisherLoginProvider(platformId) },
            ),
            stopRequester = stopRequester,
        )
        engine = embeddedServer(Netty, host = config.host, port = config.port) {
            adminModule(context)
        }.start(wait = false)
    }

    public fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        engine = null
    }
}

public data class AdminServerContext(
    val token: String,
    val tokenProvider: () -> String = { token },
    val service: AdminService,
    val loginService: AdminLoginService,
    val stopRequester: ((String) -> Unit)? = null,
)

public fun Application.adminModule(context: AdminServerContext) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        )
    }

    routing {
        get("/") {
            call.respondText(AdminStatic.indexHtml, ContentType.Text.Html)
        }
        get("/admin") {
            call.respondText(AdminStatic.indexHtml, ContentType.Text.Html)
        }
        get("/admin/{...}") {
            call.respondText(AdminStatic.indexHtml, ContentType.Text.Html)
        }

        route("/api") {
            get("/overview") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.overview() }
            }
            get("/plugins") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.plugins() }
            }
            post("/plugins/{id}/reload") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.reloadPlugin(id) }
            }
            post("/system/stop") {
                if (!call.ensureAuthorized(context)) return@post
                val requester = context.stopRequester
                if (requester == null) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("主项目停止功能未配置"))
                    return@post
                }
                call.respond(ActionResultResponse(changed = true, message = "已请求停止主项目"))
                requester("web-admin")
            }
            get("/platform-logins") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.platformLogins() }
            }
            get("/publishers") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.publishers() }
            }
            patch("/publishers/{id}") {
                if (!call.ensureAuthorized(context)) return@patch
                val id = call.pathInt("id")
                call.respondApi { context.service.updatePublisher(id, call.receive()) }
            }
            delete("/publishers/{id}") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.deletePublisher(id) }
            }
            get("/subscribers") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.subscribers() }
            }
            get("/subscriber-target-platforms") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.subscriberTargetPlatforms() }
            }
            get("/subscriber-targets") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi {
                    context.service.subscriberTargets(
                        platformId = call.request.queryParameters["platformId"],
                        type = call.request.queryParameters["type"],
                    )
                }
            }
            patch("/subscribers/{id}") {
                if (!call.ensureAuthorized(context)) return@patch
                val id = call.pathInt("id")
                call.respondApi { context.service.updateSubscriber(id, call.receive()) }
            }
            delete("/subscribers/{id}") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.deleteSubscriber(id) }
            }
            get("/subscriptions") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.subscriptions() }
            }
            post("/subscriptions") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.createSubscription(call.receive()) }
            }
            patch("/subscriptions/{id}") {
                if (!call.ensureAuthorized(context)) return@patch
                val id = call.pathInt("id")
                call.respondApi { context.service.updateSubscription(id, call.receive()) }
            }
            delete("/subscriptions/{id}") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.deleteSubscription(id) }
            }
            get("/filter-rules") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi {
                    context.service.filterRules(call.optionalQueryInt("subscriptionId"))
                }
            }
            post("/filter-rules") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.createFilterRule(call.receive()) }
            }
            delete("/filter-rules/{id}") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.deleteFilterRule(id) }
            }
            delete("/subscriptions/{id}/filter-rules") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.clearFilterRules(id) }
            }
            get("/configs") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.configs() }
            }
            get("/configs/{id}") {
                if (!call.ensureAuthorized(context)) return@get
                val id = call.pathString("id")
                call.respondApi { context.service.config(id) }
            }
            put("/configs/{id}") {
                if (!call.ensureAuthorized(context)) return@put
                val id = call.pathString("id")
                call.respondApi { context.service.updateConfig(id, call.receive()) }
            }
            post("/platforms/{platform}/login/cookie") {
                if (!call.ensureAuthorized(context)) return@post
                val platform = call.pathString("platform")
                val request = call.receive<CookieLoginRequest>()
                call.respondApi { context.loginService.loginByCookie(platform, request.cookie) }
            }
            post("/platforms/{platform}/login/qr") {
                if (!call.ensureAuthorized(context)) return@post
                val platform = call.pathString("platform")
                call.respondApi { context.loginService.startQrLogin(platform) }
            }
            get("/login/qr/{loginId}") {
                if (!call.ensureAuthorized(context)) return@get
                val loginId = call.pathString("loginId")
                call.respondApi { context.loginService.qrLoginStatus(loginId) }
            }
            delete("/login/qr/{loginId}") {
                if (!call.ensureAuthorized(context)) return@delete
                val loginId = call.pathString("loginId")
                call.respondApi { context.loginService.cancelQrLogin(loginId) }
            }
            get("/login/qr/{loginId}/image") {
                if (!call.ensureAuthorized(context)) return@get
                val loginId = call.pathString("loginId")
                try {
                    call.respondBytes(
                        bytes = context.loginService.qrImageBytes(loginId),
                        contentType = ContentType.Image.PNG,
                    )
                } catch (e: NoSuchElementException) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "未找到资源"))
                }
            }
        }
    }
}

private object AdminAuth {
    fun isAuthorized(expectedToken: String, call: ApplicationCall): Boolean {
        if (expectedToken.isBlank()) return false
        val header = call.request.headers[HttpHeaders.Authorization] ?: return false
        val token = header.removePrefix("Bearer ").takeIf { it != header } ?: return false
        return MessageDigest.isEqual(token.toByteArray(), expectedToken.toByteArray())
    }
}

private suspend fun ApplicationCall.ensureAuthorized(context: AdminServerContext): Boolean {
    if (AdminAuth.isAuthorized(context.tokenProvider(), this)) return true
    respond(HttpStatusCode.Unauthorized, ErrorResponse("认证失败"))
    return false
}

private suspend inline fun <reified T : Any> ApplicationCall.respondApi(crossinline block: suspend () -> T) {
    try {
        respond(block())
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "请求体格式无效"))
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "资源不存在"))
    } catch (e: PluginReloadFailedException) {
        respond(HttpStatusCode.Conflict, e.response)
    } catch (e: IllegalStateException) {
        respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "当前状态不允许执行该操作"))
    }
}

private fun ApplicationCall.pathInt(name: String): Int {
    return pathString(name).toIntOrNull() ?: throw IllegalArgumentException("路径参数无效：$name")
}

private fun ApplicationCall.pathString(name: String): String {
    return parameters[name] ?: throw IllegalArgumentException("缺少路径参数：$name")
}

private fun ApplicationCall.optionalQueryInt(name: String): Int? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("查询参数无效：$name")
}
