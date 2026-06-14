package top.colter.dynamic.plugin

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import top.colter.dynamic.core.plugin.PluginAdminPageDescriptor

public data class PluginAdminPageInfo(
    val pluginId: String,
    val pluginName: String,
    val pluginVersion: String,
    val loadTime: Long,
    val pluginState: PluginState,
    val page: PluginAdminPageDescriptor,
) {
    public val pageKey: String = "$pluginId:${page.id}"

    private val cacheToken: String = URLEncoder.encode("$pluginVersion-$loadTime", StandardCharsets.UTF_8.name())

    public val htmlPath: String =
        "/admin/plugins/$pluginId/pages/${page.id}/${page.entryHtml.trim('/')}?v=$cacheToken"

    public val scriptPath: String =
        "/admin/plugins/$pluginId/pages/${page.id}/${page.entryScript.trim('/')}?v=$cacheToken"
}

public data class PluginAdminResource(
    val bytes: ByteArray,
    val resourcePath: String,
)
