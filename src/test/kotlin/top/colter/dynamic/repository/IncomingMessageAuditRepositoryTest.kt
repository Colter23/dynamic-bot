package top.colter.dynamic.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.IncomingMessageRecordPolicy
import top.colter.dynamic.core.data.IncomingMessageRecordPolicyType
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.data.IncomingProcessingResult
import top.colter.dynamic.core.data.IncomingProcessingStage
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.initTestDatabase

class IncomingMessageAuditRepositoryTest {
    @Test
    fun nonePolicyShouldNotCreateRecord() {
        initTestDatabase("incoming-audit-none")

        val inserted = IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-none", recordPolicy = IncomingMessageRecordPolicy.None),
        )

        assertEquals(false, inserted)
        assertEquals(0, IncomingMessageAuditRepository.countMessages())
    }

    @Test
    fun tracePolicyShouldStoreLightweightPreviewAndPayloadDigest() {
        initTestDatabase("incoming-audit-trace")

        val inserted = IncomingMessageAuditRepository.recordMessage(
            auditRequest(
                traceId = "trace-1",
                recordPolicy = IncomingMessageRecordPolicy.Trace(retentionSeconds = 60),
                receivedAtEpochSeconds = 100,
            ),
        )

        assertEquals(true, inserted)
        val record = IncomingMessageAuditRepository.findByTraceId("trace-1")?.message
        assertNotNull(record)
        assertEquals(IncomingMessageRecordPolicyType.TRACE, record.recordPolicyType)
        assertEquals(60, record.retentionSeconds)
        assertEquals(160, record.expiresAtEpochSeconds)
        assertEquals("hello", record.textPreview)
        assertEquals("文本 1", record.segmentSummary)
        assertEquals("json", record.rawFormat)
        assertEquals(rawPayload().toByteArray(Charsets.UTF_8).size, record.rawPayloadSize)
        assertNotNull(record.rawPayloadSha256)
    }

    @Test
    fun auditPolicyShouldNotExpireAndProcessingShouldBeRecorded() {
        initTestDatabase("incoming-audit-processing")

        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-audit", recordPolicy = IncomingMessageRecordPolicy.Audit),
        )
        IncomingMessageAuditRepository.recordProcessing(
            IncomingProcessingWriteRequest(
                traceId = "trace-audit",
                stage = IncomingProcessingStage.COMMAND_EXECUTE,
                handlerId = "status",
                result = IncomingProcessingResult.SUCCEEDED,
                commandPath = "status",
                role = "USER",
                durationMs = 12,
            ),
        )

        val detail = IncomingMessageAuditRepository.findByTraceId("trace-audit")
        assertNotNull(detail)
        assertEquals(IncomingMessageRecordPolicyType.AUDIT, detail.message.recordPolicyType)
        assertNull(detail.message.expiresAtEpochSeconds)
        assertEquals(1, detail.processing.size)
        assertEquals(IncomingProcessingResult.SUCCEEDED, detail.processing.single().result)
    }

    @Test
    fun cleanupExpiredTraceShouldPreserveAuditRecords() {
        initTestDatabase("incoming-audit-cleanup")

        IncomingMessageAuditRepository.recordMessage(
            auditRequest(
                traceId = "trace-expired",
                recordPolicy = IncomingMessageRecordPolicy.Trace(retentionSeconds = 10),
                receivedAtEpochSeconds = 100,
            ),
        )
        IncomingMessageAuditRepository.recordProcessing(
            IncomingProcessingWriteRequest(
                traceId = "trace-expired",
                stage = IncomingProcessingStage.LINK_PARSE,
                handlerId = "link",
                result = IncomingProcessingResult.FAILED,
            ),
        )
        IncomingMessageAuditRepository.recordMessage(
            auditRequest(
                traceId = "trace-audit",
                recordPolicy = IncomingMessageRecordPolicy.Audit,
                receivedAtEpochSeconds = 100,
            ),
        )

        val result = IncomingMessageAuditRepository.cleanupExpiredTrace(nowEpochSeconds = 120)

        assertEquals(1, result.deletedMessages)
        assertEquals(1, result.deletedProcessingRecords)
        assertNull(IncomingMessageAuditRepository.findByTraceId("trace-expired"))
        assertNotNull(IncomingMessageAuditRepository.findByTraceId("trace-audit"))
    }

    @Test
    fun defaultListShouldHideTraceUnlessFailedOrExplicitlyIncluded() {
        initTestDatabase("incoming-audit-default-visible")

        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-ok", recordPolicy = IncomingMessageRecordPolicy.Trace()),
        )
        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-failed", recordPolicy = IncomingMessageRecordPolicy.Trace()),
        )
        IncomingMessageAuditRepository.recordProcessing(
            IncomingProcessingWriteRequest(
                traceId = "trace-failed",
                stage = IncomingProcessingStage.LINK_PARSE,
                handlerId = "link",
                result = IncomingProcessingResult.FAILED,
            ),
        )
        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-audit", recordPolicy = IncomingMessageRecordPolicy.Audit),
        )

        val defaultIds = IncomingMessageAuditRepository.findRecent().map { it.message.traceId }.toSet()
        assertEquals(setOf("trace-failed", "trace-audit"), defaultIds)
        val allIds = IncomingMessageAuditRepository.findRecent(includeTrace = true).map { it.message.traceId }.toSet()
        assertEquals(setOf("trace-ok", "trace-failed", "trace-audit"), allIds)
    }

    @Test
    fun explicitTraceQueryShouldFindSuccessfulTraceWithoutIncludeTrace() {
        initTestDatabase("incoming-audit-explicit-trace")

        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-ok", recordPolicy = IncomingMessageRecordPolicy.Trace()),
        )

        val rows = IncomingMessageAuditRepository.findRecent(traceId = "trace-ok")

        assertEquals(listOf("trace-ok"), rows.map { it.message.traceId })
    }

    @Test
    fun processingFilterShouldFindTraceRecordsHiddenFromDefaultView() {
        initTestDatabase("incoming-audit-processing-filter")

        IncomingMessageAuditRepository.recordMessage(
            auditRequest(traceId = "trace-ok", recordPolicy = IncomingMessageRecordPolicy.Trace()),
        )
        IncomingMessageAuditRepository.recordProcessing(
            IncomingProcessingWriteRequest(
                traceId = "trace-ok",
                stage = IncomingProcessingStage.LINK_PARSE,
                handlerId = "link",
                result = IncomingProcessingResult.SUCCEEDED,
            ),
        )

        val rows = IncomingMessageAuditRepository.findRecent(result = IncomingProcessingResult.SUCCEEDED)

        assertEquals(listOf("trace-ok"), rows.map { it.message.traceId })
    }

    private fun auditRequest(
        traceId: String,
        recordPolicy: IncomingMessageRecordPolicy,
        receivedAtEpochSeconds: Long = 1_000,
    ): IncomingAuditWriteRequest {
        return IncomingAuditWriteRequest(
            sourcePlugin = "onebot",
            message = incomingMessage(),
            traceId = traceId,
            replyToMessageId = "reply-1",
            intent = IncomingMessageIntent.PlainText,
            recordPolicy = recordPolicy,
            receivedAtEpochSeconds = receivedAtEpochSeconds,
            dedupeKey = "dedupe-$traceId",
            sourceEventId = "event-$traceId",
        )
    }

    private fun incomingMessage(): IncomingMessage {
        return IncomingMessage(
            platformId = PlatformId.of("onebot"),
            target = TargetAddress.of("onebot", TargetKind.GROUP, "10001"),
            senderId = "sender",
            botAccountId = "bot-1",
            messageId = "message-1",
            timestamp = 900,
            text = "hello",
            segments = listOf(IncomingMessageSegment.Text("hello")),
            rawFormat = "json",
            rawPayload = rawPayload(),
        )
    }

    private fun rawPayload(): String = """{"message":"hello"}"""
}
