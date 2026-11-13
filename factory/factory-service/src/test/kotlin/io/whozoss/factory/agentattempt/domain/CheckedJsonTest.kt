package io.whozoss.factory.agentattempt.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CheckedJsonTest {
    @Test
    fun `object conversion preserves string keys nulls and legacy nested values`() {
        val raw: Map<*, *> = linkedMapOf("first" to null, "nested" to listOf(mapOf("value" to 42)))

        assertThat(raw.toCheckedJsonObject()).containsExactlyEntriesOf(
            linkedMapOf("first" to null, "nested" to listOf(mapOf("value" to 42))),
        )
        assertThat(null.checkedJsonObjectOrNull()).isNull()
    }

    @Test
    fun `object conversion rejects nonstring keys rather than dropping or coercing them`() {
        for (key in listOf(1, null)) {
            assertThatThrownBy { mapOf(key to "critical").toCheckedJsonObject() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("keys must be strings")
        }
    }

    @Test
    fun `present nonobject envelope context is rejected`() {
        for (field in listOf("inputs", "runBrief")) {
            for (value in listOf("[]", "42", "\"not an object\"")) {
                assertThatThrownBy { AttemptContextEnvelope.fromJson("{\"$field\":$value}") }
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining("Expected a JSON object")
            }
        }
    }

    @Test
    fun `legacy absent and explicit null context retain their defaults`() {
        for (json in listOf("{}", "{\"inputs\":null,\"runBrief\":null}")) {
            val envelope = AttemptContextEnvelope.fromJson(json)
            assertThat(envelope.inputs).isEmpty()
            assertThat(envelope.runBrief).isNull()
        }
    }
}
