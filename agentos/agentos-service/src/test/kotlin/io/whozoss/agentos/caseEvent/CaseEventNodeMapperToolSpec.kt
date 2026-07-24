package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.caseEvent.ToolSelectedEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.EnrichmentPhaseTrace
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperToolSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        // ─── ToolRequestEvent ────────────────────────────────────────────────────────

        "ToolRequestEvent with args survives the round-trip" {
            val original =
                ToolRequestEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    toolRequestId = "req-1",
                    toolName = "FILES__readFile",
                    args = """{"path":"src/main.kt"}""",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolRequestEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.toolRequestId shouldBe "req-1"
            roundTripped.toolName shouldBe "FILES__readFile"
            roundTripped.args shouldBe original.args
            roundTripped.enrichmentPhases shouldBe null
        }

        "ToolRequestEvent with null args survives the round-trip" {
            val original =
                ToolRequestEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    toolRequestId = "req-2",
                    toolName = "NOOP__ping",
                    args = null,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolRequestEvent

            roundTripped.args shouldBe null
        }

        "ToolRequestEvent with enrichmentPhases survives the round-trip" {
            val phases = listOf(
                EnrichmentPhaseTrace(
                    phaseIndex = 0,
                    prompt = "Extract the file path",
                    llmOutput = """{"path":"src/main.kt"}""",
                    enrichmentContent = "src/main.kt",
                    success = true,
                ),
            )
            val original =
                ToolRequestEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:02:00Z"),
                    toolRequestId = "req-3",
                    toolName = "FILES__readFile",
                    args = """{"path":"src/main.kt"}""",
                    enrichmentPhases = phases,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolRequestEvent

            roundTripped.enrichmentPhases shouldBe phases
        }

        "ToolRequestEvent with null enrichmentPhases survives the round-trip" {
            val original =
                ToolRequestEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:03:00Z"),
                    toolRequestId = "req-4",
                    toolName = "FILES__readFile",
                    args = null,
                    enrichmentPhases = null,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolRequestEvent

            roundTripped.enrichmentPhases shouldBe null
        }

        // ─── ToolResponseEvent ───────────────────────────────────────────────────────

        "ToolResponseEvent with success=true survives the round-trip" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:04:00Z"),
                    toolRequestId = "req-1",
                    toolName = "FILES__readFile",
                    output = MessageContent.Text("file contents here"),
                    success = true,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.toolRequestId shouldBe "req-1"
            roundTripped.toolName shouldBe "FILES__readFile"
            roundTripped.output shouldBe MessageContent.Text("file contents here")
            roundTripped.success shouldBe true
            roundTripped.toolMetadata shouldBe emptyMap()
            roundTripped.durationMs shouldBe null
        }

        "ToolResponseEvent with success=false survives the round-trip" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:05:00Z"),
                    toolRequestId = "req-5",
                    toolName = "FILES__remove",
                    output = MessageContent.Text("Error: file not found"),
                    success = false,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.success shouldBe false
            roundTripped.output shouldBe MessageContent.Text("Error: file not found")
        }

        "ToolResponseEvent with non-empty toolMetadata survives the round-trip" {
            val metadata = mapOf("entityId" to "42", "entityType" to "User")
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:06:00Z"),
                    toolRequestId = "req-6",
                    toolName = "USERS__get",
                    output = MessageContent.Text("User fetched"),
                    success = true,
                    toolMetadata = metadata,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.toolMetadata shouldBe metadata
        }

        "ToolResponseEvent with empty toolMetadata survives the round-trip" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:07:00Z"),
                    toolRequestId = "req-7",
                    toolName = "NOOP__ping",
                    output = MessageContent.Text("pong"),
                    success = true,
                    toolMetadata = emptyMap(),
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.toolMetadata shouldBe emptyMap()
        }

        "ToolResponseEvent with durationMs survives the round-trip" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:08:00Z"),
                    toolRequestId = "req-8",
                    toolName = "SLOW__tool",
                    output = MessageContent.Text("done"),
                    success = true,
                    durationMs = 1234L,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.durationMs shouldBe 1234L
        }

        "ToolResponseEvent with null durationMs survives the round-trip" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:09:00Z"),
                    toolRequestId = "req-9",
                    toolName = "FAST__tool",
                    output = MessageContent.Text("instant"),
                    success = true,
                    durationMs = null,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.durationMs shouldBe null
        }

        "ToolResponseEvent with images survives the round-trip" {
            val images = listOf(
                MessageContent.Image(content = "aGVsbG8=", mimeType = "image/jpeg", width = 1024, height = 745),
                MessageContent.Image(content = "d29ybGQ=", mimeType = "image/png", width = 100, height = 80),
            )
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:11:00Z"),
                    toolRequestId = "req-10",
                    toolName = "FILES__readAsImage",
                    output = MessageContent.Text("Rendered PDF cv.pdf: page(s) 1-2 of 2"),
                    success = true,
                    images = images,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent

            roundTripped.images shouldBe images
            roundTripped.output shouldBe original.output
        }

        "ToolResponseEvent without images round-trips to an empty list (legacy nodes)" {
            val original =
                ToolResponseEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:12:00Z"),
                    toolRequestId = "req-11",
                    toolName = "FILES__readFile",
                    output = MessageContent.Text("text"),
                    success = true,
                )

            val node = nodeMapper.fromDomain(original)
            (node as ToolResponseEventNode).imagesJson shouldBe null

            val roundTripped = nodeMapper.toDomain(node) as ToolResponseEvent
            roundTripped.images shouldBe emptyList()
        }

        // ─── ToolSelectedEvent ───────────────────────────────────────────────────────

        "ToolSelectedEvent survives the fromDomain/toDomain round-trip" {
            val agentId = UUID.randomUUID()
            val original =
                ToolSelectedEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:10:00Z"),
                    agentId = agentId,
                    toolName = "FILES__readFile",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as ToolSelectedEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.agentId shouldBe agentId
            roundTripped.toolName shouldBe "FILES__readFile"
        }
    })
