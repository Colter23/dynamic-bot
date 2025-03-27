package top.colter.dynamic

import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.jvmErasure

public data class LazyImage(val url: String) {
    var image: ByteArray? = null
}

/**
 * 查找对象中所有 LazyImage 类型的属性
 */
public fun forEachLazyImageFields(obj: Any, block: LazyImage.(ImageType) -> Unit) {
    obj::class.declaredMemberProperties.forEach { property ->
        val type = property.returnType.jvmErasure
        if (type == LazyImage::class) {
            (property.apply { isAccessible = true }.getter.call(obj) as LazyImage)
                .block(property.findAnnotation<ImgType>()?.type?:ImageType.UNKNOWN)
        } else if (type == List::class) {
            val list = property.apply { isAccessible = true }.getter.call(obj) as List<*>
            if (list.isNotEmpty()) {
                if (list.first()!!::class == LazyImage::class) {
                    list.forEach {
                        (it as LazyImage).block(property.findAnnotation<ImgType>()?.type?:ImageType.UNKNOWN)
                    }
                }else if (list.first()!!::class.isData) {
                    list.forEach { forEachLazyImageFields(it!!, block) }
                }
            }
        } else if (type.isData) {
            property.apply { isAccessible = true }.getter.call(obj)?.let {
                forEachLazyImageFields(it, block)
            }
        }
    }
}