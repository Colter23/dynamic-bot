package top.colter.dynamic.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.CardAttachment
import top.colter.dynamic.core.data.DynamicAttachmentKind
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.FilterAction
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.ImageAttachment
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PollAttachment
import top.colter.dynamic.core.data.PollOption
import top.colter.dynamic.core.data.SourceUpdateReference
import top.colter.dynamic.core.data.VideoAttachment
import top.colter.dynamic.testDynamicUpdate
import top.colter.dynamic.testMedia

class DynamicFilterEvaluatorTest {
    @Test
    fun shouldMatchAttachmentReferenceAndTextConditions() {
        val update = demoUpdate()
        val mediaOnlyUpdate = update.copy(
            payload = (update.payload as DynamicPayload).copy(
                title = null,
                labels = emptyList(),
                content = null,
                references = emptyList(),
            ),
        )

        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("hello world")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("origin secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextRegex("Video\\s+Secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("card secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("poll option")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasAttachmentKind(DynamicAttachmentKind.VIDEO)))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasAttachmentKind(DynamicAttachmentKind.POLL)))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasReference(DynamicReferenceKind.ORIGIN)))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("Publisher Name")))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("dynamic-link")))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextRegex("[")))
        assertFalse(DynamicFilterEvaluator.matches(mediaOnlyUpdate, FilterCondition.TextContains("hello world")))
        assertTrue(DynamicFilterEvaluator.matches(mediaOnlyUpdate, FilterCondition.HasAttachmentKind(DynamicAttachmentKind.VIDEO)))
    }

    @Test
    fun shouldComposeBooleanConditions() {
        val update = demoUpdate()

        assertTrue(
            DynamicFilterEvaluator.matches(
                update,
                FilterCondition.AllOf(
                    listOf(
                        FilterCondition.TextContains("hello"),
                        FilterCondition.HasAttachmentKind(DynamicAttachmentKind.CARD),
                    ),
                ),
            ),
        )
        assertTrue(
            DynamicFilterEvaluator.matches(
                update,
                FilterCondition.AnyOf(
                    listOf(
                        FilterCondition.TextContains("missing"),
                        FilterCondition.HasAttachmentKind(DynamicAttachmentKind.IMAGE),
                    ),
                ),
            ),
        )
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.Not(FilterCondition.TextContains("missing"))))
    }

    @Test
    fun isBlockedShouldLetLaterMatchingRuleOverrideEarlierOnes() {
        val update = demoUpdate()
        val disabledMatch = blockRule(FilterCondition.TextContains("hello world")).copy(enabled = false)
        val enabledMiss = blockRule(FilterCondition.TextContains("missing"))
        val enabledBlock = blockRule(FilterCondition.HasAttachmentKind(DynamicAttachmentKind.VIDEO), priority = 10)
        val laterAllow = allowRule(FilterCondition.TextContains("Video Secret"), priority = 20)

        assertTrue(DynamicFilterEvaluator.isBlocked(update, listOf(disabledMatch, enabledMiss, enabledBlock)))
        assertFalse(DynamicFilterEvaluator.isBlocked(update, listOf(enabledBlock, laterAllow)))
        assertFalse(DynamicFilterEvaluator.isBlocked(update, listOf(disabledMatch, enabledMiss)))
    }

    private fun blockRule(condition: FilterCondition, priority: Int = 0): DynamicFilterRule {
        return DynamicFilterRule(
            id = condition.hashCode(),
            subscriptionId = 1,
            action = FilterAction.BLOCK,
            condition = condition,
            priority = priority,
            enabled = true,
            createdAtEpochSeconds = 1L,
        )
    }

    private fun allowRule(condition: FilterCondition, priority: Int = 0): DynamicFilterRule {
        return blockRule(condition, priority).copy(action = FilterAction.ALLOW)
    }

    private fun demoUpdate() = testDynamicUpdate(
        payload = DynamicPayload(
            title = "Current Title",
            labels = listOf(DynamicLabel("Current Notice")),
            content = DynamicContent(listOf(DynamicContentNodeText("Hello World"))),
            attachments = listOf(
                VideoAttachment(
                    id = "BV1",
                    title = "Video Secret",
                    description = "Video Description",
                    cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                    durationSeconds = 60,
                    badge = "video",
                    link = "https://example.com/video",
                ),
                CardAttachment(
                    id = "card-1",
                    cardKind = "card",
                    title = "Card Title",
                    description = "Card Secret",
                    badge = "Card Badge",
                    cover = testMedia("https://example.com/card.png", MediaKind.COVER),
                    info = "Card Info",
                    link = "https://example.com/card",
                ),
                PollAttachment(
                    title = "Poll Secret",
                    options = listOf(PollOption(text = "poll option")),
                ),
            ),
            references = listOf(
                SourceUpdateReference(
                    kind = DynamicReferenceKind.ORIGIN,
                    key = testDynamicUpdate(externalId = "origin-1").key,
                    embedded = testDynamicUpdate(
                        externalId = "origin-1",
                        payload = DynamicPayload(
                            content = DynamicContent.text("Origin Secret"),
                            attachments = listOf(
                                ImageAttachment(
                                    images = listOf(
                                        ImageItem(testMedia("https://example.com/pic.png", MediaKind.IMAGE)),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}
