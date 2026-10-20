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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CostPausedDelegationSpec :
    StringSpec({
        "human confirmation outlives the execution deadline and resumes the same delegation" {
            runTest {
                val status = MutableStateFlow(CaseStatus.RUNNING)
                val runtime = mockk<CaseRuntime>()
                every { runtime.statusFlow } returns status
                var paused = true
                val result = async { awaitDelegationStatus(runtime, 1000) { paused } }
                advanceTimeBy(10_000)
                result.isCompleted shouldBe false
                paused = false
                status.value = CaseStatus.IDLE
                runCurrent()
                result.await() shouldBe CaseStatus.IDLE
            }
        }

        "active execution still expires when no confirmation is pending" {
            runTest {
                val runtime = mockk<CaseRuntime>()
                every { runtime.statusFlow } returns MutableStateFlow(CaseStatus.RUNNING)
                val result = async { awaitDelegationStatus(runtime, 1000) { false } }
                advanceTimeBy(1001)
                result.await() shouldBe null
            }
        }
    })
