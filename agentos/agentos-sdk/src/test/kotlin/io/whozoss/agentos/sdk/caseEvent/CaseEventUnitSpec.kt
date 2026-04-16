package io.whozoss.agentos.sdk.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import java.util.UUID

class CaseEventUnitSpec : StringSpec({

    "QuestionEvent should create AnswerEvent with correct references" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val questionEvent =
            QuestionEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = "TestAgent",
                question = "Do you want to proceed?",
                options = listOf("Yes", "No"),
            )

        questionEvent.type shouldBe CaseEventType.QUESTION

        val actor = Actor(id = "user-1", displayName = "Test User", role = ActorRole.USER)
        val answerEvent = questionEvent.createAnswer(actor, "Yes")

        answerEvent.questionId shouldBe questionEvent.id
        answerEvent.namespaceId shouldBe namespaceId
        answerEvent.caseId shouldBe caseId
        answerEvent.actor shouldBe actor
        answerEvent.answer shouldBe "Yes"
        answerEvent.type shouldBe CaseEventType.ANSWER
    }

    "QuestionEvent without options should work for free text responses" {
        val namespaceId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val questionEvent =
            QuestionEvent(
                namespaceId = namespaceId,
                caseId = caseId,
                agentId = agentId,
                agentName = "TestAgent",
                question = "What is your name?",
                options = null,
            )

        questionEvent.type shouldBe CaseEventType.QUESTION

        val actor = Actor(id = "user-1", displayName = "Test User", role = ActorRole.USER)
        val answerEvent = questionEvent.createAnswer(actor, "John Doe")

        answerEvent.questionId shouldBe questionEvent.id
        answerEvent.answer shouldBe "John Doe"
        answerEvent.type shouldBe CaseEventType.ANSWER
    }
})
