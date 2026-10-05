package io.whozoss.agentos.git

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * `agentos.git.workspaces.enabled` is the single switch of the Git workspace feature: with it off,
 * an instance must carry none of its beans. [GitWorkspaceMonitorConditionSpec] covers the monitor,
 * which additionally follows the worker switch.
 */
class GitWorkspaceBeanConditionSpec :
    StringSpec({
        val contexts =
            ApplicationContextRunner()
                .withBean(CaseResourceBindingService::class.java, { mockk<CaseResourceBindingService>() })
                .withBean(GitRepositoryAssociationService::class.java, { mockk<GitRepositoryAssociationService>() })
                .withBean(ExchangeStorageService::class.java, { mockk<ExchangeStorageService>() })
                .withBean(GitCommandRunner::class.java, { mockk<GitCommandRunner>() })
                .withBean(GitServiceAccountResolver::class.java, { mockk<GitServiceAccountResolver>() })
                .withBean(ObjectMapper::class.java, { jacksonObjectMapper().findAndRegisterModules() })
                .withUserConfiguration(GitWorkspaceStatusService::class.java, GitHubPullRequests::class.java)

        listOf("true" to true, "false" to false).forEach { (enabled, expected) ->
            "the status service and its GitHub adapter are registered with workspaces=$enabled: $expected" {
                contexts
                    .withPropertyValues("agentos.git.workspaces.enabled=$enabled")
                    .run { context ->
                        context.getBeansOfType(GitWorkspaceStatusService::class.java).isNotEmpty() shouldBe expected
                        context.getBeansOfType(GitHubPullRequests::class.java).isNotEmpty() shouldBe expected
                    }
            }
        }

        "neither bean is registered when the property is absent" {
            contexts.run { context ->
                context.getBeansOfType(GitWorkspaceStatusService::class.java).isEmpty() shouldBe true
                context.getBeansOfType(GitHostingProvider::class.java).isEmpty() shouldBe true
            }
        }

        "exactly one adapter satisfies the hosting port the status service depends on" {
            contexts
                .withPropertyValues("agentos.git.workspaces.enabled=true")
                .run { context ->
                    // More than one would make the single-argument injection ambiguous at startup.
                    context.getBeansOfType(GitHostingProvider::class.java).size shouldBe 1
                    context.startupFailure shouldBe null
                }
        }
    })
