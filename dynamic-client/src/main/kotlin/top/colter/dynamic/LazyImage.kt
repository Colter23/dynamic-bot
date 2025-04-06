package top.colter.dynamic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure

@Serializable(LazyImageSerializer::class)
public data class LazyImage(val url: String) {
    var image: ByteArray? = null
}

public object LazyImageSerializer: KSerializer<LazyImage> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(LazyImage::class.qualifiedName!!, PrimitiveKind.STRING)
    private const val prefix = "DYNAMIC_TYPE_"
    override fun deserialize(decoder: Decoder): LazyImage {
        return LazyImage(decoder.decodeString())

    }
    override fun serialize(encoder: Encoder, value: LazyImage) {
        encoder.encodeString(value.url)
    }
}


/**
 * 查找对象中所有 LazyImage 类型的属性
 */
public fun forEachLazyImageFields(obj: Any, block: LazyImage.(ImageType) -> Unit) {
    obj::class.declaredMemberProperties.forEach { property ->
        val type = property.returnType.jvmErasure
        if (type == LazyImage::class) {
            property.apply { isAccessible = true }.getter.call(obj)?.let {
                (it as LazyImage).block(property.findAnnotation<ImgType>()?.type?:ImageType.UNKNOWN)
            }
        } else if (type == List::class) {
            property.apply { isAccessible = true }.getter.call(obj)?.let { l ->
                val list = l as List<*>
                if (list.isNotEmpty()) {
                    if (list.first()!!::class == LazyImage::class) {
                        list.forEach {
                            (it as LazyImage).block(property.findAnnotation<ImgType>()?.type?:ImageType.UNKNOWN)
                        }
                    }else if (list.first()!!::class.isData) {
                        list.forEach { forEachLazyImageFields(it!!, block) }
                    }
                }
            }
        } else if (type.isData) {
            property.apply { isAccessible = true }.getter.call(obj)?.let {
                forEachLazyImageFields(it, block)
            }
        }
    }
}