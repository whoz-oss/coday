package io.whozoss.factory.workflow.web

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.service.WorkflowService
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
 * Read/write surface of the workflow definition registry.
 *
 * Port of `factory/dashboard/workflow-definition-routes.mjs`. Definitions are
 * tenant-scoped; the validated, canonical SHA-256 hash is computed server-side
 * and never trusted from the client.
 */
@RestController
@RequestMapping("/api/factory/workflow-definitions")
@Tag(name = "workflows", description = "Workflow definitions")
class WorkflowDefinitionController(
    private val service: WorkflowService,
    private val tenantScopeProvider: TenantScopeProvider,
) {

    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "List registered workflow definitions.")
    fun list(
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        return WorkflowDataEnvelope(service.listDefinitions(caller.scope))
    }

    @GetMapping(path = ["/{workflowType}/{version}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Get one workflow definition by type and version.")
    fun detail(
        @PathVariable workflowType: String,
        @PathVariable version: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        val definition = service.getDefinition(caller.scope, workflowType, version)
            ?: throw workflowException(WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND, "Workflow definition was not found.")
        return WorkflowDataEnvelope(definition)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Register or update a workflow definition.")
    fun register(
        @RequestBody(required = false) body: Map<String, Any?>?,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        val validation = WorkflowDefinitionValidator.validate(body)
        if (validation is WorkflowDefinitionValidation.Invalid) {
            throw workflowException(
                validation.error.code,
                "Workflow definition is invalid.",
                validation.error.details + mapOf("path" to validation.error.path),
            )
        }
        val valid = validation as WorkflowDefinitionValidation.Valid
        val record = WorkflowDefinitionRecord(
            workflowType = valid.definition["workflowType"] as String,
            version = valid.definition["version"] as String,
            definitionHash = io.whozoss.factory.workflow.domain.hashWorkflowDefinition(valid.definition),
            definition = valid.definition,
        )
        service.registerDefinition(caller.scope, record)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            WorkflowDataEnvelope(
                mapOf(
                    "workflowType" to record.workflowType,
                    "version" to record.version,
                    "definitionHash" to record.definitionHash,
                ),
            ),
        )
    }
}
