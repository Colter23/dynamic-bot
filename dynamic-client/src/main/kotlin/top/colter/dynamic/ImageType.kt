package top.colter.dynamic

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

public enum class ImageType {
    USER,
    EMOJI,
    COVER,
    IMAGES,
    OTHER,
    UNKNOWN
}

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
public annotation class ImgType (
    val type: ImageType = ImageType.UNKNOWN
)