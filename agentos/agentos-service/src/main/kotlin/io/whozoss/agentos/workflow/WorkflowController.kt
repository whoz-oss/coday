package io.whozoss.agentos.workflow

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.workflow.WorkflowDto
import io.whozoss.agentos.sdk.api.workflow.WorkflowStepDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for [Workflow] definitions at /api/workflows.
 *
 * Authorization follows the workflow's scope, evaluated here because workflows carry no
 * permission edge of their own:
 *
 * | scope     | read                    | create / update / delete |
 * |-----------|-------------------------|--------------------------|
 * | platform  | any authenticated user  | Super Admin              |
 * | namespace | READ on the namespace   | WRITE on the namespace   |
 *
 * A workflow the caller may not read is reported as not found (404), not forbidden, so ids of
 * other namespaces cannot be probed. `namespaceId` is immutable on PUT.
 */
@RestController
@RequestMapping("/api/workflows", produces = [MediaType.APPLICATION_JSON_VALUE])
class WorkflowController(
    private val workflowService: WorkflowService,
    private val namespaceService: NamespaceService,
    private val permissionService: PermissionService,
    private val userService: UserService,
) {
    @Operation(
        summary = "List workflows",
        description =
            "With `namespaceId`: the workflows usable in that namespace (its own, then the platform ones); " +
                "requires READ on the namespace. Without: the platform workflows.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: UUID?,
    ): List<WorkflowDto> {
        if (namespaceId == null) return workflowService.findPlatform().map(::toDto)
        requireNamespace(namespaceId, Action.READ)
        return workflowService.findAvailable(namespaceId).map(::toDto)
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    fun getById(
        @PathVariable id: UUID,
    ): WorkflowDto = toDto(findReadable(id))

    @Operation(summary = "Create a workflow", description = "`namespaceId` null creates a platform workflow (Super Admin only).")
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody resource: WorkflowDto,
    ): WorkflowDto {
        requireWrite(resource.namespaceId)
        if (resource.namespaceId != null && namespaceService.findById(resource.namespaceId) == null) {
            throw ResourceNotFoundException("Namespace not found: ${resource.namespaceId}")
        }
        val workflow =
            Workflow(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resource.namespaceId,
                name = resource.name,
                description = resource.description,
                version = resource.version,
                steps = resource.steps.map { it.toDomain() },
            )
        return toDto(workflowService.create(workflow))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: WorkflowDto,
    ): WorkflowDto {
        val existing = findReadable(id)
        requireWrite(existing.namespaceId)
        if (resource.namespaceId != existing.namespaceId) throw BadRequestException("namespaceId is immutable")
        val updated =
            existing.copy(
                name = resource.name,
                description = resource.description,
                version = resource.version,
                steps = resource.steps.map { it.toDomain() },
            )
        return toDto(workflowService.update(updated))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val existing = findReadable(id)
        requireWrite(existing.namespaceId)
        workflowService.delete(id)
    }

    /** The workflow, or 404 when it does not exist or the caller may not read it. */
    private fun findReadable(id: UUID): Workflow {
        val workflow = workflowService.findById(id) ?: throw ResourceNotFoundException("Workflow not found: $id")
        val namespaceId = workflow.namespaceId ?: return workflow
        if (!hasNamespace(namespaceId, Action.READ)) throw ResourceNotFoundException("Workflow not found: $id")
        return workflow
    }

    private fun requireWrite(namespaceId: UUID?) {
        when (namespaceId) {
            null -> if (!userService.getCurrentUser().isAdmin) throw AccessDeniedException("Platform workflows require Super Admin")
            else -> requireNamespace(namespaceId, Action.WRITE)
        }
    }

    private fun requireNamespace(
        namespaceId: UUID,
        action: Action,
    ) {
        if (!hasNamespace(namespaceId, action)) {
            throw AccessDeniedException("${action.name} on namespace $namespaceId is required")
        }
    }

    private fun hasNamespace(
        namespaceId: UUID,
        action: Action,
    ): Boolean =
        permissionService.hasPermission(
            userId = userService.getCurrentUser().id.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = namespaceId.toString(),
            action = action,
        )
}

internal fun WorkflowStepDto.toDomain(): LoopStep = LoopStep(id = id, tool = tool, input = LoopStepInput(params = params, from = from))

internal fun toDto(workflow: Workflow): WorkflowDto =
    WorkflowDto(
        id = workflow.id,
        namespaceId = workflow.namespaceId,
        name = workflow.name,
        description = workflow.description,
        version = workflow.version,
        steps = workflow.steps.map { WorkflowStepDto(id = it.id, tool = it.tool, params = it.input.params, from = it.input.from) },
        createdBy = workflow.metadata.createdBy,
        createdOn = workflow.metadata.created,
        updatedBy = workflow.metadata.modifiedBy,
        updatedOn = workflow.metadata.modified,
    )
