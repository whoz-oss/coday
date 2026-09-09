package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.AgentRunningEvent
import io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperAgentLifecycleSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        "AgentSelectedEvent survives the fromDomain/toDomain round-trip" {
            val agentId = UUID.randomUUID()
            val original =
                AgentSelectedEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    agentId = agentId,
                    agentName = "RouterAgent",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as AgentSelectedEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.agentId shouldBe agentId
            roundTripped.agentName shouldBe "RouterAgent"
        }

        "AgentRunningEvent survives the fromDomain/toDomain round-trip" {
            val agentId = UUID.randomUUID()
            val original =
                AgentRunningEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    agentId = agentId,
                    agentName = "WorkerAgent",
                    llmProvider = "anthropic",
                    llmModel = "claude-sonnet-4-20250514",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as AgentRunningEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.agentId shouldBe agentId
            roundTripped.agentName shouldBe "WorkerAgent"
            roundTripped.llmProvider shouldBe "anthropic"
            roundTripped.llmModel shouldBe "claude-sonnet-4-20250514"
        }

        "AgentFinishedEvent survives the fromDomain/toDomain round-trip" {
            val agentId = UUID.randomUUID()
            val original =
                AgentFinishedEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:02:00Z"),
                    agentId = agentId,
                    agentName = "WorkerAgent",
                    llmProvider = "anthropic",
                    llmModel = "claude-sonnet-4-20250514",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as AgentFinishedEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.agentId shouldBe agentId
            roundTripped.agentName shouldBe "WorkerAgent"
            roundTripped.llmProvider shouldBe "anthropic"
            roundTripped.llmModel shouldBe "claude-sonnet-4-20250514"
        }
    })
