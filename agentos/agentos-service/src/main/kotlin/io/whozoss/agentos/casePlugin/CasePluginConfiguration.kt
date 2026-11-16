package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the CASE internal integration ([CaseToolPlugin]: `ReadCase`,
 * `FreeCaseStarter` and `FreeCaseStarterForUser`).
 *
 * Declared in a dedicated `@Configuration` class (mirroring
 * [io.whozoss.agentos.redirect.RedirectConfiguration] and
 * [io.whozoss.agentos.userGroup.UserGroupToolPluginConfiguration]) to avoid a circular Spring
 * dependency: `ToolRegistryService` collects every `ToolPlugin` bean, and `CaseService` reaches it
 * through `AgentService`.
 *
 * All functional logic lives in services, injected here and wired into the plugin. This class contains no
 * business logic, it only binds dependencies:
 * - [CaseToolService]: the read side (`ReadCase`): existence, namespace isolation and Case READ. It injects
 *   repositories rather than `CaseService`, which would close the cycle.
 * - [CaseStarterService]: the start side. It is safe to inject: it reaches `CaseService` through a `@Lazy`
 *   proxy.
 */
@Configuration
class CasePluginConfiguration(
    private val caseToolService: CaseToolService,
    private val caseStarterService: CaseStarterService,
) {
    @Bean
    fun caseToolPlugin(): ToolPlugin =
        CaseToolPlugin(
            caseEventsLoader = caseToolService::findCaseEvents,
            caseStarterService = caseStarterService,
        )
}
