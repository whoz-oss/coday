package io.whozoss.factory.workflow.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.AdminGuard
import io.whozoss.factory.web.TrustContext
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.domain.workflowException
import io.whozoss.factory.workflow.service.WorkflowService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

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
    private val adminGuard: AdminGuard,
    private val objectMapper: ObjectMapper,
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
        adminGuard.requireAdminRole(trustContext)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        return registerParsedDefinition(caller, body)
    }

    @PostMapping(
        path = ["/upload"],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(summary = "Upload and register a workflow definition JSON file.")
    fun uploadDefinition(
        @RequestPart("file") file: MultipartFile,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
        adminGuard.requireAdminRole(trustContext)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        if (file.isEmpty) {
            throw workflowException(
                WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
                "Uploaded file is empty.",
            )
        }
        val body = try {
            objectMapper.readValue(file.inputStream, object : TypeReference<Map<String, Any?>>() {})
        } catch (ex: Exception) {
            throw workflowException(
                WorkflowErrorCodes.WORKFLOW_DEFINITION_INVALID,
                "Invalid JSON file format: ${ex.message ?: "unreadable content"}",
            )
        }
        return registerParsedDefinition(caller, body)
    }

    @DeleteMapping(path = ["/{workflowType}/{version}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(summary = "Delete a workflow definition by type and version.")
    fun deleteDefinition(
        @PathVariable workflowType: String,
        @PathVariable version: String,
        @Parameter(hidden = true) trustContext: TrustContext?,
    ): WorkflowDataEnvelope<Map<String, Any?>> {
        adminGuard.requireAdminRole(trustContext)
        val caller = resolveWorkflowCaller(trustContext, tenantScopeProvider, requireNamespace = false)
        val deleted = service.deleteDefinition(caller.scope, workflowType, version)
        if (!deleted) {
            throw workflowException(
                WorkflowErrorCodes.WORKFLOW_DEFINITION_NOT_FOUND,
                "Workflow definition was not found.",
            )
        }
        return WorkflowDataEnvelope(
            mapOf(
                "deleted" to true,
                "workflowType" to workflowType,
                "version" to version,
            ),
        )
    }

    /** Validates, canonicalizes and persists a parsed definition payload. */
    private fun registerParsedDefinition(
        caller: WorkflowCaller,
        body: Map<String, Any?>?,
    ): ResponseEntity<WorkflowDataEnvelope<Map<String, Any?>>> {
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
            definitionHash = hashWorkflowDefinition(valid.definition),
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
