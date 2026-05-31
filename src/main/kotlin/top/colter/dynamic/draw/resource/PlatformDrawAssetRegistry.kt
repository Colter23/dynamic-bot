package top.colter.dynamic.draw.resource

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.skia.Image
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.plugin.PlatformDrawAssetDescriptor
import top.colter.dynamic.core.tools.loggerFor

private val platformDrawAssetLogger = loggerFor<PlatformDrawAssetRegistry>()

public interface PlatformDrawAssetResolver {
    public fun image(
        platformId: PlatformId,
        key: String,
        width: Int? = null,
        height: Int? = null,
    ): Image?
}

public object EmptyPlatformDrawAssetResolver : PlatformDrawAssetResolver {
    override fun image(platformId: PlatformId, key: String, width: Int?, height: Int?): Image? = null
}

public class PlatformDrawAssetRegistry : PlatformDrawAssetResolver {
    private val assets: ConcurrentHashMap<AssetKey, RegisteredAsset> = ConcurrentHashMap()
    private val imageCache: ConcurrentHashMap<ImageCacheKey, Image> = ConcurrentHashMap()

    public fun registerPluginAssets(
        pluginId: String,
        descriptors: List<PlatformDrawAssetDescriptor>,
        classLoader: ClassLoader,
    ) {
        if (descriptors.isEmpty()) return

        val normalized = descriptors.map { it.normalized() }
        val duplicatedKeys = normalized
            .groupingBy { AssetKey(it.platformId, it.key) }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicatedKeys.isEmpty()) {
            "插件绘图资源重复：pluginId=$pluginId，resources=${duplicatedKeys.joinToString { it.display() }}"
        }

        val registeredKeys = mutableListOf<AssetKey>()
        runCatching {
            normalized.forEach { descriptor ->
                val assetKey = AssetKey(descriptor.platformId, descriptor.key)
                require(classLoader.getResource(descriptor.resourcePath) != null) {
                    "插件绘图资源不存在：pluginId=$pluginId，resourcePath=${descriptor.resourcePath}"
                }
                val existing = assets.putIfAbsent(
                    assetKey,
                    RegisteredAsset(pluginId, descriptor, classLoader),
                )
                require(existing == null) {
                    "平台绘图资源重复：${assetKey.display()}，已注册插件=${existing?.pluginId}，当前插件=$pluginId"
                }
                registeredKeys += assetKey
            }
        }.onFailure {
            registeredKeys.forEach { assets.remove(it) }
            throw it
        }
    }

    public fun unregisterPluginAssets(pluginId: String) {
        val removedKeys = assets
            .filterValues { it.pluginId == pluginId }
            .keys
        removedKeys.forEach { assets.remove(it) }
        imageCache.keys
            .filter { it.pluginId == pluginId }
            .forEach { imageCache.remove(it) }
    }

    override fun image(platformId: PlatformId, key: String, width: Int?, height: Int?): Image? {
        val normalizedKey = AssetKey(platformId.value, key.trim())
        val asset = assets[normalizedKey] ?: return null
        val cacheKey = ImageCacheKey(asset.pluginId, normalizedKey.platformId, normalizedKey.key, width, height)
        return imageCache[cacheKey] ?: runCatching {
            val image = asset.loadImage(width, height)
            imageCache[cacheKey] = image
            image
        }.onFailure {
            platformDrawAssetLogger.warn(it) {
                "平台绘图资源加载失败：pluginId=${asset.pluginId}，${normalizedKey.display()}，resourcePath=${asset.descriptor.resourcePath}"
            }
        }.getOrNull()
    }

    private fun RegisteredAsset.loadImage(width: Int?, height: Int?): Image {
        val bytes = classLoader.getResourceAsStream(descriptor.resourcePath)?.use { it.readBytes() }
            ?: error("插件绘图资源不存在：pluginId=$pluginId，resourcePath=${descriptor.resourcePath}")
        return if (descriptor.isSvg()) {
            loadSVG(bytes).makeImage(width ?: DEFAULT_SVG_SIZE, height ?: DEFAULT_SVG_SIZE)
        } else {
            Image.makeFromEncoded(bytes)
        }
    }

    private fun PlatformDrawAssetDescriptor.normalized(): PlatformDrawAssetDescriptor {
        return copy(
            platformId = PlatformId.of(platformId).value,
            key = key.trim(),
            resourcePath = resourcePath.trim().trimStart('/'),
            mimeType = mimeType?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        )
    }

    private fun PlatformDrawAssetDescriptor.isSvg(): Boolean {
        val mime = mimeType?.lowercase()
        return mime == "image/svg+xml" || resourcePath.substringAfterLast('.', "").equals("svg", ignoreCase = true)
    }

    private data class AssetKey(
        val platformId: String,
        val key: String,
    ) {
        fun display(): String = "platformId=$platformId，key=$key"
    }

    private data class RegisteredAsset(
        val pluginId: String,
        val descriptor: PlatformDrawAssetDescriptor,
        val classLoader: ClassLoader,
    )

    private data class ImageCacheKey(
        val pluginId: String,
        val platformId: String,
        val key: String,
        val width: Int?,
        val height: Int?,
    )

    private companion object {
        private const val DEFAULT_SVG_SIZE: Int = 100
    }
}
