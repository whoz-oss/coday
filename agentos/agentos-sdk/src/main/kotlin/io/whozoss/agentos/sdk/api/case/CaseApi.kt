package io.whozoss.agentos.sdk.api.case

import io.swagger.v3.oas.annotations.Operation
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

    @Operation(summary = "List cases by namespace", description = "GET /api/cases/by-parentId/{parentId} — list all cases belonging to a namespace.")
    fun listByParent(parentId: UUID): List<CaseDto>

    @Operation(summary = "List cases by user", description = "GET /api/cases/by-user/{userId} — list all cases concerning a specific user.")
    fun listByUser(userId: UUID): List<CaseDto>

    @Operation(summary = "List cases by user external ID", description = "GET /api/cases/by-user/external/{externalId} — list cases for a user identified by their IdP key.")
    fun listByUserExternalId(externalId: String): List<CaseDto>

    @Operation(
        summary = "List cases by user and namespace (external IDs)",
        description = "POST /api/cases/by-user/in-namespace — list cases for a user scoped to a namespace, both identified by their external IDs.",
    )
    fun listByUserInNamespace(request: ListByUserInNamespaceRequest): List<CaseDto>

    @Operation(summary = "Add a message", description = "POST /api/cases/{caseId}/messages — add a user message to a running case.")
    fun addMessage(caseId: UUID, request: AddMessageRequest)

    @Operation(summary = "Interrupt a case", description = "POST /api/cases/{caseId}/interrupt — interrupt the current agent turn gracefully.")
    fun interruptCase(caseId: UUID)

    @Operation(summary = "Kill a case", description = "POST /api/cases/{caseId}/kill — permanently terminate a case.")
    fun killCase(caseId: UUID)
}
