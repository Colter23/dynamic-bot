package top.colter.dynamic

import org.jetbrains.skia.Image
import java.io.File
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.EntityState
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.Publisher
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.data.SourceEventType
import top.colter.dynamic.core.data.SourcePayload
import top.colter.dynamic.core.data.SourceUpdate
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.data.UpdateKey


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

fun testMedia(
    uri: String,
    kind: MediaKind = MediaKind.OTHER,
    alt: String? = null,
): MediaRef = MediaRef(uri = uri, kind = kind, alt = alt)

fun testPublisherKey(
    platformId: String = "bilibili",
    externalId: String = "123",
    kind: PublisherKind = PublisherKind.USER,
): PublisherKey = PublisherKey.of(platformId = platformId, kind = kind, externalId = externalId)

fun testPublisherInfo(
    key: PublisherKey = testPublisherKey(),
    name: String = "demo",
    avatar: MediaRef = testMedia("https://example.com/${key.externalId}.png", MediaKind.AVATAR),
    banner: MediaRef? = null,
    pendant: MediaRef? = null,
    state: EntityState = EntityState.ACTIVE,
): PublisherInfo = PublisherInfo(
    key = key,
    name = name,
    state = state,
    avatar = avatar,
    banner = banner,
    pendant = pendant,
)

fun testPublisher(
    id: Int = 1,
    key: PublisherKey = testPublisherKey(),
    name: String = "demo",
    avatar: MediaRef = testMedia("https://example.com/${key.externalId}.png", MediaKind.AVATAR),
    banner: MediaRef? = null,
    pendant: MediaRef? = null,
    state: EntityState = EntityState.ACTIVE,
): Publisher = Publisher(
    id = id,
    key = key,
    name = name,
    state = state,
    avatar = avatar,
    banner = banner,
    pendant = pendant,
    createTime = 1L,
    createUser = 1,
)

fun testTargetAddress(
    platformId: String = "onebot",
    kind: TargetKind = TargetKind.GROUP,
    externalId: String = "100",
    scopeId: String? = null,
    threadId: String? = null,
    accountId: String? = null,
): TargetAddress = TargetAddress(
    platformId = PlatformId.of(platformId),
    kind = kind,
    externalId = externalId,
    scopeId = scopeId,
    threadId = threadId,
    accountId = accountId,
)

fun testDynamicUpdate(
    publisher: PublisherInfo = testPublisherInfo(),
    eventType: SourceEventType = SourceEventType.DYNAMIC_CREATED,
    externalId: String = "dynamic-1",
    payload: SourcePayload = DynamicPayload(content = DynamicContent.text("hello")),
): SourceUpdate = SourceUpdate(
    key = UpdateKey.of(
        publisherKey = publisher.key,
        eventType = eventType,
        externalId = externalId,
    ),
    publisher = publisher,
    occurredAtEpochSeconds = 1L,
    link = "https://example.com/$externalId",
    payload = payload,
)
