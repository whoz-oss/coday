package io.whozoss.agentos.sdk.caseEvent

import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.util.UUID

class CaseEventTest {
    @Test
    fun `QuestionEvent should create AnswerEvent with correct references`() {
        // Given
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val questionEvent =
            QuestionEvent(
                projectId = projectId,
                caseId = caseId,
                agentId = agentId,
                agentName = "TestAgent",
                question = "Do you want to proceed?",
                options = listOf("Yes", "No"),
            )

        // Assert question event type
        Assertions.assertEquals(CaseEventType.QUESTION, questionEvent.type)

        val actor =
            Actor(
                id = "user-1",
                displayName = "Test User",
                role = ActorRole.USER,
            )

        // When
        val answerEvent = questionEvent.createAnswer(actor, "Yes")

        // Then Assert answer event properties
        Assertions.assertEquals(questionEvent.id, answerEvent.questionId)
        Assertions.assertEquals(projectId, answerEvent.projectId)
        Assertions.assertEquals(caseId, answerEvent.caseId)
        Assertions.assertEquals(actor, answerEvent.actor)
        Assertions.assertEquals("Yes", answerEvent.answer)
        Assertions.assertEquals(CaseEventType.ANSWER, answerEvent.type)
    }

    @Test
    fun `QuestionEvent without options should work for free text responses`() {
        // Arrange
        val projectId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val agentId = UUID.randomUUID()

        val questionEvent =
            QuestionEvent(
                projectId = projectId,
                caseId = caseId,
                agentId = agentId,
                agentName = "TestAgent",
                question = "What is your name?",
                options = null, // Free text response
            )

        // Assert question event type
        Assertions.assertEquals(CaseEventType.QUESTION, questionEvent.type)

        val actor =
            Actor(
                id = "user-1",
                displayName = "Test User",
                role = ActorRole.USER,
            )

        // Act
        val answerEvent = questionEvent.createAnswer(actor, "John Doe")

        // Assert
        Assertions.assertEquals(questionEvent.id, answerEvent.questionId)
        Assertions.assertEquals("John Doe", answerEvent.answer)
        Assertions.assertEquals(CaseEventType.ANSWER, answerEvent.type)
    }
}
