package io.whozoss.factory.workflow

import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.workflow.service.codeVerificationFacts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CodeVerificationFactsTest {
    @Test
    fun `refused verification preserves reason without claiming execution`() {
        val facts = codeVerificationFacts(CapabilityOutcome.CodeRefused(
            "build-and-test", "frontend-build-and-test", "VERIFICATION_NOT_DECLARED", "not declared",
        ))
        assertThat(facts["executed"]).isEqualTo(false)
        assertThat(facts["code"]).isEqualTo("VERIFICATION_NOT_DECLARED")
        assertThat(facts["message"]).isEqualTo("not declared")
        assertThat(facts).doesNotContainKeys("exitCode", "durationMs")
    }

    @Test
    fun `execution retains exit and timing without exposing raw output`() {
        val facts = codeVerificationFacts(CapabilityOutcome.CodeExecuted(
            "build-and-test", "frontend-build-and-test", "command with secret", 1000L,
            1, false, 123L, "sensitive stdout", "sensitive stderr",
            cwd = "/worktrees/run-1", observedHead = "a".repeat(40), rootIdentity = "sha256:${"b".repeat(64)}",
        ))
        assertThat(facts["executed"]).isEqualTo(true)
        assertThat(facts["exitCode"]).isEqualTo(1)
        assertThat(facts["durationMs"]).isEqualTo(123L)
        assertThat(facts["command"]).isEqualTo("command with secret")
        assertThat(facts["cwd"]).isEqualTo("/worktrees/run-1")
        assertThat(facts["observedHead"]).isEqualTo("a".repeat(40))
        assertThat(facts["rootIdentity"]).isEqualTo("sha256:${"b".repeat(64)}")
        assertThat((facts["stdout"] as Map<*, *>)["excerpt"]).isEqualTo("sensitive stdout")
        assertThat((facts["stderr"] as Map<*, *>)["excerpt"]).isEqualTo("sensitive stderr")
    }

    @Test
    fun `execution diagnostics are bounded and redact common secrets`() {
        val facts = codeVerificationFacts(CapabilityOutcome.CodeExecuted(
            "build-and-test", "frontend-build-and-test", "TOKEN=abc ./test", 1000L,
            1, false, 123L, "Authorization: Bearer abc", "PASSWORD=secret " + "x".repeat(20_000),
        ))
        assertThat(facts["command"].toString()).contains("TOKEN=<redacted>").doesNotContain("abc")
        assertThat((facts["stdout"] as Map<*, *>)["excerpt"].toString()).contains("Authorization=<redacted>")
        val stderr = facts["stderr"] as Map<*, *>
        assertThat(stderr["excerpt"].toString()).doesNotContain("secret")
        assertThat(stderr["truncated"]).isEqualTo(true)
        assertThat(stderr["sha256"].toString()).startsWith("sha256:")
    }
}
