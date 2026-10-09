package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HighWaterMarkTest {

    private fun event(id: String, timestamp: String? = "2026-01-01T00:00:00Z"): CaseEventView =
        CaseEventView(eventId = id, type = "MessageEvent", caseId = "case-1", timestamp = timestamp, raw = emptyMap())

    @Test
    fun `an event id is recorded once and duplicates are rejected`() {
        val checkpoint = EventCheckpoint()

        assertThat(checkpoint.record(event("e1"))).isTrue()
        assertThat(checkpoint.record(event("e1"))).isFalse()
        assertThat(checkpoint.isDuplicate(event("e1"))).isTrue()
        assertThat(checkpoint.record(event("e2"))).isTrue()
    }

    @Test
    fun `the mark advances monotonically with the recorded events`() {
        val checkpoint = EventCheckpoint()

        checkpoint.record(event("b", "2026-01-01T00:00:02Z"))
        assertThat(checkpoint.mark).isEqualTo(HighWaterMark("2026-01-01T00:00:02Z", "b"))

        // an out-of-order older event is recorded (first sight) but does not move the mark backwards
        checkpoint.record(event("a", "2026-01-01T00:00:01Z"))
        assertThat(checkpoint.mark).isEqualTo(HighWaterMark("2026-01-01T00:00:02Z", "b"))
    }

    @Test
    fun `an id evicted from the bounded window stays covered by the mark`() {
        val checkpoint = EventCheckpoint(windowSize = 2)

        checkpoint.record(event("e1", "2026-01-01T00:00:01Z"))
        checkpoint.record(event("e2", "2026-01-01T00:00:02Z"))
        checkpoint.record(event("e3", "2026-01-01T00:00:03Z"))

        // e1 was evicted from the 2-slot window…
        assertThat(checkpoint.record(event("e1", "2026-01-01T00:00:01Z"))).isFalse()
        // …but a future event id is still fresh
        assertThat(checkpoint.record(event("e4", "2026-01-01T00:00:04Z"))).isTrue()
    }

    @Test
    fun `a replay of earlier events is fully covered by the mark`() {
        val checkpoint = EventCheckpoint(windowSize = 1)

        checkpoint.record(event("e5", "2026-01-01T00:00:05Z"))

        // same timestamp, id sorts before the mark id → covered
        assertThat(checkpoint.isDuplicate(event("e1", "2026-01-01T00:00:05Z"))).isTrue()
        // same timestamp, id sorts after the mark id → not covered
        assertThat(checkpoint.isDuplicate(event("e9", "2026-01-01T00:00:05Z"))).isFalse()
        // later timestamp → not covered
        assertThat(checkpoint.isDuplicate(event("e0", "2026-01-01T00:00:06Z"))).isFalse()
    }

    @Test
    fun `a fresh checkpoint covers nothing - full replay and dedup`() {
        val checkpoint = EventCheckpoint()

        assertThat(checkpoint.mark).isEqualTo(HighWaterMark(null, null))
        assertThat(checkpoint.isDuplicate(event("e1"))).isFalse()
        assertThat(checkpoint.record(event("e1"))).isTrue()
    }

    @Test
    fun `the store hands out one checkpoint per case and attempt`() {
        val store = HighWaterMarkStore()

        val first = store.checkpoint("case-1", "attempt-1")
        assertThat(store.checkpoint("case-1", "attempt-1")).isSameAs(first)
        assertThat(store.checkpoint("case-1", "attempt-2")).isNotSameAs(first)
        assertThat(store.checkpoint("case-2", "attempt-1")).isNotSameAs(first)

        store.clear("case-1", "attempt-1")
        assertThat(store.checkpoint("case-1", "attempt-1")).isNotSameAs(first)
    }
}
