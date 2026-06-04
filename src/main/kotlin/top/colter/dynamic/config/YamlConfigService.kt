package top.colter.dynamic.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import top.colter.dynamic.core.config.ConfigMigration
import kotlin.reflect.KClass
import top.colter.dynamic.core.config.ConfigException
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.config.MutableConfigDocument
import top.colter.dynamic.core.config.PluginDataStore
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<YamlConfigService>()

public class YamlConfigService(
    baseDir: Path = Paths.get("config"),
) : ConfigService {
    private val store: YamlDocumentStore = YamlDocumentStore(baseDir, "配置")

    override fun <T : Any> loadOrCreate(
        pluginId: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T {
        return store.loadOrCreate(pluginId, clazz, migrations, defaultProvider)
    }

    override fun <T : Any> save(pluginId: String, config: T) {
        store.save(pluginId, config)
    }

    override fun <T : Any> reload(pluginId: String, clazz: KClass<T>, migrations: List<ConfigMigration>): T {
        return store.reload(pluginId, clazz, migrations)
    }

    override fun exists(pluginId: String): Boolean {
        return store.exists(pluginId)
    }

    override fun delete(pluginId: String): Boolean {
        return store.delete(pluginId)
    }

    override fun resolvePath(pluginId: String): Path {
        return store.resolvePath(pluginId)
    }
}

public class YamlPluginDataStore(
    pluginId: String,
    baseDir: Path = Paths.get("data", "plugins"),
) : PluginDataStore {
    override val dataDir: Path = baseDir.resolve(validateDocumentId(pluginId))
    private val store: YamlDocumentStore = YamlDocumentStore(dataDir, "插件数据")

    init {
        Files.createDirectories(dataDir)
    }

    override fun <T : Any> loadOrCreate(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
        defaultProvider: () -> T,
    ): T {
        return store.loadOrCreate(name, clazz, migrations, defaultProvider)
    }

    override fun <T : Any> save(name: String, value: T) {
        store.save(name, value)
    }

    override fun <T : Any> reload(name: String, clazz: KClass<T>, migrations: List<ConfigMigration>): T {
        return store.reload(name, clazz, migrations)
    }

    override fun exists(name: String): Boolean {
        return store.exists(name)
    }

    override fun delete(name: String): Boolean {
        return store.delete(name)
    }

    override fun resolvePath(name: String): Path {
        return store.resolvePath(name)
    }
}

internal class YamlDocumentStore(
    private val baseDir: Path,
    private val label: String,
) {
    private val objectMapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)

    private val lockMap: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    fun <T : Any> loadOrCreate(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration> = emptyList(),
        defaultProvider: () -> T,
    ): T {
        val path = resolvePath(name)
        val lock = lockMap.computeIfAbsent(name) { Any() }

        synchronized(lock) {
            return try {
                if (!Files.exists(path)) {
                    val defaultValue = defaultProvider()
                    writeAtomically(path, defaultValue)
                    logger.info { "$label 文件已创建：name=$name，path=${path.toAbsolutePath()}" }
                    defaultValue
                } else {
                    readExisting(name, path, clazz, migrations)
                }
            } catch (e: Exception) {
                throw ConfigException("加载或创建 $label 失败：name=$name", path, e)
            }
        }
    }

    fun <T : Any> save(name: String, value: T) {
        val path = resolvePath(name)
        val lock = lockMap.computeIfAbsent(name) { Any() }

        synchronized(lock) {
            try {
                writeAtomically(path, value)
            } catch (e: Exception) {
                throw ConfigException("保存 $label 失败：name=$name", path, e)
            }
        }
    }

    fun <T : Any> reload(
        name: String,
        clazz: KClass<T>,
        migrations: List<ConfigMigration> = emptyList(),
    ): T {
        val path = resolvePath(name)
        val lock = lockMap.computeIfAbsent(name) { Any() }

        synchronized(lock) {
            try {
                require(Files.exists(path)) { "$label 文件不存在：name=$name" }
                return readExisting(name, path, clazz, migrations)
            } catch (e: Exception) {
                throw ConfigException("重新加载 $label 失败：name=$name", path, e)
            }
        }
    }

    fun exists(name: String): Boolean {
        return Files.exists(resolvePath(name))
    }

    fun delete(name: String): Boolean {
        val path = resolvePath(name)
        val lock = lockMap.computeIfAbsent(name) { Any() }
        synchronized(lock) {
            return Files.deleteIfExists(path)
        }
    }

    fun resolvePath(name: String): Path {
        return baseDir.resolve("${validateDocumentId(name)}.yml")
    }

    private fun <T : Any> readExisting(
        name: String,
        path: Path,
        clazz: KClass<T>,
        migrations: List<ConfigMigration>,
    ): T {
        val root = readRootObject(path)
        val document = JacksonMutableConfigDocument(objectMapper, root)
        val appliedMigrations = mutableListOf<String>()
        migrations.forEach { migration ->
            val before = document.changeCount
            migration.apply(document)
            if (document.changeCount > before) {
                appliedMigrations += migration.id
            }
        }

        val value = objectMapper.treeToValue(document.root, clazz.java)
        val normalized = objectMapper.valueToTree<JsonNode>(value)
        val defaultsFilled = normalized != document.root
        if (document.changed || defaultsFilled) {
            writeAtomically(path, value)
            val reasons = buildList {
                if (appliedMigrations.isNotEmpty()) {
                    add("迁移=${appliedMigrations.joinToString("|")}")
                }
                if (defaultsFilled) {
                    add("默认字段补齐")
                }
            }.ifEmpty { listOf("规范化") }
            logger.info {
                "$label 文件已更新：name=$name，path=${path.toAbsolutePath()}，原因=${reasons.joinToString("，")}"
            }
        }
        return value
    }

    private fun readRootObject(path: Path): ObjectNode {
        val root = path.inputStream().use { input ->
            objectMapper.readTree(input)
        }
        require(root is ObjectNode) { "$label 文件根节点必须是对象" }
        return root
    }

    private fun <T : Any> writeAtomically(path: Path, value: T) {
        Files.createDirectories(path.parent)
        val tempPath = Files.createTempFile(path.parent, "${path.fileName}.", ".tmp")
        try {
            tempPath.outputStream().use { output ->
                objectMapper.writeValue(output, value)
            }
            runCatching {
                Files.move(
                    tempPath,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }
}

private class JacksonMutableConfigDocument(
    private val objectMapper: ObjectMapper,
    val root: ObjectNode,
) : MutableConfigDocument {
    var changeCount: Int = 0
        private set

    val changed: Boolean
        get() = changeCount > 0

    override fun contains(path: String): Boolean {
        return findNode(path) != null
    }

    override fun get(path: String): Any? {
        val node = findNode(path) ?: return null
        return objectMapper.convertValue(node, Any::class.java)
    }

    override fun set(path: String, value: Any?) {
        setNode(path, objectMapper.valueToTree(value))
    }

    override fun remove(path: String): Boolean {
        val segments = pathSegments(path)
        val parent = findParent(segments) ?: return false
        val removed = parent.remove(segments.last()) ?: return false
        changeCount += 1
        return !removed.isMissingNode
    }

    override fun move(from: String, to: String, overwrite: Boolean): Boolean {
        if (from == to) return false
        val source = findNode(from) ?: return false
        val before = changeCount
        if (overwrite || !contains(to)) {
            setNode(to, source.deepCopy())
        }
        remove(from)
        return changeCount > before
    }

    private fun setNode(path: String, value: JsonNode) {
        val existing = findNode(path)
        if (existing == value) return
        val segments = pathSegments(path)
        val parent = ensureParent(segments)
        parent.set<JsonNode>(segments.last(), value)
        changeCount += 1
    }

    private fun findNode(path: String): JsonNode? {
        var current: JsonNode = root
        pathSegments(path).forEach { segment ->
            current = current.get(segment) ?: return null
        }
        return current.takeUnless { it.isMissingNode }
    }

    private fun findParent(segments: List<String>): ObjectNode? {
        var current: JsonNode = root
        segments.dropLast(1).forEach { segment ->
            current = current.get(segment) ?: return null
            if (current !is ObjectNode) return null
        }
        return current as ObjectNode
    }

    private fun ensureParent(segments: List<String>): ObjectNode {
        var current = root
        segments.dropLast(1).forEach { segment ->
            val child = current.get(segment)
            current = if (child is ObjectNode) {
                child
            } else {
                objectMapper.createObjectNode().also {
                    current.set<ObjectNode>(segment, it)
                    changeCount += 1
                }
            }
        }
        return current
    }

    private fun pathSegments(path: String): List<String> {
        val segments = path.split(".")
        require(segments.isNotEmpty() && segments.none { it.isBlank() }) { "配置路径不能为空：$path" }
        return segments
    }
}

private fun validateDocumentId(id: String): String {
    require(id.isNotBlank()) { "名称不能为空" }
    require(DOCUMENT_ID_REGEX.matches(id)) { "名称包含非法字符：$id" }
    require(id != "." && id != "..") { "名称不能是路径保留段：$id" }
    return id
}

private val DOCUMENT_ID_REGEX: Regex = Regex("^[a-zA-Z0-9._-]+$")
