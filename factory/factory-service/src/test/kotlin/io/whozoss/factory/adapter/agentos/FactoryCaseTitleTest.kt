package io.whozoss.factory.adapter.agentos

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FactoryCaseTitleTest {
    @Test
    fun `instructions are a bounded excerpt when no explicit title exists`() {
        val instructions = "  Analyze   login\nfailures " + "x".repeat(120)

        assertThat(rootCaseTitle("https://whoz.atlassian.net/browse/WZ-35428", null, instructions))
            .isEqualTo("WZ-35428 Analyze login failures " + "x".repeat(77) + "…")
    }

    @Test
    fun `explicit title takes precedence over instructions`() {
        assertThat(rootCaseTitle("WZ-35428", " Review authentication ", "Analyze login failures"))
            .isEqualTo("WZ-35428 Review authentication")
    }
}
