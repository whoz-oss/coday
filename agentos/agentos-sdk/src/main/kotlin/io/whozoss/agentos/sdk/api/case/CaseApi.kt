package io.whozoss.agentos.sdk.api.case

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for Case entities.
 *
 * Implemented by `CaseController` in agentos-service. External consumers (e.g. the whoz
 * Copilot module) implement this interface as a Feign client, adding their own
 * `@FeignClient` and routing annotations. AgentOS does not prescribe the client
 * technology or configuration.
 *
 * Authorization annotations (`@PreAuthorize`) and request-validation annotations
 * (`@Valid`) are service-layer concerns and are intentionally absent here.
 */
interface CaseApi : EntityCrudApi<CaseDto> {

    fun listByParent(parentId: UUID): List<CaseDto>

    /** GET /api/cases/by-user/{userId} — list all cases concerning a specific user. */
    fun listByUser(userId: UUID): List<CaseDto>

    /** GET /api/cases/by-user/external/{externalId} — list cases for a user identified by their IdP key. */
    fun listByUserExternalId(externalId: String): List<CaseDto>

    /**
     * POST /api/cases/by-user/in-namespace — list cases for a user scoped to a namespace,
     * both identified by their external IDs.
     */
    fun listByUserInNamespace(request: ListByUserInNamespaceRequest): List<CaseDto>

    /** POST /api/cases/{caseId}/messages — add a user message to a running case. */
    fun addMessage(caseId: UUID, request: AddMessageRequest)

    /** POST /api/cases/{caseId}/interrupt — interrupt the current agent turn. */
    fun interruptCase(caseId: UUID)

    /** POST /api/cases/{caseId}/kill — permanently terminate a case. */
    fun killCase(caseId: UUID)
}
