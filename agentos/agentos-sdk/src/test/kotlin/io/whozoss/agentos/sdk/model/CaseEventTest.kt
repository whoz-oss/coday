package io.whozoss.agentos.sdk.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class CaseEventTest {
    @Test
    fun `QuestionEvent should create AnswerEvent with correct references`() {
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
                question = "Do you want to proceed?",
                options = listOf("Yes", "No"),
            )

        // Assert question event type
        assertEquals(CaseEventType.QUESTION, questionEvent.type)

        val actor =
            Actor(
                id = "user-1",
                displayName = "Test User",
                role = ActorRole.USER,
            )

        // Act
        val answerEvent = questionEvent.createAnswer(actor, "Yes")

        // Assert answer event properties
        assertEquals(questionEvent.id, answerEvent.questionId)
        assertEquals(projectId, answerEvent.projectId)
        assertEquals(caseId, answerEvent.caseId)
        assertEquals(actor, answerEvent.actor)
        assertEquals("Yes", answerEvent.answer)
        assertEquals(CaseEventType.ANSWER, answerEvent.type)
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
        assertEquals(CaseEventType.QUESTION, questionEvent.type)

        val actor =
            Actor(
                id = "user-1",
                displayName = "Test User",
                role = ActorRole.USER,
            )

        // Act
        val answerEvent = questionEvent.createAnswer(actor, "John Doe")

        // Assert
        assertEquals(questionEvent.id, answerEvent.questionId)
        assertEquals("John Doe", answerEvent.answer)
        assertEquals(CaseEventType.ANSWER, answerEvent.type)
    }
}
