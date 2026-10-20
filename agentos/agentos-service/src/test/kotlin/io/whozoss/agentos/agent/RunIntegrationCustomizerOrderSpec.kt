package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.exchange.ExchangeCapabilityService
import io.whozoss.agentos.exchange.ExchangePathRunIntegration
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.CaseResourceBindingService
import io.whozoss.agentos.git.GitExchangeRootResolver
import io.whozoss.agentos.git.GitToolsRunIntegration
import io.whozoss.agentos.caseFlow.CaseRepository
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The fold in [AgentServiceImpl] hands each [RunIntegrationCustomizer] what the previous returned,
 * so their order is part of the contract rather than an accident of the bean scan.
 *
 * It is observable for one combination: a `GIT` config carrying an exchange token on a family whose
 * workspace is not usable yet. [GitToolsRunIntegration] raises a `ConflictException` from
 * `requireUsable()` there, the answer that predates the tokens, while [ExchangePathRunIntegration]
 * drops the config and lets the resolution succeed. Git first keeps the older answer, and this spec
 * is what notices a bean losing its [org.springframework.core.annotation.Order].
 */
class RunIntegrationCustomizerOrderSpec :
    StringSpec({
        val contexts =
            ApplicationContextRunner()
                .withBean(CaseRepository::class.java, { mockk<CaseRepository>() })
                .withBean(CaseResourceBindingService::class.java, { mockk<CaseResourceBindingService>() })
                .withBean(ExchangeStorageService::class.java, { mockk<ExchangeStorageService>() })
                .withBean(ExchangeCapabilityService::class.java, { mockk<ExchangeCapabilityService>() })
                .withBean(ExchangeRootResolver::class.java, { mockk<ExchangeRootResolver>() })
                .withUserConfiguration(
                    GitExchangeRootResolver::class.java,
                    GitToolsRunIntegration::class.java,
                    ExchangePathRunIntegration::class.java,
                )

        "the Git customizer runs before the exchange one" {
            contexts
                .withPropertyValues("agentos.git.workspaces.enabled=true")
                .run { context ->
                    context.startupFailure shouldBe null
                    context
                        .getBeanProvider(RunIntegrationCustomizer::class.java)
                        .orderedStream()
                        .map { it::class }
                        .toList() shouldBe
                        listOf(GitToolsRunIntegration::class, ExchangePathRunIntegration::class)
                }
        }

        "the exchange one is the only customizer without Git workspaces" {
            contexts.run { context ->
                context
                    .getBeanProvider(RunIntegrationCustomizer::class.java)
                    .orderedStream()
                    .map { it::class }
                    .toList() shouldBe listOf(ExchangePathRunIntegration::class)
            }
        }
    })
