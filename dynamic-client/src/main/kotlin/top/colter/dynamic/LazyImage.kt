package top.colter.dynamic

public data class LazyImage(val url: String) {
    var image: ByteArray? = null
}