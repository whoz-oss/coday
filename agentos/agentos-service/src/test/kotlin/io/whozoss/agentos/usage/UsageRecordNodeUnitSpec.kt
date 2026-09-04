package io.whozoss.agentos.usage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [UsageRecordNode] round-trip mapping (domain -> node -> domain)
 * and for [UsageRecord.Companion.fromLlmUsage].
 *
 * No Spring context required — all conversions are pure functions.
 */
class UsageRecordNodeUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val caseId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")
    val agentConfigId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000004")
    val fixedInstant: Instant = Instant.parse("2025-01-01T12:00:00Z")

    fun baseRecord(
        recordUserId: UUID? = userId,
        recordAgentConfigId: UUID? = agentConfigId,
        cost: Double? = 0.042,
        currency: String = "USD",
    ) = UsageRecord(
        metadata = EntityMetadata(id = UUID.randomUUID(), created = fixedInstant, modified = fixedInstant),
        namespaceId = namespaceId,
        caseId = caseId,
        userId = recordUserId,
        source = UsageSource.LLM,
        outcome = UsageOutcome.COMPLETED,
        agentConfigId = recordAgentConfigId,
        agentName = "copilot",
        providerName = "anthropic",
        apiModelName = "claude-sonnet-4-5",
        inputTokens = 100L,
        outputTokens = 200L,
        cacheReadTokens = 50L,
        cacheWriteTokens = 10L,
        totalTokens = 360L,
        cost = cost,
        currency = currency,
        timestamp = fixedInstant,
    )

    // -------------------------------------------------------------------------
    // Round-trip: full record
    // -------------------------------------------------------------------------

    "round-trip preserves all fields of a fully-populated UsageRecord" {
        val original = baseRecord()
        val roundTripped = UsageRecordNode.fromDomain(original).toDomain()

        roundTripped.id shouldBe original.id
        roundTripped.namespaceId shouldBe namespaceId
        roundTripped.caseId shouldBe caseId
        roundTripped.userId shouldBe userId
        roundTripped.source shouldBe UsageSource.LLM
        roundTripped.outcome shouldBe UsageOutcome.COMPLETED
        roundTripped.agentConfigId shouldBe agentConfigId
        roundTripped.agentName shouldBe "copilot"
        roundTripped.providerName shouldBe "anthropic"
        roundTripped.apiModelName shouldBe "claude-sonnet-4-5"
        roundTripped.inputTokens shouldBe 100L
        roundTripped.outputTokens shouldBe 200L
        roundTripped.cacheReadTokens shouldBe 50L
        roundTripped.cacheWriteTokens shouldBe 10L
        roundTripped.totalTokens shouldBe 360L
        roundTripped.cost shouldBe 0.042
        roundTripped.currency shouldBe "USD"
        roundTripped.timestamp shouldBe fixedInstant
        roundTripped.metadata.created shouldBe fixedInstant
    }

    // -------------------------------------------------------------------------
    // Nullable fields
    // -------------------------------------------------------------------------

    "round-trip with null cost preserves null (cost unknown, not zero)" {
        val record = baseRecord(cost = null)
        val roundTripped = UsageRecordNode.fromDomain(record).toDomain()

        roundTripped.cost shouldBe null
    }

    "round-trip with null userId preserves null (system/scheduler run)" {
        val record = baseRecord(recordUserId = null)
        val roundTripped = UsageRecordNode.fromDomain(record).toDomain()

        roundTripped.userId shouldBe null
    }

    "round-trip with null agentConfigId preserves null (filesystem-only agent)" {
        val record = baseRecord(recordAgentConfigId = null)
        val roundTripped = UsageRecordNode.fromDomain(record).toDomain()

        roundTripped.agentConfigId shouldBe null
    }

    // -------------------------------------------------------------------------
    // Non-USD currency
    // -------------------------------------------------------------------------

    "round-trip with non-USD currency preserves the currency code" {
        val record = baseRecord(cost = 0.038, currency = "EUR")
        val roundTripped = UsageRecordNode.fromDomain(record).toDomain()

        roundTripped.currency shouldBe "EUR"
        roundTripped.cost shouldBe 0.038
    }

    // -------------------------------------------------------------------------
    // Soft-delete convention
    // -------------------------------------------------------------------------

    "fromDomain writes null for removed=false (active entity)" {
        val record = baseRecord()
        val node = UsageRecordNode.fromDomain(record)

        node.removed shouldBe null
    }

    "fromDomain writes true for removed=true (soft-deleted entity)" {
        val record = baseRecord().copy(metadata = EntityMetadata(removed = true))
        val node = UsageRecordNode.fromDomain(record)

        node.removed shouldBe true
    }

    // -------------------------------------------------------------------------
    // fromLlmUsage factory
    // -------------------------------------------------------------------------

    "fromLlmUsage copies all token fields and maps estimatedCostUsd to cost" {
        val llmUsage = LlmUsage(
            inputTokens = 100L,
            outputTokens = 200L,
            cacheReadTokens = 50L,
            cacheWriteTokens = 10L,
            totalTokens = 360L,
            estimatedCostUsd = 0.042,
        )
        val record = UsageRecord.fromLlmUsage(
            llmUsage = llmUsage,
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "copilot",
            outcome = UsageOutcome.COMPLETED,
        )

        record.inputTokens shouldBe 100L
        record.outputTokens shouldBe 200L
        record.cacheReadTokens shouldBe 50L
        record.cacheWriteTokens shouldBe 10L
        record.totalTokens shouldBe 360L
        record.cost shouldBe 0.042
        record.currency shouldBe "USD"
        record.source shouldBe UsageSource.LLM
    }

    "fromLlmUsage with null estimatedCostUsd produces null cost (not zero)" {
        val llmUsage = LlmUsage(totalTokens = 100L, estimatedCostUsd = null)
        val record = UsageRecord.fromLlmUsage(
            llmUsage = llmUsage,
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "copilot",
            outcome = UsageOutcome.FAILED,
        )

        record.cost shouldBe null
        record.cost shouldNotBe 0.0
    }

    "fromLlmUsage assigns a fresh EntityMetadata id" {
        val llmUsage = LlmUsage(totalTokens = 1L)
        val r1 = UsageRecord.fromLlmUsage(llmUsage, namespaceId, caseId, "copilot", UsageOutcome.COMPLETED)
        val r2 = UsageRecord.fromLlmUsage(llmUsage, namespaceId, caseId, "copilot", UsageOutcome.COMPLETED)

        r1.id shouldNotBe r2.id
    }
})
