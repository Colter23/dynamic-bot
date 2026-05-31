package top.colter.dynamic.filter

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.data.DynamicBlockKind
import top.colter.dynamic.core.data.DynamicContent
import top.colter.dynamic.core.data.DynamicContentNodeText
import top.colter.dynamic.core.data.DynamicFilterRule
import top.colter.dynamic.core.data.DynamicLabel
import top.colter.dynamic.core.data.DynamicMediaCard
import top.colter.dynamic.core.data.DynamicMediaCardKind
import top.colter.dynamic.core.data.DynamicPayload
import top.colter.dynamic.core.data.DynamicReferenceKind
import top.colter.dynamic.core.data.FilterCondition
import top.colter.dynamic.core.data.ImageGridBlock
import top.colter.dynamic.core.data.ImageItem
import top.colter.dynamic.core.data.MediaCardBlock
import top.colter.dynamic.core.data.MediaCardStyle
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.PollBlock
import top.colter.dynamic.core.data.PollOption
import top.colter.dynamic.core.data.RepostBlock
import top.colter.dynamic.core.data.TextBlock
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
                blocks = (update.payload as DynamicPayload).blocks.filterNot {
                    it is TextBlock || it is RepostBlock
                },
            ),
        )

        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("hello world")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("origin secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextRegex("Video\\s+Secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("card secret")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("poll option")))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasBlockKind(DynamicBlockKind.VIDEO)))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasBlockKind(DynamicBlockKind.POLL)))
        assertTrue(DynamicFilterEvaluator.matches(update, FilterCondition.HasReference(DynamicReferenceKind.ORIGIN)))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("Publisher Name")))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextContains("dynamic-link")))
        assertFalse(DynamicFilterEvaluator.matches(update, FilterCondition.TextRegex("[")))
        assertFalse(DynamicFilterEvaluator.matches(mediaOnlyUpdate, FilterCondition.TextContains("hello world")))
        assertTrue(DynamicFilterEvaluator.matches(mediaOnlyUpdate, FilterCondition.HasBlockKind(DynamicBlockKind.VIDEO)))
    }

    @Test
    fun isBlockedShouldBlockWhenAnyRuleMatches() {
        val update = demoUpdate()
        val missed = blockRule(FilterCondition.TextContains("missing"))
        val attachmentBlock = blockRule(FilterCondition.HasBlockKind(DynamicBlockKind.VIDEO))
        val textBlock = blockRule(FilterCondition.TextContains("Video Secret"))

        assertTrue(DynamicFilterEvaluator.isBlocked(update, listOf(missed, attachmentBlock)))
        assertTrue(DynamicFilterEvaluator.isBlocked(update, listOf(textBlock)))
        assertFalse(DynamicFilterEvaluator.isBlocked(update, listOf(missed)))
    }

    private fun blockRule(condition: FilterCondition): DynamicFilterRule {
        return DynamicFilterRule(
            id = condition.hashCode(),
            subscriptionId = 1,
            condition = condition,
            createdAtEpochSeconds = 1L,
        )
    }

    private fun demoUpdate() = testDynamicUpdate(
        payload = DynamicPayload(
            title = "Current Title",
            labels = listOf(DynamicLabel("Current Notice")),
            blocks = listOf(
                TextBlock(DynamicContent(listOf(DynamicContentNodeText("Hello World")))),
                MediaCardBlock(
                    style = MediaCardStyle.LARGE,
                    card = DynamicMediaCard(
                        kind = DynamicMediaCardKind.VIDEO,
                        id = "BV1",
                        title = "Video Secret",
                        description = "Video Description",
                        cover = testMedia("https://example.com/cover.png", MediaKind.COVER),
                        durationSeconds = 60,
                        badge = "video",
                        link = "https://example.com/video",
                    ),
                ),
                MediaCardBlock(
                    style = MediaCardStyle.SMALL,
                    card = DynamicMediaCard(
                        kind = DynamicMediaCardKind.LINK,
                        id = "card-1",
                        title = "Card Title",
                        description = "Card Secret",
                        badge = "Card Badge",
                        cover = testMedia("https://example.com/card.png", MediaKind.COVER),
                        info = "Card Info",
                        link = "https://example.com/card",
                    ),
                ),
                PollBlock(
                    title = "Poll Secret",
                    options = listOf(PollOption(text = "poll option")),
                ),
                RepostBlock(
                    referenceKind = DynamicReferenceKind.ORIGIN,
                    key = testDynamicUpdate(externalId = "origin-1").key,
                    embedded = testDynamicUpdate(
                        externalId = "origin-1",
                        payload = DynamicPayload(
                            blocks = listOf(
                                TextBlock(DynamicContent.text("Origin Secret")),
                                ImageGridBlock(
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
