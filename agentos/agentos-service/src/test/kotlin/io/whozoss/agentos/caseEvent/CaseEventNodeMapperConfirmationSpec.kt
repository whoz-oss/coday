package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.ConfirmationResolvedEvent
import io.whozoss.agentos.sdk.caseEvent.PendingConfirmationEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

/**
 * Round-trip tests for the two confirmation events through [CaseEventNodeMapper].
 * Catches mismatches between domain field names and node field names (e.g. inputJson
 * vs pendingPayloadJson) that would otherwise surface only at runtime in production.
 */
class CaseEventNodeMapperConfirmationSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        "PendingConfirmationEvent survives the fromDomain/toDomain round-trip" {
            val original =
                PendingConfirmationEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    toolRequestId = "tool-req-42",
                    toolName = "FILES__remove",
                    inputJson = """{"path":"old.txt"}""",
                    toolConfirmationInstructions = "Be strict on deletion.",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as PendingConfirmationEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.toolRequestId shouldBe original.toolRequestId
            roundTripped.toolName shouldBe original.toolName
            roundTripped.inputJson shouldBe original.inputJson
            roundTripped.toolConfirmationInstructions shouldBe original.toolConfirmationInstructions
        }

        "PendingConfirmationEvent with default analysisInstructions round-trips correctly" {
            val original =
                PendingConfirmationEvent(
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    toolRequestId = "tool-req-default",
                    toolName = "TEST__noop",
                    inputJson = "{}",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as PendingConfirmationEvent

            roundTripped.inputJson shouldBe "{}"
            roundTripped.toolConfirmationInstructions shouldBe ""
        }

        "ConfirmationResolvedEvent (confirmed=true) survives the round-trip" {
            val original =
                ConfirmationResolvedEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    pendingEventId = UUID.randomUUID(),
                    confirmed = true,
                    resultText = "File old.txt deleted successfully",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ConfirmationResolvedEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.pendingEventId shouldBe original.pendingEventId
            roundTripped.confirmed shouldBe true
            roundTripped.resultText shouldBe original.resultText
        }

        "ConfirmationResolvedEvent (confirmed=false) round-trips with reject text" {
            val original =
                ConfirmationResolvedEvent(
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    pendingEventId = UUID.randomUUID(),
                    confirmed = false,
                    resultText = "Action cancelled.",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ConfirmationResolvedEvent

            roundTripped.confirmed shouldBe false
            roundTripped.resultText shouldBe "Action cancelled."
        }
    })
