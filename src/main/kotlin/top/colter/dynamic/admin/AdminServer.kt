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
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import top.colter.dynamic.MainDynamicConfig
import top.colter.dynamic.WebAdminConfig
import top.colter.dynamic.command.CommandRegistry
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.plugin.PlatformDrawAssetKeys
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.tools.loggerFor
import top.colter.dynamic.config.YamlConfigService
import top.colter.dynamic.draw.resource.PlatformDrawAssetRegistry
import top.colter.dynamic.draw.resource.PlatformDrawAssetResource
import top.colter.dynamic.event.EventBus
import top.colter.dynamic.media.OutboundMediaResult
import top.colter.dynamic.media.OutboundMediaService
import top.colter.dynamic.message.OutboundMessageService
import top.colter.dynamic.plugin.PluginManager

private val logger = loggerFor<AdminServer>()

public class AdminServer(
    private val config: WebAdminConfig,
    private val pluginManager: PluginManager,
    private val configProvider: () -> MainDynamicConfig,
    private val mainConfigUpdater: (MainDynamicConfig) -> ConfigApplyResult,
    private val configService: ConfigService = YamlConfigService(),
    private val commandRegistry: CommandRegistry = CommandRegistry(),
    private val eventBus: EventBus = EventBus(),
    private val drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry(),
    private val outboundMessageService: OutboundMessageService = OutboundMessageService(),
    private val outboundMediaService: OutboundMediaService = OutboundMediaService(configProvider),
    private val taskScheduler: TaskScheduler? = null,
    private val stopRequester: ((String) -> Unit)? = null,
    private val startedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var loginService: AdminLoginService? = null

    public fun start() {
        if (engine != null) return
        val adminLoginService = AdminLoginService(
            loginProviderResolver = { platformId -> pluginManager.findPublisherLoginProvider(platformId) },
        )
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
                outboundMessageService = outboundMessageService,
                mainTaskScheduler = taskScheduler,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
            loginService = adminLoginService,
            mediaService = AdminMediaService(
                configProvider = configProvider,
                registeredLocalMediaLookup = DatabaseAdminRegisteredLocalMediaLookup,
            ),
            outboundMediaService = outboundMediaService,
            drawAssetRegistry = drawAssetRegistry,
            stopRequester = stopRequester,
        )
        engine = embeddedServer(Netty, host = config.host, port = config.port) {
            adminModule(context)
        }.start(wait = false)
        loginService = adminLoginService
    }

    public fun stop() {
        val currentEngine = engine
        engine = null
        currentEngine?.let { server ->
            runCatching {
                server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
            }.onFailure { error ->
                logger.warn(error) { "管理后台停止异常，继续关闭其他服务" }
            }
        }

        val currentLoginService = loginService
        loginService = null
        currentLoginService?.let { service ->
            runCatching {
                runBlocking { service.close() }
            }.onFailure { error ->
                logger.warn(error) { "管理后台登录服务关闭异常，继续关闭其他服务" }
            }
        }
    }
}

