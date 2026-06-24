package top.colter.dynamic

private val ADDRESS_ALREADY_IN_USE_MARKERS = listOf(
    "address already in use",
    "地址已在使用",
    "地址已经在使用",
    "端口已被占用",
    "端口被占用",
)

internal class DynamicStartupException(
    val userMessage: String,
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)

internal fun Throwable.isAddressAlreadyInUse(): Boolean {
    return causeChain().any { error ->
        error.message
            ?.lowercase()
            ?.let { message -> ADDRESS_ALREADY_IN_USE_MARKERS.any(message::contains) }
            ?: false
    }
}

internal fun webAdminPortOccupiedMessage(config: WebAdminConfig): String {
    val host = config.host.ifBlank { "0.0.0.0" }
    return "Web 后台端口已被占用：$host:${config.port}。请关闭占用该端口的程序，" +
        "或修改 config/main.yml 中的 webAdmin.port 后重新启动。"
}

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this@causeChain
    while (current != null && seen.add(current)) {
        yield(current)
        current = current.cause
    }
}
