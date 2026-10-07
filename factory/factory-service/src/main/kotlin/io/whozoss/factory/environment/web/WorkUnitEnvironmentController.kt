package io.whozoss.factory.environment.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.environment.domain.InvalidEnvironmentRequestException
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.service.EnvironmentInspection
import io.whozoss.factory.environment.service.ProvisionEnvironmentCommand
import io.whozoss.factory.environment.service.WorkUnitEnvironmentService
import io.whozoss.factory.error.UnauthenticatedException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Trusted Factory control-plane for work environments.
 *
 * Faithful port of `handleWorkUnitEnvironmentRequest` in
 * `factory/src/application/environment/work-unit-environment-controller.ts`:
 *
 *   * `GET    /api/factory/workflows/{workflowId}/environment`
 *   * `POST   /api/factory/workflows/{workflowId}/environment/provision`
 *   * `POST   /api/factory/workflows/{workflowId}/environment/reconcile`
 *   * `POST   /api/factory/workflows/{workflowId}/environment/release`
 *
 * The call is scoped by the verified [TrustContext] resolved at the HTTP
 * boundary (never client headers for tenant identity): the namespace/case of an
 * AgentOS execution context come from the trusted context, falling back to the
 * request body only for local/loopback development. Success responses use the
 * canonical `{ "data": ... }` envelope; failures are rendered by
 * [io.whozoss.factory.error.FactoryExceptionHandler] as
 * `{ "error": { "code", "message", "details" } }`.
 */
@RestController
@RequestMapping("/api/factory/workflows/{workflowId}/environment")
@Tag(name = "work-environments", description = "Work-unit environment provisioning control plane")
class WorkUnitEnvironmentController(
    private val service: WorkUnitEnvironmentService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Get the current environment of a workflow")
    fun getEnvironment(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): EnvironmentDataEnvelope<EnvironmentResponse> {
        val scope = requireScope(trustContext)
        return EnvironmentDataEnvelope(service.inspect(scope, workflowId).toResponse())
    }

    @PostMapping(
        path = ["/provision"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(summary = "Provision (or idempotently reuse) the workflow environment")
    fun provision(
        @PathVariable workflowId: String,
        @RequestBody(required = false) request: ProvisionEnvironmentRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<EnvironmentDataEnvelope<EnvironmentResponse>> {
        val scope = requireScope(trustContext)
        val workUnitId = request?.workUnitId?.takeIf { it.isNotBlank() }
            ?: throw InvalidEnvironmentRequestException("'workUnitId' is required")
        val branch = request.branch?.takeIf { it.isNotBlank() }
            ?: throw InvalidEnvironmentRequestException("'branch' is required")
        val integrationBranch = request.integrationBranch?.takeIf { it.isNotBlank() } ?: DEFAULT_INTEGRATION_BRANCH
        val namespaceId = trustContext?.namespaceId?.takeIf { it.isNotBlank() }
            ?: request.namespaceId?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAMESPACE
        val parentCaseId = trustContext?.caseId?.takeIf { it.isNotBlank() }
            ?: request.parentCaseId?.takeIf { it.isNotBlank() }
        val createdBy = trustContext?.principalId?.takeIf { it.isNotBlank() } ?: UNKNOWN_ACTOR

        val outcome = service.provision(
            scope = scope,
            command = ProvisionEnvironmentCommand(
                workflowId = workflowId,
                workUnitId = workUnitId,
                namespaceId = namespaceId,
                parentCaseId = parentCaseId,
                integrationBranch = integrationBranch,
                branch = branch,
                createdBy = createdBy,
                repoRoot = request.repoRoot,
            ),
        )
        val status = if (outcome.changed) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(
            EnvironmentDataEnvelope(
                EnvironmentInspection(
                    environment = outcome.environment,
                    reconciliation = null,
                    headCommit = null,
                ).toResponse(),
            ),
        )
    }

    @PostMapping(
        path = ["/reconcile"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(summary = "Reconcile the workflow environment against its Git worktree")
    fun reconcile(
        @PathVariable workflowId: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): EnvironmentDataEnvelope<EnvironmentResponse> {
        val scope = requireScope(trustContext)
        return EnvironmentDataEnvelope(service.inspect(scope, workflowId).toResponse())
    }

    @PostMapping(
        path = ["/release"],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(summary = "Release (decommission) the workflow environment")
    fun release(
        @PathVariable workflowId: String,
        @RequestBody(required = false) request: ReleaseEnvironmentRequest?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): EnvironmentDataEnvelope<EnvironmentResponse> {
        val scope = requireScope(trustContext)
        requireReleaseState(request?.state)
        val released = service.release(scope, workflowId, WorkEnvironmentState.DECOMMISSIONED)
        return EnvironmentDataEnvelope(released.toResponse())
    }

    private fun requireScope(trustContext: TrustContext?): TenantScope =
        tenantScopeProvider.scopeOf(trustContext) ?: throw UnauthenticatedException()

    private fun requireReleaseState(state: String?) {
        if (state == null) return
        if (state.lowercase() !in ACCEPTED_RELEASE_STATES) {
            throw InvalidEnvironmentRequestException(
                "Unsupported release state '$state'",
                details = mapOf("acceptedStates" to ACCEPTED_RELEASE_STATES.toList()),
            )
        }
    }

    private companion object {
        const val DEFAULT_INTEGRATION_BRANCH = "main"
        const val DEFAULT_NAMESPACE = "default"
        const val UNKNOWN_ACTOR = "unknown"

        /** `decommissioned` is the native vocabulary; `completed`/`abandoned` are accepted aliases. */
        val ACCEPTED_RELEASE_STATES = setOf("decommissioned", "completed", "abandoned")
    }
}
