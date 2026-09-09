package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.SubCaseFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.SubCaseOutcome
import io.whozoss.agentos.sdk.caseEvent.SubCaseStartedEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperDelegationSpec : StringSpec({
    val mapper = CaseEventNodeMapper(MessageContentSerializer(jacksonObjectMapper().registerKotlinModule()))
    val namespaceId = UUID.randomUUID()
    val parentCaseId = UUID.randomUUID()
    val subCaseId = UUID.randomUUID()
    val delegationId = UUID.randomUUID()

    "SubCaseStartedEvent survives Neo4j mapper round-trip" {
        val original = SubCaseStartedEvent(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            caseId = parentCaseId,
            timestamp = Instant.parse("2026-05-20T10:00:00Z"),
            delegationId = delegationId,
            toolRequestId = "tool-request-1",
            subCaseId = subCaseId,
            agentName = "researcher",
            task = "Investigate the issue",
            resumed = true,
        )

        val roundTripped = mapper.toDomain(mapper.fromDomain(original)) as SubCaseStartedEvent
        roundTripped shouldBe original
    }

    "SubCaseFinishedEvent preserves outcome errorType and removed metadata" {
        val original = SubCaseFinishedEvent(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = namespaceId,
            caseId = parentCaseId,
            timestamp = Instant.parse("2026-05-20T10:01:00Z"),
            delegationId = delegationId,
            toolRequestId = "tool-request-1",
            subCaseId = subCaseId,
            agentName = "researcher",
            outcome = SubCaseOutcome.TIMEOUT,
            errorType = "TIMEOUT",
        )

        val removed = mapper.withRemoved(mapper.fromDomain(original), true)
        val roundTripped = mapper.toDomain(removed) as SubCaseFinishedEvent

        roundTripped.delegationId shouldBe delegationId
        roundTripped.toolRequestId shouldBe "tool-request-1"
        roundTripped.subCaseId shouldBe subCaseId
        roundTripped.outcome shouldBe SubCaseOutcome.TIMEOUT
        roundTripped.errorType shouldBe "TIMEOUT"
        roundTripped.metadata.removed shouldBe true
    }
})
