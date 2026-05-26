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
import top.colter.dynamic.WebAdminConfig
import top.colter.dynamic.core.plugin.PluginManager

public class AdminServer(
    private val config: WebAdminConfig,
    private val pluginManager: PluginManager,
    private val configProvider: () -> top.colter.dynamic.MainDynamicConfig,
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    public fun start() {
        if (engine != null) return
        val context = AdminServerContext(
            token = config.token,
            service = AdminService(pluginManager, configProvider),
            loginService = AdminLoginService(
                platformPluginResolver = { platformId -> pluginManager.findPlatformPublisherPlugin(platformId) },
            ),
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
    val service: AdminService,
    val loginService: AdminLoginService,
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
            get("/subscribers") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.subscribers() }
            }
            patch("/subscribers/{id}") {
                if (!call.ensureAuthorized(context)) return@patch
                val id = call.pathInt("id")
                call.respondApi { context.service.updateSubscriber(id, call.receive()) }
            }
            get("/subscriptions") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.subscriptions() }
            }
            post("/subscriptions") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.createSubscription(call.receive()) }
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
            get("/templates") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.templates() }
            }
            put("/template-bindings/publisher/{publisherId}") {
                if (!call.ensureAuthorized(context)) return@put
                val publisherId = call.pathInt("publisherId")
                call.respondApi { context.service.setPublisherTemplate(publisherId, call.receive()) }
            }
            delete("/template-bindings/publisher/{publisherId}") {
                if (!call.ensureAuthorized(context)) return@delete
                val publisherId = call.pathInt("publisherId")
                call.respondApi { context.service.removePublisherTemplate(publisherId) }
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
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "not found"))
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
    if (AdminAuth.isAuthorized(context.token, this)) return true
    respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
    return false
}

private suspend inline fun <reified T : Any> ApplicationCall.respondApi(crossinline block: suspend () -> T) {
    try {
        respond(block())
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "invalid request body"))
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "not found"))
    } catch (e: IllegalStateException) {
        respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "conflict"))
    }
}

private fun ApplicationCall.pathInt(name: String): Int {
    return pathString(name).toIntOrNull() ?: throw IllegalArgumentException("invalid $name")
}

private fun ApplicationCall.pathString(name: String): String {
    return parameters[name] ?: throw IllegalArgumentException("missing $name")
}

private fun ApplicationCall.optionalQueryInt(name: String): Int? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("invalid $name")
}
