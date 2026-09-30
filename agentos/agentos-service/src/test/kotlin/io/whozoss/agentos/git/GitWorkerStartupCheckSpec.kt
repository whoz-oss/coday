package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider

class GitWorkerStartupCheckSpec :
    StringSpec({
        fun check(gitAvailable: Boolean, workerEnabled: Boolean): GitWorkerStartupCheck {
            val availability = mockk<GitAvailability> { every { isAvailable() } returns gitAvailable }
            val worker = mockk<ObjectProvider<CaseWorkspaceWorker>> {
                every { getIfAvailable() } returns if (workerEnabled) mockk() else null
            }
            return GitWorkerStartupCheck(availability, worker)
        }

        "Git without its worker is reported: nothing would ever be cloned" {
            check(gitAvailable = true, workerEnabled = false).workerMissing() shouldBe true
        }

        "no report when the worker runs or when Git is not available" {
            check(gitAvailable = true, workerEnabled = true).workerMissing() shouldBe false
            check(gitAvailable = false, workerEnabled = false).workerMissing() shouldBe false
        }
    })
