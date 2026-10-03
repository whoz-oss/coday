package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VerdictDeriverTest {

    private val context = VerdictDeriver.DerivationContext(caseId = "case-1")

    private fun status(id: String, status: String) = CaseEventView(
        eventId = id,
        type = CaseEventView.CASE_STATUS_EVENT,
        caseId = "case-1",
        timestamp = "2026-01-01T00:00:0${id.last()}Z",
        raw = mapOf("id" to id, "type" to "CaseStatusEvent", "status" to status),
    )

    private fun agentMessage(id: String, text: String) = CaseEventView(
        eventId = id,
        type = CaseEventView.MESSAGE_EVENT,
        caseId = "case-1",
        timestamp = "2026-01-01T00:00:0${id.last()}Z",
        raw = mapOf(
            "id" to id,
            "type" to "MessageEvent",
            "actor" to mapOf("role" to "AGENT"),
            "content" to listOf(mapOf("content" to text)),
        ),
    )

    private fun question(id: String, text: String) = CaseEventView(
        eventId = id,
        type = CaseEventView.QUESTION_EVENT,
        caseId = "case-1",
        timestamp = "2026-01-01T00:00:00Z",
        raw = mapOf("id" to id, "type" to "QuestionEvent", "question" to text),
    )

    private fun answer(id: String, questionId: String) = CaseEventView(
        eventId = id,
        type = CaseEventView.ANSWER_EVENT,
        caseId = "case-1",
        timestamp = "2026-01-01T00:00:01Z",
        raw = mapOf("id" to id, "type" to "AnswerEvent", "questionId" to questionId),
    )

    @Test
    fun `no events derives no verdict`() {
        assertThat(VerdictDeriver.derive(emptyList(), context)).isNull()
    }

    @Test
    fun `RUNNING proves only that the execution started - never a verdict`() {
        assertThat(VerdictDeriver.derive(listOf(status("e1", "RUNNING")), context)).isNull()
        assertThat(VerdictDeriver.derive(listOf(status("e1", "PENDING")), context)).isNull()
    }

    @Test
    fun `IDLE with an unanswered question is WaitingHuman with the question id as ref`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), question("q1", "Which branch?"), status("e2", "IDLE")),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.WaitingHuman::class.java)
        verdict as AgentOsExecutionVerdict.WaitingHuman
        assertThat(verdict.questionRef).isEqualTo("q1")
        assertThat(verdict.questionText).isEqualTo("Which branch?")
        assertThat(verdict.evidence["caseStatus"]).isEqualTo("IDLE")
    }

    @Test
    fun `IDLE with an answered question and an agent message is Indeterminate - free text is not a result`() {
        val verdict = VerdictDeriver.derive(
            listOf(
                question("q1", "Which branch?"),
                answer("a1", "q1"),
                agentMessage("m1", "done on main"),
                status("e2", "IDLE"),
            ),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
    }

    @Test
    fun `IDLE with a free-text agent message is Indeterminate - never an authoritative Succeeded`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), agentMessage("m1", "all good"), status("e2", "IDLE")),
            context,
        )

        // A raw agent message is observation evidence only: the authoritative
        // success of an agent step comes exclusively from a structured
        // submission through the capability channel.
        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        verdict as AgentOsExecutionVerdict.Indeterminate
        assertThat(verdict.reason).isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
        assertThat(verdict.evidence).containsEntry("caseId", "case-1")
        assertThat(verdict.evidence).containsEntry("caseStatus", "IDLE")
        assertThat(verdict.evidence).containsEntry("summary", "all good")
    }

    @Test
    fun `IDLE without question and without structured output is Indeterminate - never Succeeded`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), status("e2", "IDLE")),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Indeterminate).reason)
            .isEqualTo(VerdictDeriver.AGENT_NO_STRUCTURED_RESULT)
    }

    @Test
    fun `ERROR is Failed and never Succeeded`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), status("e2", "ERROR")),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Failed).code).isEqualTo("AGENT_CASE_ERROR")
    }

    @Test
    fun `KILLED is Failed and never Succeeded`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), status("e2", "KILLED")),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Failed::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Failed).code).isEqualTo("AGENT_CASE_KILLED")
    }

    @Test
    fun `KILLED after a caller-initiated interrupt is Interrupted`() {
        val verdict = VerdictDeriver.derive(
            listOf(status("e1", "RUNNING"), status("e2", "KILLED")),
            context.copy(interruptRequested = true, interruptReason = "budget exceeded"),
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Interrupted::class.java)
        assertThat((verdict as AgentOsExecutionVerdict.Interrupted).reason).isEqualTo("budget exceeded")
    }

    @Test
    fun `transient events never feed the verdict`() {
        val thinking = CaseEventView(
            eventId = "t1",
            type = "ThinkingEvent",
            caseId = "case-1",
            timestamp = "2026-01-01T00:00:00Z",
            raw = mapOf("id" to "t1", "type" to "ThinkingEvent"),
        )
        // IDLE + only a transient event besides → still Indeterminate, not Succeeded
        val verdict = VerdictDeriver.derive(listOf(thinking, status("e2", "IDLE")), context)

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
    }

    @Test
    fun `evidence carries the turn facts even when the verdict is not authoritative`() {
        val toolResponse = CaseEventView(
            eventId = "tr1",
            type = "ToolResponseEvent",
            caseId = "case-1",
            timestamp = "2026-01-01T00:00:01Z",
            raw = mapOf("id" to "tr1", "type" to "ToolResponseEvent"),
        )
        val agentFinished = CaseEventView(
            eventId = "af1",
            type = "AgentFinishedEvent",
            caseId = "case-1",
            timestamp = "2026-01-01T00:00:02Z",
            raw = mapOf("id" to "af1", "type" to "AgentFinishedEvent"),
        )

        val verdict = VerdictDeriver.derive(
            listOf(toolResponse, agentFinished, agentMessage("m1", "done"), status("e3", "IDLE")),
            context,
        )

        assertThat(verdict).isInstanceOf(AgentOsExecutionVerdict.Indeterminate::class.java)
        val evidence = (verdict as AgentOsExecutionVerdict.Indeterminate).evidence
        assertThat(evidence["agentTurns"]).isEqualTo(1)
        assertThat(evidence["toolCalls"]).isEqualTo(1)
        assertThat(evidence["modifiedFiles"]).isEqualTo(emptyList<String>())
    }
}
