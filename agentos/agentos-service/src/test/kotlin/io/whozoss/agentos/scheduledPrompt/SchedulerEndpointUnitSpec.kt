package io.whozoss.agentos.scheduledPrompt

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * Unit tests for [SchedulerEndpoint].
 *
 * Uses MockK to mock [SchedulerScanner] and [ExecutionWindowService] — no Spring context required.
 * The `consume` phase is a no-op on the converged path; callers still receive a 200 with the
 * current status rather than a 400, so existing JMX/HTTP integrations remain compatible.
 */
class SchedulerEndpointUnitSpec : StringSpec() {
    private fun endpoint(
        scanner: SchedulerScanner = mockk(relaxed = true),
        executionWindowService: ExecutionWindowService = mockk(relaxed = true),
    ): SchedulerEndpoint = SchedulerEndpoint(scanner, executionWindowService)

    data class StatusCase(
        val claimPaused: Boolean,
    )

    data class ControlCase(
        val phase: String,
        val action: String,
        val expectClaimPaused: Boolean,
        val verifyCall: (SchedulerScanner) -> Unit,
        val verifyNotCalled: (SchedulerScanner) -> Unit,
    )

    data class InvalidCase(
        val phase: String,
        val action: String,
    )

    init {
        // -------------------------------------------------------------------------
        // status — data-driven
        // -------------------------------------------------------------------------

        listOf(
            StatusCase(claimPaused = false),
            StatusCase(claimPaused = true),
        ).forEach { (claimPaused) ->
            "status claimPaused=$claimPaused" {
                val scanner = mockk<SchedulerScanner> {
                    every { isClaimPaused() } returns claimPaused
                }
                val result = endpoint(scanner).status()
                result["claimPaused"] shouldBe claimPaused
            }
        }

        // -------------------------------------------------------------------------
        // control routing — data-driven
        // -------------------------------------------------------------------------

        listOf(
            ControlCase(
                phase = "claim",
                action = "pause",
                expectClaimPaused = true,
                verifyCall = { sc -> verify(exactly = 1) { sc.pauseClaim() } },
                verifyNotCalled = { sc -> verify(exactly = 0) { sc.resumeClaim() } },
            ),
            ControlCase(
                phase = "claim",
                action = "resume",
                expectClaimPaused = false,
                verifyCall = { sc -> verify(exactly = 1) { sc.resumeClaim() } },
                verifyNotCalled = { sc -> verify(exactly = 0) { sc.pauseClaim() } },
            ),
            // consume is a no-op — claim is not affected
            ControlCase(
                phase = "consume",
                action = "pause",
                expectClaimPaused = false,
                verifyCall = { _ -> /* no-op */ },
                verifyNotCalled = { sc -> verify(exactly = 0) { sc.pauseClaim() } },
            ),
            ControlCase(
                phase = "consume",
                action = "resume",
                expectClaimPaused = false,
                verifyCall = { _ -> /* no-op */ },
                verifyNotCalled = { sc -> verify(exactly = 0) { sc.resumeClaim() } },
            ),
            ControlCase(
                phase = "all",
                action = "pause",
                expectClaimPaused = true,
                verifyCall = { sc -> verify(exactly = 1) { sc.pauseClaim() } },
                verifyNotCalled = { _ -> /* consume is no-op */ },
            ),
            ControlCase(
                phase = "all",
                action = "resume",
                expectClaimPaused = false,
                verifyCall = { sc -> verify(exactly = 1) { sc.resumeClaim() } },
                verifyNotCalled = { _ -> /* consume is no-op */ },
            ),
        ).forEach { case ->
            "control phase=${case.phase} action=${case.action}" {
                val scanner = mockk<SchedulerScanner>(relaxed = true) {
                    every { isClaimPaused() } returns case.expectClaimPaused
                }
                val result = endpoint(scanner).control(
                    claimOrConsumeOrAllPhase = case.phase,
                    pauseOrResumeAction = case.action,
                )
                case.verifyCall(scanner)
                case.verifyNotCalled(scanner)
                result["claimPaused"] shouldBe case.expectClaimPaused
            }
        }

        // -------------------------------------------------------------------------
        // invalid inputs — data-driven
        // -------------------------------------------------------------------------

        listOf(
            InvalidCase(phase = "unknown", action = "pause"),
            InvalidCase(phase = "claim", action = "stop"),
            InvalidCase(phase = "consume", action = "stop"),
            InvalidCase(phase = "all", action = "stop"),
            InvalidCase(phase = "", action = "pause"),
            InvalidCase(phase = "claim", action = ""),
        ).forEach { (phase, action) ->
            "control phase=$phase action=$action throws IllegalArgumentException" {
                shouldThrow<IllegalArgumentException> {
                    endpoint().control(claimOrConsumeOrAllPhase = phase, pauseOrResumeAction = action)
                }
            }
        }
    }
}
