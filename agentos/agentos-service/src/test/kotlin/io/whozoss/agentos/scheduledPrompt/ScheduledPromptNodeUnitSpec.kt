package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Unit tests for [ScheduledPromptNode].
 *
 * Covers:
 * - [ScheduledPromptNode.computeTripleKey]: slugification and scope encoding
 * - [ScheduledPromptNode.fromDomain] / [ScheduledPromptNode.toDomain]: externalMetadata round-trip
 */
class ScheduledPromptNodeUnitSpec : StringSpec({

    val nsId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    /** Builds a minimal [ScheduledPrompt] with all required fields populated. */
    fun minimalPrompt(
        externalMetadata: Map<String, Any?>? = null,
    ) = ScheduledPrompt(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        agentConfigId = UUID.randomUUID(),
        promptTemplateId = UUID.randomUUID(),
        name = "test-prompt",
        recurrence = Recurrence(
            unit = SchedulerUnit.WEEK,
            timeUtc = LocalTime.of(8, 0),
        ),
        planning = Planning(
            startDate = LocalDate.of(2026, 1, 1),
            endType = SchedulerEndType.NEVER,
        ),
        nextRunAt = Instant.parse("2026-01-01T08:00:00Z"),
        externalMetadata = externalMetadata,
    )

    // -------------------------------------------------------------------------
    // Platform scope (null, null)
    // -------------------------------------------------------------------------

    "computeTripleKey with free-form name slugifies to lowercase-hyphenated key" {
        ScheduledPromptNode.computeTripleKey(null, null, "Daily Digest") shouldBe "_:_:daily-digest"
    }

    "computeTripleKey with same name different casing produces identical key" {
        val key1 = ScheduledPromptNode.computeTripleKey(null, null, "Daily Digest")
        val key2 = ScheduledPromptNode.computeTripleKey(null, null, "daily digest")
        key1 shouldBe key2
    }

    "computeTripleKey with diacritics normalizes correctly" {
        ScheduledPromptNode.computeTripleKey(null, null, "café") shouldBe "_:_:cafe"
    }

    "computeTripleKey with already-valid slug is unchanged" {
        ScheduledPromptNode.computeTripleKey(null, null, "daily-digest") shouldBe "_:_:daily-digest"
    }

    // -------------------------------------------------------------------------
    // Namespace scope
    // -------------------------------------------------------------------------

    "computeTripleKey with namespace scope encodes namespaceId" {
        val key = ScheduledPromptNode.computeTripleKey(nsId, null, "Weekly Sync")
        key shouldBe "${nsId}:_:weekly-sync"
    }

    // -------------------------------------------------------------------------
    // User × namespace scope
    // -------------------------------------------------------------------------

    "computeTripleKey with user and namespace scope encodes both ids" {
        val key = ScheduledPromptNode.computeTripleKey(nsId, userId, "Réunion hebdo")
        key shouldBe "${nsId}:${userId}:reunion-hebdo"
    }

    // -------------------------------------------------------------------------
    // Collision detection
    // -------------------------------------------------------------------------

    "computeTripleKey 'Daily Digest' and 'daily digest' collide in same scope" {
        ScheduledPromptNode.computeTripleKey(nsId, null, "Daily Digest") shouldBe
            ScheduledPromptNode.computeTripleKey(nsId, null, "daily digest")
    }

    "computeTripleKey 'Daily Digest' and 'Weekly Report' do not collide" {
        val k1 = ScheduledPromptNode.computeTripleKey(nsId, null, "Daily Digest")
        val k2 = ScheduledPromptNode.computeTripleKey(nsId, null, "Weekly Report")
        (k1 == k2) shouldBe false
    }

    // -------------------------------------------------------------------------
    // externalMetadata — fromDomain / toDomain round-trip
    // -------------------------------------------------------------------------

    "fromDomain with non-null externalMetadata serializes to non-null externalMetadataJson" {
        val metadata = mapOf("isStandard" to true, "source" to "integration")
        val node = ScheduledPromptNode.fromDomain(minimalPrompt(externalMetadata = metadata))
        node.externalMetadataJson shouldNotBe null
    }

    "toDomain restores externalMetadata map correctly from serialized node" {
        val metadata = mapOf("isStandard" to true, "source" to "integration")
        val restored = ScheduledPromptNode.fromDomain(minimalPrompt(externalMetadata = metadata)).toDomain()
        restored.externalMetadata shouldBe metadata
    }

    "fromDomain with null externalMetadata produces null externalMetadataJson" {
        val node = ScheduledPromptNode.fromDomain(minimalPrompt(externalMetadata = null))
        node.externalMetadataJson shouldBe null
    }

    "toDomain with null externalMetadataJson produces null externalMetadata" {
        val restored = ScheduledPromptNode.fromDomain(minimalPrompt(externalMetadata = null)).toDomain()
        restored.externalMetadata shouldBe null
    }

    // -------------------------------------------------------------------------
    // flowConfiguration — fromDomain / toDomain
    // -------------------------------------------------------------------------

    val mapper = ObjectMapper()

    "flowConfiguration round-trips: object params, no params, no configuration" {
        forAll(
            row(FlowConfiguration(FlowConfiguration.Search("searchAgentUsers", mapper.readTree("""{"a": "b", "n": {"x": [1, 2]}}""")), FlowConfiguration.Act("Caretaker"))),
            row(FlowConfiguration(FlowConfiguration.Search("searchAgentUsers"), FlowConfiguration.Act("Caretaker"))),
            row(null),
        ) { config ->
            val node = ScheduledPromptNode.fromDomain(minimalPrompt().copy(flowConfiguration = config))
            (node.flowConfigurationJson == null) shouldBe (config == null)
            node.toDomain().flowConfiguration shouldBe config
        }
    }

    "the stored flowConfiguration has the nested shape of the API, without a params key when there are none" {
        val withParams = FlowConfiguration(
            FlowConfiguration.Search("searchAgentUsers", mapper.readTree("""{"agentConfigId": "abc"}""")),
            FlowConfiguration.Act("Caretaker"),
        )
        val withoutParams = withParams.copy(search = FlowConfiguration.Search("searchAgentUsers"))

        fun stored(config: FlowConfiguration) =
            mapper.readTree(ScheduledPromptNode.fromDomain(minimalPrompt().copy(flowConfiguration = config)).flowConfigurationJson)

        stored(withParams) shouldBe
            mapper.readTree("""{"search":{"tool":"searchAgentUsers","params":{"agentConfigId":"abc"}},"act":{"agentName":"Caretaker"}}""")
        stored(withoutParams) shouldBe
            mapper.readTree("""{"search":{"tool":"searchAgentUsers"},"act":{"agentName":"Caretaker"}}""")
    }
})
