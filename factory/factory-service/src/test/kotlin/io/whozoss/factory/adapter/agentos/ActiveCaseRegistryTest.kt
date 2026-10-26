package io.whozoss.factory.adapter.agentos

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * Tests of [ActiveCaseRegistry]: enumeration/tracking of every active case
 * and the graceful shutdown (interrupt + kill of each registered case).
 */
class ActiveCaseRegistryTest {

    private val registry = ActiveCaseRegistry()

    private fun binding(caseId: String, attemptId: String = "attempt-$caseId") = TrustedCaseBinding(
        caseId = caseId,
        namespaceId = "ns-1",
        attemptId = attemptId,
        runtimeId = "agentos-primary",
        capabilityToken = "token-$caseId",
        environmentRef = "env-1",
        environmentRevision = 3,
    )

    @Test
    fun `registers, enumerates and tracks every active case with its trusted binding and state`() {
        registry.register(binding("case-1"))
        registry.register(binding("case-2"))
        registry.register(binding("case-3"))
        registry.markState("case-2", ActiveCaseState.RUNNING)

        assertThat(registry.activeCount()).isEqualTo(3)
        val snapshot = registry.snapshot()
        assertThat(snapshot.map { it.binding.caseId }).containsExactlyInAnyOrder("case-1", "case-2", "case-3")
        val case2 = snapshot.single { it.binding.caseId == "case-2" }
        assertThat(case2.state).isEqualTo(ActiveCaseState.RUNNING)
        assertThat(case2.binding.runtimeId).isEqualTo("agentos-primary")
        assertThat(case2.binding.environmentRef).isEqualTo("env-1")
        assertThat(case2.binding.environmentRevision).isEqualTo(3)
    }

    @Test
    fun `markBaseline records the per-turn baseline of a tracked case`() {
        registry.register(binding("case-1"))
        val baseline = HighWaterMark("2026-01-01T00:00:02Z", "e2")

        registry.markBaseline("case-1", baseline)

        assertThat(registry.snapshot().single().baseline).isEqualTo(baseline)
    }

    @Test
    fun `deregister removes a case from the enumeration`() {
        registry.register(binding("case-1"))
        registry.register(binding("case-2"))

        registry.deregister("case-1")

        assertThat(registry.activeCount()).isEqualTo(1)
        assertThat(registry.snapshot().map { it.binding.caseId }).containsExactly("case-2")
    }

    @Test
    fun `shutdownActiveCases interrupts then kills every registered case and deregisters them`() {
        val adapter = mockk<AgentRuntimeAdapter>(relaxed = true)
        registry.register(binding("case-1"))
        registry.register(binding("case-2"))
        registry.markState("case-2", ActiveCaseState.RUNNING)

        registry.shutdownActiveCases(adapter)

        verify { adapter.interrupt("case-1", any()) }
        verify { adapter.kill("case-1") }
        verify { adapter.interrupt("case-2", any()) }
        verify { adapter.kill("case-2") }
        assertThat(registry.activeCount()).isZero()
        assertThat(registry.snapshot()).isEmpty()
    }

    @Test
    fun `shutdownActiveCases is best-effort - an unreachable runtime never propagates and the case is still deregistered`() {
        val adapter = mockk<AgentRuntimeAdapter>(relaxed = true)
        every { adapter.interrupt(any(), any()) } throws RuntimeException("connection refused")
        every { adapter.kill(any()) } throws RuntimeException("connection refused")
        registry.register(binding("case-1"))

        assertThatCode { registry.shutdownActiveCases(adapter) }.doesNotThrowAnyException()

        verify { adapter.interrupt("case-1", any()) }
        verify { adapter.kill("case-1") }
        assertThat(registry.activeCount()).isZero()
    }

    @Test
    fun `shutdownActiveCases skips already terminated cases but still deregisters them`() {
        val adapter = mockk<AgentRuntimeAdapter>(relaxed = true)
        registry.register(binding("case-1"))
        registry.markState("case-1", ActiveCaseState.TERMINATED)

        registry.shutdownActiveCases(adapter)

        verify(exactly = 0) { adapter.interrupt(any(), any()) }
        verify(exactly = 0) { adapter.kill(any()) }
        assertThat(registry.activeCount()).isZero()
    }

    @Test
    fun `shutdownActiveCases on an empty registry is a no-op`() {
        val adapter = mockk<AgentRuntimeAdapter>(relaxed = true)

        registry.shutdownActiveCases(adapter)

        verify(exactly = 0) { adapter.interrupt(any(), any()) }
        verify(exactly = 0) { adapter.kill(any()) }
    }
}
