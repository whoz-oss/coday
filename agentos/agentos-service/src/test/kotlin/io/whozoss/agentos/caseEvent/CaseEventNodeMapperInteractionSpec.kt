package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.AnswerEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Instant
import java.util.UUID

class CaseEventNodeMapperInteractionSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule()
        val nodeMapper = CaseEventNodeMapper(MessageContentSerializer(mapper))

        val agentId = UUID.randomUUID()
        val userActor = Actor(id = "user-1", displayName = "Bob", role = ActorRole.USER)

        // ─── QuestionEvent ───────────────────────────────────────────────────────────

        "QuestionEvent with options list survives the round-trip" {
            val original =
                QuestionEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:00:00Z"),
                    agentId = agentId,
                    agentName = "RouterAgent",
                    question = "Which environment should I deploy to?",
                    options = listOf("staging", "production"),
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as QuestionEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.agentId shouldBe agentId
            roundTripped.agentName shouldBe "RouterAgent"
            roundTripped.question shouldBe "Which environment should I deploy to?"
            roundTripped.options shouldBe listOf("staging", "production")
            roundTripped.questionType shouldBe QuestionType.FREE_TEXT
        }

        "QuestionEvent with OAUTH_AUTHORIZE questionType survives the round-trip" {
            val original =
                QuestionEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:03:00Z"),
                    agentId = agentId,
                    agentName = "RouterAgent",
                    question = "Please authorise access.",
                    options = null,
                    questionType = QuestionType.OAUTH_AUTHORIZE,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as QuestionEvent

            roundTripped.questionType shouldBe QuestionType.OAUTH_AUTHORIZE
        }

        "QuestionEvent with unknown questionType in node defaults to FREE_TEXT" {
            val node =
                QuestionEventNode(
                    id = UUID.randomUUID().toString(),
                    caseId = UUID.randomUUID().toString(),
                    namespaceId = UUID.randomUUID().toString(),
                    timestamp = Instant.parse("2026-05-19T15:04:00Z"),
                    agentId = agentId.toString(),
                    agentName = "LegacyAgent",
                    question = "Old question from before the field existed.",
                    options = null,
                    questionType = "UNKNOWN_VALUE",
                )

            val roundTripped = nodeMapper.toDomain(node) as QuestionEvent

            roundTripped.questionType shouldBe QuestionType.FREE_TEXT
        }

        "QuestionEvent with null options survives the round-trip" {
            val original =
                QuestionEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:01:00Z"),
                    agentId = agentId,
                    agentName = "RouterAgent",
                    question = "What is the target path?",
                    options = null,
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as QuestionEvent

            roundTripped.options shouldBe null
        }

        // ─── AnswerEvent ─────────────────────────────────────────────────────────────

        "AnswerEvent survives the fromDomain/toDomain round-trip" {
            val questionId = UUID.randomUUID()
            val original =
                AnswerEvent(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    timestamp = Instant.parse("2026-05-19T15:02:00Z"),
                    questionId = questionId,
                    actor = userActor,
                    answer = "staging",
                )

            val node = nodeMapper.fromDomain(original)
            val roundTripped = nodeMapper.toDomain(node) as AnswerEvent

            roundTripped.metadata.id shouldBe original.metadata.id
            roundTripped.namespaceId shouldBe original.namespaceId
            roundTripped.caseId shouldBe original.caseId
            roundTripped.timestamp shouldBe original.timestamp
            roundTripped.questionId shouldBe questionId
            roundTripped.actor.id shouldBe userActor.id
            roundTripped.actor.displayName shouldBe userActor.displayName
            roundTripped.actor.role shouldBe ActorRole.USER
            roundTripped.answer shouldBe "staging"
        }
    })
