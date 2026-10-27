package io.whozoss.agentos.delegation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.CaseRuntime
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The delegation budget of [awaitDelegationStatus] counts inactivity, sampled once a second. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DelegationInactivitySpec :
    StringSpec({
        "a sub-case that keeps showing activity is waited for well past the budget" {
            runTest {
                val status = MutableStateFlow(CaseStatus.RUNNING)
                var activity = 0L
                val runtime = mockk<CaseRuntime>()
                every { runtime.statusFlow } returns status
                every { runtime.activityCount } answers { activity }

                val result = async { awaitDelegationStatus(runtime, 1_000) { false } }
                repeat(10) {
                    advanceTimeBy(900)
                    activity++
                }

                result.isCompleted shouldBe false
                status.value = CaseStatus.IDLE
                runCurrent()
                result.await() shouldBe CaseStatus.IDLE
            }
        }

        "the budget runs out a full budget after the last activity seen" {
            runTest {
                var activity = 0L
                val runtime = mockk<CaseRuntime>()
                every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.RUNNING)
                every { runtime.activityCount } answers { activity }

                val result = async { awaitDelegationStatus(runtime, 1_000) { false } }
                advanceTimeBy(500)
                activity++
                // Seen by the sample at 1 000 ms, which restarts the budget.
                advanceTimeBy(1_400)
                result.isCompleted shouldBe false
                // The sample at 2 000 ms finds a full budget without activity.
                advanceTimeBy(200)
                result.await() shouldBe null
            }
        }

        "activity is reported to onActivity once per sample that sees it" {
            runTest {
                val status = MutableStateFlow(CaseStatus.RUNNING)
                var activity = 0L
                var relayed = 0
                val runtime = mockk<CaseRuntime>()
                every { runtime.statusFlow } returns status
                every { runtime.activityCount } answers { activity }

                val result = async { awaitDelegationStatus(runtime, 1_000, onActivity = { relayed++ }) { false } }
                advanceTimeBy(500)
                activity++
                advanceTimeBy(1_000)
                activity += 3
                advanceTimeBy(1_000)

                relayed shouldBe 2
                status.value = CaseStatus.IDLE
                runCurrent()
                result.await() shouldBe CaseStatus.IDLE
            }
        }
    })