public data class AdminServerContext(
    val token: String,
    val tokenProvider: () -> String = { token },
    val service: AdminService,
    val loginService: AdminLoginService,
    val mediaService: AdminMediaService = AdminMediaService(),
    val outboundMediaService: OutboundMediaService = OutboundMediaService(configProvider = { MainDynamicConfig() }),
    val drawAssetRegistry: PlatformDrawAssetRegistry = PlatformDrawAssetRegistry(),
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
        get("/admin/assets/{...}") {
            call.respondAdminStaticResource("/admin/assets/")
        }
        get("/admin/pages/{...}") {
            call.respondAdminStaticResource("/admin/pages/")
        }
        get("/admin/{...}") {
            call.respondText(AdminStatic.indexHtml, ContentType.Text.Html)
        }
        get("/media/outbound/{id}") {
            call.respondOutboundMedia {
                context.outboundMediaService.resolve(
                    profileId = call.request.queryParameters["profile"],
                    id = call.pathString("id"),
                    expires = call.request.queryParameters["expires"]?.toLongOrNull()
                        ?: throw IllegalArgumentException("缺少或无效的过期时间"),
                    signature = call.request.queryParameters["sig"]
                        ?: throw IllegalArgumentException("缺少媒体链接签名"),
                )
            }
        }
        get("/media/outbound-probe") {
            call.respondOutboundMedia {
                context.outboundMediaService.resolveProbe(
                    profileId = call.request.queryParameters["profile"],
                    expires = call.request.queryParameters["expires"]?.toLongOrNull()
                        ?: throw IllegalArgumentException("缺少或无效的过期时间"),
                    signature = call.request.queryParameters["sig"]
                        ?: throw IllegalArgumentException("缺少媒体链接签名"),
                )
            }
        }

        route("/api") {
            get("/dashboard") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.dashboard() }
            }
            get("/plugins") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.plugins() }
            }
            get("/plugin-catalog") {
                if (!call.ensureAuthorized(context)) return@get
                val force = call.request.queryParameters["force"]
                    ?.trim()
                    ?.let { it.equals("true", ignoreCase = true) || it == "1" }
                    ?: false
                call.respondApi { context.service.pluginCatalog(force = force) }
            }
            post("/plugin-catalog/{id}/install") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.installCatalogPlugin(id) }
            }
            post("/plugin-catalog/{id}/update") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.updateCatalogPlugin(id) }
            }
            post("/plugins/{id}/start") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.startPlugin(id) }
            }
            post("/plugins/{id}/stop") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.stopPlugin(id) }
            }
            post("/plugins/{id}/reload") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathString("id")
                call.respondApi { context.service.reloadPlugin(id) }
            }
            get("/system/status") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.systemStatus() }
            }
            get("/tasks") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.tasks() }
            }
            post("/tasks/start") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi {
                    context.service.startTask(
                        ownerType = call.requiredQueryString("ownerType"),
                        ownerId = call.requiredQueryString("ownerId"),
                        taskId = call.requiredQueryString("taskId"),
                    )
                }
            }
            post("/tasks/stop") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi {
                    context.service.stopTask(
                        ownerType = call.requiredQueryString("ownerType"),
                        ownerId = call.requiredQueryString("ownerId"),
                        taskId = call.requiredQueryString("taskId"),
                    )
                }
            }
            post("/tasks/restart") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi {
                    context.service.restartTask(
                        ownerType = call.requiredQueryString("ownerType"),
                        ownerId = call.requiredQueryString("ownerId"),
                        taskId = call.requiredQueryString("taskId"),
                    )
                }
            }
            get("/logs") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi {
                    context.service.logs(
                        since = call.optionalQueryLong("since"),
                        level = call.request.queryParameters["level"] ?: call.request.queryParameters["levels"],
                        logger = call.request.queryParameters["logger"],
                        query = call.request.queryParameters["q"],
                        limit = call.optionalQueryInt("limit"),
                    )
                }
            }
            get("/deliveries") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi {
                    context.service.deliveries(
                        status = call.request.queryParameters["status"],
                        platformId = call.request.queryParameters["platformId"],
                        targetKind = call.request.queryParameters["targetKind"]
                            ?: call.request.queryParameters["type"],
                        query = call.request.queryParameters["q"],
                        limit = call.optionalQueryInt("limit"),
                    )
                }
            }
            get("/deliveries/{id}") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.delivery(call.pathInt("id")) }
            }
            post("/message-forwards") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.forwardMessage(call.receive()) }
            }
            get("/media/image") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondMedia {
                    context.mediaService.image(
                        uri = call.request.queryParameters["uri"] ?: throw IllegalArgumentException("缺少图片地址"),
                        platformId = call.request.queryParameters["platformId"],
                        kind = call.request.queryParameters["kind"].toMediaKind(),
                    )
                }
            }
            get("/platforms/{platformId}/logo") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondPlatformLogo(context.drawAssetRegistry, call.pathString("platformId"))
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
                val force = call.request.queryParameters["force"]
                    ?.trim()
                    ?.let { it.equals("true", ignoreCase = true) || it == "1" }
                    ?: false
                call.respondApi { context.service.platformLogins(force = force) }
            }
            get("/target-platform-accounts") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.targetPlatformAccounts() }
            }
            get("/commands") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.commands() }
            }
            get("/publishers") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.publishers() }
            }
            post("/publishers") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.createPublisher(call.receive()) }
            }
            get("/publisher-platforms") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi { context.service.publisherPlatforms() }
            }
            get("/publisher-search") {
                if (!call.ensureAuthorized(context)) return@get
                call.respondApi {
                    context.service.searchPublishers(
                        platformId = call.request.queryParameters["platformId"],
                        query = call.request.queryParameters["q"],
                    )
                }
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
            post("/subscribers") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.createSubscriber(call.receive()) }
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
            post("/subscriptions/export") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.exportSubscriptions(call.receive()) }
            }
            post("/subscriptions/import") {
                if (!call.ensureAuthorized(context)) return@post
                call.respondApi { context.service.importSubscriptions(call.receive()) }
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
            delete("/filter-rules/{id}") {
                if (!call.ensureAuthorized(context)) return@delete
                val id = call.pathInt("id")
                call.respondApi { context.service.deleteFilterRule(id) }
            }
            post("/subscriptions/{id}/filter-rules") {
                if (!call.ensureAuthorized(context)) return@post
                val id = call.pathInt("id")
                call.respondApi { context.service.createFilterRule(id, call.receive()) }
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
            get("/configs/{id}/secrets/{path}") {
                if (!call.ensureAuthorized(context)) return@get
                val id = call.pathString("id")
                val path = call.pathString("path")
                call.respondApi { context.service.configSecret(id, path) }
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
            get("/platforms/{platform}/login/cookie/export") {
                if (!call.ensureAuthorized(context)) return@get
                val platform = call.pathString("platform")
                call.respondApi { context.loginService.exportCookie(platform) }
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
        return MessageDigest.isEqual(sha256(token), sha256(expectedToken))
    }

    private fun sha256(value: String): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
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
    } catch (e: Exception) {
        logger.error(e) { "Web 后台 API 处理失败：${request.path()}" }
        respond(HttpStatusCode.InternalServerError, ErrorResponse("后台接口处理失败"))
    }
}

private suspend fun ApplicationCall.respondMedia(block: suspend () -> AdminMediaResult) {
    try {
        val result = block()
        response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
        respondBytes(
            bytes = result.bytes,
            contentType = result.contentType,
        )
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "bad request"))
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "资源不存在"))
    } catch (e: IllegalStateException) {
        respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "图片加载失败"))
    } catch (e: Exception) {
        respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "图片加载失败"))
    }
}

