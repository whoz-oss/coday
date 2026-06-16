package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperMessageSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        val userActor = Actor(id = "user-1", displayName = "Alice", role = ActorRole.USER)
        val agentActor = Actor(id = "agent-1", displayName = "Coday", role = ActorRole.AGENT)

        "MessageEvent with Text content and USER actor survives the round-trip" {
            val original =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    actor = userActor,
                    content = listOf(MessageContent.Text("Hello, can you help me?")),
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as MessageEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.actor.id shouldBe userActor.id
            roundTripped.actor.displayName shouldBe userActor.displayName
            roundTripped.actor.role shouldBe ActorRole.USER
            roundTripped.content shouldBe listOf(MessageContent.Text("Hello, can you help me?"))
            roundTripped.sessionContext shouldBe null
        }

        "MessageEvent with Image content and AGENT actor survives the round-trip" {
            val imageContent = MessageContent.Image(
                content = "base64encodeddata==",
                mimeType = "image/png",
                width = 800,
                height = 600,
            )
            val original =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    actor = agentActor,
                    content = listOf(imageContent),
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as MessageEvent

            roundTripped.actor.role shouldBe ActorRole.AGENT
            val roundTrippedImage = roundTripped.content.single() as MessageContent.Image
            roundTrippedImage.content shouldBe imageContent.content
            roundTrippedImage.mimeType shouldBe imageContent.mimeType
            roundTrippedImage.width shouldBe imageContent.width
            roundTrippedImage.height shouldBe imageContent.height
        }

        "MessageEvent with non-null sessionContext survives the round-trip" {
            val sessionCtx = mapOf("page" to "dashboard", "entityId" to "42")
            val original =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:02:00Z"),
                    actor = userActor,
                    content = listOf(MessageContent.Text("Show me the report")),
                    sessionContext = sessionCtx,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as MessageEvent

            roundTripped.sessionContext shouldBe sessionCtx
        }

        "MessageEvent with null sessionContext survives the round-trip" {
            val original =
                MessageEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:03:00Z"),
                    actor = userActor,
                    content = listOf(MessageContent.Text("No context here")),
                    sessionContext = null,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as MessageEvent

            roundTripped.sessionContext shouldBe null
        }
    })
