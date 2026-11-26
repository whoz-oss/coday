package io.whozoss.factory.adapter.agentos

import io.whozoss.factory.agentattempt.domain.ResolvedModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AttemptModelObserverTest {
    private fun event(id: String, agent: String = "Dev", case: String = "case", model: String = "actual") =
        CaseEventView(id, "AgentRunningEvent", case, "2026-01-01T00:00:00Z", mapOf(
            "agentName" to agent, "llmProvider" to "provider", "llmModel" to model,
        ))

    @Test
    fun `same case retry ignores old model and other agents and cases`() {
        val history = listOf(event("1", model = "old"), event("3", "Other"), event("4", case = "other"), event("5"))
        assertThat(resolveAttemptModel("case", "Dev", HighWaterMark("2026-01-01T00:00:00Z", "2"), history))
            .isEqualTo(ResolvedModel("provider", "actual"))
    }

    @Test
    fun `replayed recovery checkpoint yields the same first actual model and ignores later changes`() {
        val history = listOf(event("3", model = "first"), event("4", model = "later"))
        repeat(2) {
            assertThat(resolveAttemptModel("case", "Dev", HighWaterMark("2026-01-01T00:00:00Z", "2"), history.reversed()))
                .isEqualTo(ResolvedModel("provider", "first"))
        }
    }

    @Test
    fun `missing attribution and credential shaped metadata remain unknown`() {
        assertThat(resolveAttemptModel("case", "Dev", HighWaterMark(null, null), listOf(event("3", model = "sk-secret")))).isNull()
        assertThat(resolveAttemptModel("case", "Dev", HighWaterMark(null, null), emptyList())).isNull()
    }

    private fun at(id: String, timestamp: String, model: String) =
        CaseEventView(id, "AgentRunningEvent", "case", timestamp, mapOf(
            "agentName" to "Dev", "llmProvider" to "provider", "llmModel" to model,
        ))

    @Test
    fun `checkpoint comparison is chronological across differing fractional precision`() {
        // As text "...00Z" > "...00.5Z" ('Z' > '.'), yet 00.5Z is half a second AFTER 00Z.
        val baseline = HighWaterMark("2026-01-01T00:00:00Z", "b")
        assertThat(EventCheckpoint.covers(baseline, at("a", "2026-01-01T00:00:00.500Z", "x"))).isFalse()
        assertThat(EventCheckpoint.covers(baseline, at("a", "2025-12-31T23:59:59.999Z", "x"))).isTrue()
        assertThat(EventCheckpoint.covers(baseline, at("a", "2026-01-01T00:00:00.000Z", "x"))).isTrue()
        assertThat(EventCheckpoint.covers(baseline, at("c", "2026-01-01T00:00:00.000Z", "x"))).isFalse()
    }

    @Test
    fun `the first model is the earliest instant not the lexicographically smallest timestamp`() {
        val history = listOf(
            at("1", "2026-01-01T00:00:00.500Z", "later"),
            at("2", "2026-01-01T00:00:00Z", "earlier"),
        )
        assertThat(resolveAttemptModel("case", "Dev", HighWaterMark(null, null), history))
            .isEqualTo(ResolvedModel("provider", "earlier"))
    }

    @Test
    fun `the checkpoint advances chronologically`() {
        val first = EventCheckpoint.advance(HighWaterMark(null, null), at("a", "2026-01-01T00:00:00.500Z", "x"))
        val unchanged = EventCheckpoint.advance(first, at("b", "2026-01-01T00:00:00Z", "x"))
        assertThat(unchanged).isEqualTo(first)
    }
}
