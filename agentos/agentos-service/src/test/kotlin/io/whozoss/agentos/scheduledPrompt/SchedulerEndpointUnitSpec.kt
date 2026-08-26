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
 * Uses MockK to mock [SchedulerScanner] — no Spring context required.
 * Data-driven via Kotest [StringSpec] context/test loops for control routing and status assertions.
 */
class SchedulerEndpointUnitSpec : StringSpec() {
    private fun endpoint(scanner: SchedulerScanner = mockk(relaxed = true)): SchedulerEndpoint = SchedulerEndpoint(scanner)

    data class StatusCase(
        val claimPaused: Boolean,
        val consumePaused: Boolean,
    )

    data class ControlCase(
        val phase: String,
        val action: String,
        val expectClaimPaused: Boolean,
        val expectConsumePaused: Boolean,
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
            StatusCase(claimPaused = false, consumePaused = false),
            StatusCase(claimPaused = true, consumePaused = false),
            StatusCase(claimPaused = false, consumePaused = true),
            StatusCase(claimPaused = true, consumePaused = true),
        ).forEach { (claimPaused, consumePaused) ->
            "status claimPaused=$claimPaused consumePaused=$consumePaused" {
                val scanner =
                    mockk<SchedulerScanner> {
                        every { isClaimPaused() } returns claimPaused
                        every { isConsumePaused() } returns consumePaused
                    }
                val result = endpoint(scanner).status()
                result["claimPaused"] shouldBe claimPaused
                result["consumePaused"] shouldBe consumePaused
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
                expectConsumePaused = false,
                verifyCall = { verify(exactly = 1) { it.pauseClaim() } },
                verifyNotCalled = { verify(exactly = 0) { it.pauseConsume() } },
            ),
            ControlCase(
                phase = "claim",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = { verify(exactly = 1) { it.resumeClaim() } },
                verifyNotCalled = { verify(exactly = 0) { it.resumeConsume() } },
            ),
            ControlCase(
                phase = "consume",
                action = "pause",
                expectClaimPaused = false,
                expectConsumePaused = true,
                verifyCall = { verify(exactly = 1) { it.pauseConsume() } },
                verifyNotCalled = { verify(exactly = 0) { it.pauseClaim() } },
            ),
            ControlCase(
                phase = "consume",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = { verify(exactly = 1) { it.resumeConsume() } },
                verifyNotCalled = { verify(exactly = 0) { it.resumeClaim() } },
            ),
            ControlCase(
                phase = "all",
                action = "pause",
                expectClaimPaused = true,
                expectConsumePaused = true,
                verifyCall = {
                    verify(exactly = 1) { it.pauseClaim() }
                    verify(exactly = 1) { it.pauseConsume() }
                },
                verifyNotCalled = { /* both called — nothing to negate */ },
            ),
            ControlCase(
                phase = "all",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = {
                    verify(exactly = 1) { it.resumeClaim() }
                    verify(exactly = 1) { it.resumeConsume() }
                },
                verifyNotCalled = { /* both called — nothing to negate */ },
            ),
        ).forEach { case ->
            "control phase=${case.phase} action=${case.action}" {
                val scanner =
                    mockk<SchedulerScanner>(relaxed = true) {
                        every { isClaimPaused() } returns case.expectClaimPaused
                        every { isConsumePaused() } returns case.expectConsumePaused
                    }
                val result = endpoint(scanner).control(claimOrConsumeOrAllPhase = case.phase, pauseOrResumeAction = case.action)
                case.verifyCall(scanner)
                case.verifyNotCalled(scanner)
                result["claimPaused"] shouldBe case.expectClaimPaused
                result["consumePaused"] shouldBe case.expectConsumePaused
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
