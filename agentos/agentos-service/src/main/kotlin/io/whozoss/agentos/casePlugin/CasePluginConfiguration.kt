package io.whozoss.agentos.casePlugin

import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the CASE internal integration.
 *
 * Declared in a dedicated `@Configuration` class (mirroring
 * [io.whozoss.agentos.redirect.RedirectConfiguration]) to avoid a circular Spring
 * dependency. Crucially, this class injects [CaseRepository] and [CaseEventService]
 * directly rather than [io.whozoss.agentos.caseFlow.CaseService], because
 * `CaseServiceImpl` depends on `AgentService` which depends on `ToolRegistryService`
 * which collects all `ToolPlugin` beans — creating a cycle if `CaseService` were
 * injected here.
 *
 * The [caseEventsLoader] lambda:
 * 1. Loads the target [io.whozoss.agentos.caseFlow.Case] by id — returns null if absent.
 * 2. Checks that the case's [namespaceId] matches the caller's namespace — returns null
 *    if it does not (cross-namespace reads are not allowed).
 * 3. Returns the ordered list of [io.whozoss.agentos.sdk.caseEvent.CaseEvent]s for the case.
 */
@Configuration
class CasePluginConfiguration(
    private val caseRepository: CaseRepository,
    private val caseEventService: CaseEventService,
) {
    @Bean
    fun caseToolPlugin(): ToolPlugin =
        CaseToolPlugin { caseId, namespaceId ->
            val case = caseRepository.findByIds(listOf(caseId)).firstOrNull() ?: return@CaseToolPlugin null
            if (case.namespaceId != namespaceId) return@CaseToolPlugin null
            caseEventService.findByParent(caseId)
        }
}
