package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** The worker switch stops all Git background work, status polling included. */
class GitWorkspaceMonitorConditionSpec :
    StringSpec({
        val runner =
            ApplicationContextRunner()
                .withBean(CaseResourceBindingService::class.java, { mockk<CaseResourceBindingService>() })
                .withBean(GitExchangeRootResolver::class.java, { mockk<GitExchangeRootResolver>() })
                .withBean(GitWorkspaceStatusService::class.java, { mockk<GitWorkspaceStatusService>() })
                .withUserConfiguration(GitWorkspaceMonitor::class.java)

        listOf(
            Triple("true", "true", true),
            Triple("true", "false", false),
            Triple("false", "true", false),
        ).forEach { (workspaces, worker, expected) ->
            "the monitor is registered with workspaces=$workspaces and worker=$worker: $expected" {
                runner
                    .withPropertyValues("agentos.git.workspaces.enabled=$workspaces", "agentos.git.worker.enabled=$worker")
                    .run { context -> context.getBeansOfType(GitWorkspaceMonitor::class.java).isNotEmpty() shouldBe expected }
            }
        }

        "the monitor is not registered when neither flag is set" {
            runner.run { context -> context.getBeansOfType(GitWorkspaceMonitor::class.java).isEmpty() shouldBe true }
        }
    })
