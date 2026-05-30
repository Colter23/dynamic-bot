package top.colter.dynamic

import kotlin.io.path.createTempDirectory
import top.colter.dynamic.repository.PersistenceManager

fun initTestDatabase(prefix: String) {
    val tempDir = createTempDirectory(prefix).toFile()
    PersistenceManager.init(tempDir.resolve("test.db").path)
}
