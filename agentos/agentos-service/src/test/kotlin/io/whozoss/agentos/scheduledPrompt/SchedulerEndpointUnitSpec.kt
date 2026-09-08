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
 * Uses MockK to mock [SchedulerScanner] and [ScheduledPromptExecutor] — no Spring context required.
 * Data-driven via Kotest [StringSpec] context/test loops for control routing and status assertions.
 *
 * The `claim` phase is delegated to [SchedulerScanner]; the `consume` phase is delegated
 * to [ScheduledPromptExecutor] (which owns the producer loop since the refactoring to
 * producer+channel+worker-pool).
 */
class SchedulerEndpointUnitSpec : StringSpec() {
    private fun endpoint(
        scanner: SchedulerScanner = mockk(relaxed = true),
        executor: ScheduledPromptExecutor = mockk(relaxed = true),
    ): SchedulerEndpoint = SchedulerEndpoint(scanner, executor)

    data class StatusCase(
        val claimPaused: Boolean,
        val consumePaused: Boolean,
    )

    data class ControlCase(
        val phase: String,
        val action: String,
        val expectClaimPaused: Boolean,
        val expectConsumePaused: Boolean,
        val verifyCall: (SchedulerScanner, ScheduledPromptExecutor) -> Unit,
        val verifyNotCalled: (SchedulerScanner, ScheduledPromptExecutor) -> Unit,
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
                val scanner = mockk<SchedulerScanner> {
                    every { isClaimPaused() } returns claimPaused
                }
                val executor = mockk<ScheduledPromptExecutor> {
                    every { isConsumePaused() } returns consumePaused
                }
                val result = endpoint(scanner, executor).status()
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
                verifyCall = { sc, _ -> verify(exactly = 1) { sc.pauseClaim() } },
                verifyNotCalled = { _, ex -> verify(exactly = 0) { ex.pauseConsume() } },
            ),
            ControlCase(
                phase = "claim",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = { sc, _ -> verify(exactly = 1) { sc.resumeClaim() } },
                verifyNotCalled = { _, ex -> verify(exactly = 0) { ex.resumeConsume() } },
            ),
            ControlCase(
                phase = "consume",
                action = "pause",
                expectClaimPaused = false,
                expectConsumePaused = true,
                verifyCall = { _, ex -> verify(exactly = 1) { ex.pauseConsume() } },
                verifyNotCalled = { sc, _ -> verify(exactly = 0) { sc.pauseClaim() } },
            ),
            ControlCase(
                phase = "consume",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = { _, ex -> verify(exactly = 1) { ex.resumeConsume() } },
                verifyNotCalled = { sc, _ -> verify(exactly = 0) { sc.resumeClaim() } },
            ),
            ControlCase(
                phase = "all",
                action = "pause",
                expectClaimPaused = true,
                expectConsumePaused = true,
                verifyCall = { sc, ex ->
                    verify(exactly = 1) { sc.pauseClaim() }
                    verify(exactly = 1) { ex.pauseConsume() }
                },
                verifyNotCalled = { _, _ -> /* both called — nothing to negate */ },
            ),
            ControlCase(
                phase = "all",
                action = "resume",
                expectClaimPaused = false,
                expectConsumePaused = false,
                verifyCall = { sc, ex ->
                    verify(exactly = 1) { sc.resumeClaim() }
                    verify(exactly = 1) { ex.resumeConsume() }
                },
                verifyNotCalled = { _, _ -> /* both called — nothing to negate */ },
            ),
        ).forEach { case ->
            "control phase=${case.phase} action=${case.action}" {
                val scanner = mockk<SchedulerScanner>(relaxed = true) {
                    every { isClaimPaused() } returns case.expectClaimPaused
                }
                val executor = mockk<ScheduledPromptExecutor>(relaxed = true) {
                    every { isConsumePaused() } returns case.expectConsumePaused
                }
                val result = endpoint(scanner, executor).control(
                    claimOrConsumeOrAllPhase = case.phase,
                    pauseOrResumeAction = case.action,
                )
                case.verifyCall(scanner, executor)
                case.verifyNotCalled(scanner, executor)
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
