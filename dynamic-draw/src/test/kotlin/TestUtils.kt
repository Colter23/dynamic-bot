import org.jetbrains.skia.Image
import java.io.File
import java.io.FileFilter


val resource = File("src/main/resources")
val testResource = File("src/test/resources")
val testOutput = testResource.resolve("output").apply {
    if(!exists()) this.mkdirs()
}

// 加载测试资源
fun loadTestResource(path: String = "", fileName: String) =
    testResource.resolve(path).resolve(fileName)
// 加载测试图片
fun loadTestImage(path: String = "", fileName: String) =
    Image.makeFromEncoded(loadTestResource(path, fileName).readBytes())
// 加载测试文本
fun loadTestText(path: String = "", fileName: String) =
    loadTestResource(path, fileName).readText()
// 加载目录下的所有图片
fun loadAllTestImage(path: String): Map<String, Image> {
    val imageMap = mutableMapOf<String, Image>()
    val dir = testResource.resolve(path)
    val imgExt = listOf("png", "jpg", "jpeg", "webp")
    if (dir.exists() && dir.isDirectory) {
        dir.listFiles(FileFilter {
            it.extension.lowercase() in imgExt
        })?.forEach {
            imageMap[it.nameWithoutExtension] = Image.makeFromEncoded(it.readBytes())
        }
    }
    return imageMap
}




