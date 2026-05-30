package top.colter.dynamic.admin

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.core.plugin.PublisherLoginProvider
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge

public class AdminLoginService(
    private val loginProviderResolver: (String) -> PublisherLoginProvider?,
    private val loginScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val qrCodeRenderer: AdminQrCodeRenderer = AdminQrCodeRenderer(),
) {
    private val sessions: ConcurrentHashMap<String, QrLoginSession> = ConcurrentHashMap()
    private val jobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    public suspend fun loginByCookie(platformId: String, cookie: String): LoginResultDto {
        val platform = platformId.trim().lowercase()
        val plugin = resolvePlugin(platform)
        require(plugin.supportedLoginMethods.contains(PublisherLoginMethod.COOKIE)) {
            "$platform 不支持 Cookie 登录"
        }
        require(cookie.isNotBlank()) { "Cookie 不能为空" }

        val result = runCatching { plugin.loginByCookie(cookie) }
            .getOrElse { error ->
                PublisherLoginResult(
                    status = PublisherLoginStatus.FAILED,
                    message = error.message ?: "Cookie 登录失败",
                )
            }
        return result.toDto()
    }

    public suspend fun startQrLogin(platformId: String): QrLoginStartResponse {
        val platform = platformId.trim().lowercase()
        val plugin = resolvePlugin(platform)
        require(plugin.supportedLoginMethods.contains(PublisherLoginMethod.QR_CODE)) {
            "$platform 不支持二维码登录"
        }

        val loginId = UUID.randomUUID().toString()
        val challenge = CompletableDeferred<PublisherQrLoginChallenge?>()
        sessions[loginId] = QrLoginSession(
            loginId = loginId,
            platform = platform,
            status = PublisherLoginStatus.PENDING.name,
            message = "二维码登录正在启动",
        )

        val job = loginScope.launch {
            val result = runCatching {
                plugin.loginByQrCode(
                    onQrCode = { qrChallenge ->
                        val imageBytes = qrCodeRenderer.renderPng(qrChallenge.qrContent)
                        sessions[loginId] = sessions[loginId]
                            ?.copy(
                                status = PublisherLoginStatus.PENDING.name,
                                message = qrChallenge.message ?: "等待扫码",
                                expiresAtEpochSeconds = qrChallenge.expiresAtEpochSeconds,
                                imageBytes = imageBytes,
                            )
                            ?: QrLoginSession(
                                loginId = loginId,
                                platform = platform,
                                status = PublisherLoginStatus.PENDING.name,
                                message = qrChallenge.message ?: "等待扫码",
                                expiresAtEpochSeconds = qrChallenge.expiresAtEpochSeconds,
                                imageBytes = imageBytes,
                            )
                        if (!challenge.isCompleted) {
                            challenge.complete(qrChallenge)
                        }
                    },
                    onStatusChanged = { status ->
                        updateSession(loginId, status)
                    },
                )
            }.getOrElse { error ->
                if (error is CancellationException) {
                    PublisherLoginResult(
                        status = PublisherLoginStatus.CANCELED,
                        message = "二维码登录已取消",
                    )
                } else {
                    PublisherLoginResult(
                        status = PublisherLoginStatus.FAILED,
                        message = error.message ?: "二维码登录失败",
                    )
                }
            }

            if (!challenge.isCompleted) {
                challenge.complete(null)
            }
            updateSession(loginId, result)
            jobs.remove(loginId)
        }
        jobs[loginId] = job
        job.invokeOnCompletion {
            jobs.remove(loginId, job)
        }

        val qrChallenge = withTimeoutOrNull(QR_CHALLENGE_TIMEOUT_MS) { challenge.await() }
            ?: throw IllegalStateException(sessions[loginId]?.message ?: "二维码登录启动失败")
        return QrLoginStartResponse(
            loginId = loginId,
            imageUrl = "/api/login/qr/$loginId/image",
            status = PublisherLoginStatus.PENDING.name,
            message = qrChallenge.message,
            expiresAtEpochSeconds = qrChallenge.expiresAtEpochSeconds,
        )
    }

    public fun qrLoginStatus(loginId: String): QrLoginStatusResponse {
        return findSession(loginId).toStatusDto()
    }

    public fun qrImageBytes(loginId: String): ByteArray {
        return findSession(loginId).imageBytes ?: throw NoSuchElementException("未找到二维码图片：$loginId")
    }

    public suspend fun cancelQrLogin(loginId: String): ActionResultResponse {
        val session = findSession(loginId)
        val job = jobs.remove(loginId)
        val changed = job != null && job.isActive
        if (changed) {
            job.cancelAndJoin()
        }
        sessions[loginId] = session.copy(
            status = PublisherLoginStatus.CANCELED.name,
            message = "二维码登录已取消",
        )
        return ActionResultResponse(changed = changed, message = if (changed) "二维码登录已取消" else "二维码登录已结束")
    }

    private fun updateSession(loginId: String, result: PublisherLoginResult) {
        val current = sessions[loginId] ?: return
        sessions[loginId] = current.copy(
            status = result.status.name,
            message = result.message,
            account = result.account,
        )
    }

    private fun findSession(loginId: String): QrLoginSession {
        return sessions[loginId] ?: throw NoSuchElementException("未找到二维码登录会话：$loginId")
    }

    private fun resolvePlugin(platformId: String): PublisherLoginProvider {
        require(platformId.isNotBlank()) { "平台不能为空" }
        return loginProviderResolver(platformId)
            ?: throw NoSuchElementException("未找到平台登录插件：$platformId")
    }

    private companion object {
        private const val QR_CHALLENGE_TIMEOUT_MS: Long = 10_000
    }
}

public class AdminQrCodeRenderer {
    public fun renderPng(content: String, size: Int = 512): ByteArray {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix[x, y]) Color.BLACK.rgb else Color.WHITE.rgb)
            }
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }
    }
}

private data class QrLoginSession(
    val loginId: String,
    val platform: String,
    val status: String,
    val message: String,
    val account: PublisherLoginAccount? = null,
    val expiresAtEpochSeconds: Long? = null,
    val imageBytes: ByteArray? = null,
) {
    fun toStatusDto(): QrLoginStatusResponse = QrLoginStatusResponse(
        loginId = loginId,
        platform = platform,
        status = status,
        message = message,
        account = account?.toDto(),
        expiresAtEpochSeconds = expiresAtEpochSeconds,
    )
}

private fun PublisherLoginResult.toDto(): LoginResultDto = LoginResultDto(
    status = status.name,
    message = message,
    account = account?.toDto(),
)

private fun PublisherLoginAccount.toDto(): LoginAccountDto = LoginAccountDto(
    userId = userId,
    name = name,
)