private suspend fun ApplicationCall.respondOutboundMedia(block: suspend () -> OutboundMediaResult) {
    try {
        val result = block()
        response.headers.append(HttpHeaders.CacheControl, "public, max-age=${result.cacheMaxAgeSeconds}")
        val file = result.file
        if (file != null) {
            response.headers.append(HttpHeaders.ContentType, result.contentType.toString())
            respondFile(file)
            return
        }
        respondBytes(
            bytes = result.bytes ?: ByteArray(0),
            contentType = result.contentType,
        )
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "媒体链接无效"))
    } catch (e: NoSuchElementException) {
        respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "媒体资源不存在"))
    } catch (e: Exception) {
        respond(HttpStatusCode.BadGateway, ErrorResponse(e.message ?: "媒体资源读取失败"))
    }
}

private suspend fun ApplicationCall.respondPlatformLogo(
    registry: PlatformDrawAssetRegistry,
    platformId: String,
) {
    respondMedia {
        val asset = registry.asset(PlatformId.of(platformId), PlatformDrawAssetKeys.PRIMARY_LOGO)
            ?: throw NoSuchElementException("平台 Logo 不存在：$platformId")
        AdminMediaResult(
            bytes = asset.bytes,
            contentType = platformAssetContentType(asset),
        )
    }
}

private fun platformAssetContentType(asset: PlatformDrawAssetResource): ContentType {
    return contentTypeFromMime(asset.mimeType)
        ?: contentTypeFromAssetPath(asset.resourcePath)
        ?: ContentType.Application.OctetStream
}

private fun contentTypeFromMime(value: String?): ContentType? {
    val mediaType = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.contains('/') }
        ?: return null
    val parts = mediaType.split('/', limit = 2)
    return ContentType(parts[0], parts[1])
}

private fun contentTypeFromAssetPath(path: String): ContentType? {
    return when (path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "gif" -> ContentType.Image.GIF
        "webp" -> ContentType("image", "webp")
        "svg" -> ContentType("image", "svg+xml")
        else -> null
    }
}

private suspend fun ApplicationCall.respondAdminStaticResource(routePrefix: String) {
    val requestPath = request.path()
    if (!requestPath.startsWith(routePrefix)) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("后台资源路径无效"))
        return
    }
    val resourcePath = requestPath.removePrefix("/admin/")
    try {
        val resource = AdminStatic.resource(resourcePath)
        respondBytes(
            bytes = resource.bytes,
            contentType = resource.contentType,
        )
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "后台资源路径无效"))
    } catch (e: IllegalStateException) {
        respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "后台资源不存在"))
    }
}

private fun String?.toMediaKind(): MediaKind {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return MediaKind.OTHER
    return enumValues<MediaKind>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: MediaKind.OTHER
}

private fun ApplicationCall.pathInt(name: String): Int {
    return pathString(name).toIntOrNull() ?: throw IllegalArgumentException("路径参数无效：$name")
}

private fun ApplicationCall.pathString(name: String): String {
    return parameters[name] ?: throw IllegalArgumentException("缺少路径参数：$name")
}

private fun ApplicationCall.requiredQueryString(name: String): String {
    return request.queryParameters[name]
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("缺少查询参数：$name")
}

private fun ApplicationCall.optionalQueryInt(name: String): Int? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toIntOrNull() ?: throw IllegalArgumentException("查询参数无效：$name")
}

private fun ApplicationCall.optionalQueryLong(name: String): Long? {
    val raw = request.queryParameters[name] ?: return null
    return raw.toLongOrNull() ?: throw IllegalArgumentException("查询参数无效：$name")
}
