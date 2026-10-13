package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SseFrameParserTest {

    @Test
    fun `a simple frame is dispatched on the blank-line boundary`() {
        val parser = SseFrameParser()

        assertThat(parser.feedLine("event: case-event")).isNull()
        assertThat(parser.feedLine("id: e1")).isNull()
        assertThat(parser.feedLine("""data: {"type":"CaseStatusEvent"}""")).isNull()
        val frame = parser.feedLine("")

        assertThat(frame).isNotNull
        assertThat(frame!!.id).isEqualTo("e1")
        assertThat(frame.event).isEqualTo("case-event")
        assertThat(frame.data).isEqualTo("""{"type":"CaseStatusEvent"}""")
        assertThat(frame.isHeartbeat).isFalse()
    }

    @Test
    fun `multi-line data is joined with newlines`() {
        val parser = SseFrameParser()

        parser.feedLine("data: first")
        parser.feedLine("data: second")
        parser.feedLine("data:third")
        val frame = parser.feedLine("")

        assertThat(frame!!.data).isEqualTo("first\nsecond\nthird")
    }

    @Test
    fun `a comment line is an immediate heartbeat frame`() {
        val parser = SseFrameParser()

        val frame = parser.feedLine(":keep-alive")

        assertThat(frame).isNotNull
        assertThat(frame!!.isHeartbeat).isTrue()
    }

    @Test
    fun `a heartbeat does not disturb a partially accumulated event`() {
        val parser = SseFrameParser()

        parser.feedLine("id: e1")
        parser.feedLine(":keep-alive")
        parser.feedLine("data: payload")
        val frame = parser.feedLine("")

        assertThat(frame!!.id).isEqualTo("e1")
        assertThat(frame.data).isEqualTo("payload")
    }

    @Test
    fun `a blank line without data dispatches nothing and resets state`() {
        val parser = SseFrameParser()

        parser.feedLine("id: stray")
        assertThat(parser.feedLine("")).isNull()
        parser.feedLine("data: real")
        val frame = parser.feedLine("")

        // the stray id must not leak into the next event
        assertThat(frame!!.id).isNull()
        assertThat(frame.data).isEqualTo("real")
    }

    @Test
    fun `unknown fields are ignored`() {
        val parser = SseFrameParser()

        parser.feedLine("retry: 3000")
        parser.feedLine("data: x")
        val frame = parser.feedLine("")

        assertThat(frame!!.data).isEqualTo("x")
    }

    @Test
    fun `a field without colon has an empty value`() {
        val parser = SseFrameParser()

        parser.feedLine("data")
        val frame = parser.feedLine("")

        assertThat(frame!!.data).isEqualTo("")
    }

    @Test
    fun `carriage returns are tolerated`() {
        val parser = SseFrameParser()

        parser.feedLine("id: e1\r")
        parser.feedLine("data: payload\r")
        val frame = parser.feedLine("\r")

        assertThat(frame!!.id).isEqualTo("e1")
        assertThat(frame.data).isEqualTo("payload")
    }

    @Test
    fun `finish flushes an unterminated trailing event at end of stream`() {
        val parser = SseFrameParser()

        parser.feedLine("id: e9")
        parser.feedLine("data: tail")
        val frame = parser.finish()

        assertThat(frame).isNotNull
        assertThat(frame!!.id).isEqualTo("e9")
        assertThat(frame.data).isEqualTo("tail")
        assertThat(parser.finish()).isNull()
    }
}
