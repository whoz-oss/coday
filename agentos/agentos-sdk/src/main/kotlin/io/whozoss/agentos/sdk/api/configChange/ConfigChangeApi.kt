package io.whozoss.agentos.sdk.api.configChange

import java.util.UUID

/**
 * HTTP API contract for the config agent's change plans (#1477): a frozen plan proposed by an agent
 * is shown to its recipient, who applies or rejects it.
 *
 * Implemented by `ConfigChangeController` in agentos-service. External consumers implement this
 * interface as a Feign client, adding their own `@FeignClient` and routing annotations. AgentOS does
 * not prescribe the client technology or configuration.
 *
 * Authorization summary (enforced server-side, not expressed here):
 * - [viewConfigChange]: READ on the case; the current values are shown according to the viewer's own rights
 * - [applyConfigChange], [rejectConfigChange]: WRITE on the case, and only the user the plan was
 *   proposed to (403 otherwise)
 *
 * Authorization annotations (`@PreAuthorize`) and Spring MVC annotations are service-layer concerns
 * and are intentionally absent here.
 */
interface ConfigChangeApi {
    /** GET /api/cases/{caseId}/config-changes/{questionId} — view a plan with the live status of each operation. */
    fun viewConfigChange(
        caseId: UUID,
        questionId: UUID,
    ): ConfigChangeViewDto

    /**
     * POST /api/cases/{caseId}/config-changes/{questionId}/apply — apply the plan with the rights of the
     * calling user, then answer the agent.
     */
    fun applyConfigChange(
        caseId: UUID,
        questionId: UUID,
        request: ConfigChangeApplyRequest,
    ): ConfigChangeReportDto

    /**
     * POST /api/cases/{caseId}/config-changes/{questionId}/reject — reject the plan, then answer the agent.
     * Responds 204; the body is optional.
     */
    fun rejectConfigChange(
        caseId: UUID,
        questionId: UUID,
        request: ConfigChangeRejectRequest?,
    )
}
