package io.whozoss.agentos.scheduledTask

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing [CaseDefinition] entities.
 *
 * All endpoints live under `/api/case-definitions` with `namespaceId` as a query parameter.
 *
 * ### Query param vs. namespaceId in body
 *
 * `?namespaceId=` serves routing and authorization. The controller validates that the
 * `namespaceId` field in the request body matches the query param (on create/update).
 *
 * **Step 1 — purely declarative CRUD.** No scheduler, no @Scheduled, no trigger logic.
 */
@RestController
@RequestMapping(
    "/api/case-definitions",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
@Tag(name = "case-definitions", description = "Declarative case definition management")
class CaseDefinitionController(
    private val caseDefinitionService: CaseDefinitionService,
) {
    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private fun toResource(def: CaseDefinition): CaseDefinitionResource {
        val cronSchedule = CronExpressionConverter.fromCron(def.cronExpression)
        return CaseDefinitionResource(
            id = def.metadata.id,
            namespaceId = def.namespaceId,
            userGroupId = def.userGroupId,
            userId = def.userId,
            name = def.name,
            description = def.description,
            agentId = def.agentId,
            prompt = def.prompt,
            schedule = TaskScheduleResource(
                frequency = cronSchedule.frequency,
                timeUtc = cronSchedule.timeUtc,
                dayOfWeek = cronSchedule.dayOfWeek,
            ),
            enabled = def.enabled,
            createdAt = def.metadata.created,
            updatedAt = def.metadata.modified,
        )
    }

    private fun toDomain(namespaceId: UUID, resource: CaseDefinitionResource): CaseDefinition =
        CaseDefinition(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = namespaceId,
            userGroupId = resource.userGroupId,
            userId = resource.userId,
            name = resource.name,
            description = resource.description,
            agentId = resource.agentId,
            prompt = resource.prompt,
            cronExpression = CronExpressionConverter.toCron(
                frequency = resource.schedule.frequency,
                dayOfWeek = resource.schedule.dayOfWeek,
                timeUtc = resource.schedule.timeUtc,
            ),
            enabled = resource.enabled,
        )

    private fun requireNamespaceId(namespaceId: UUID?): UUID =
        namespaceId
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "namespaceId query parameter is required",
            )

    /**
     * Validates that `namespaceId` in the body matches the `?namespaceId=` query param.
     * Throws 400 on mismatch.
     */
    private fun requireNamespaceMatch(resource: CaseDefinitionResource, namespaceId: UUID) {
        if (resource.namespaceId != namespaceId)
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "namespaceId in body (${resource.namespaceId}) " +
                    "must match the namespaceId query param ($namespaceId)",
            )
    }

    /**
     * Verifies that the persisted definition belongs to the requested namespace.
     * Throws 404 (not 403) to avoid leaking information about cross-namespace definitions.
     */
    private fun requireDefinitionInNamespace(def: CaseDefinition, namespaceId: UUID) {
        if (def.namespaceId != namespaceId)
            throw ResourceNotFoundException("CaseDefinition not found: ${def.id}")
    }

    // -------------------------------------------------------------------------
    // Endpoints
    // -------------------------------------------------------------------------

    /** GET /api/case-definitions?namespaceId= */
    @GetMapping
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @Operation(summary = "List case definitions for a namespace")
    fun list(@RequestParam(required = false) namespaceId: UUID?): List<CaseDefinitionResource> {
        val nsId = requireNamespaceId(namespaceId)
        return caseDefinitionService.findByParent(nsId).map { toResource(it) }
    }

    /** GET /api/case-definitions/{id}?namespaceId= */
    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @Operation(summary = "Get a case definition by id")
    fun getById(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID?,
    ): CaseDefinitionResource {
        val nsId = requireNamespaceId(namespaceId)
        val def = caseDefinitionService.findById(id, withRemoved = true)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        requireDefinitionInNamespace(def, nsId)
        return toResource(def)
    }

    /** POST /api/case-definitions?namespaceId= */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @Operation(summary = "Create a case definition")
    fun create(
        @RequestParam(required = false) namespaceId: UUID?,
        @Valid @RequestBody resource: CaseDefinitionResource,
    ): CaseDefinitionResource {
        val nsId = requireNamespaceId(namespaceId)
        requireNamespaceMatch(resource, nsId)
        logger.info { "[CaseDefinition] Creating '${resource.name}' ns=$nsId" }
        return toResource(caseDefinitionService.create(toDomain(nsId, resource)))
    }

    /** PUT /api/case-definitions/{id}?namespaceId= */
    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @Operation(summary = "Update a case definition")
    fun update(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID?,
        @Valid @RequestBody resource: CaseDefinitionResource,
    ): CaseDefinitionResource {
        val nsId = requireNamespaceId(namespaceId)
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        requireDefinitionInNamespace(existing, nsId)
        requireNamespaceMatch(resource, nsId)
        val updated = existing.copy(
            name = resource.name,
            description = resource.description,
            agentId = resource.agentId,
            prompt = resource.prompt,
            cronExpression = CronExpressionConverter.toCron(
                frequency = resource.schedule.frequency,
                dayOfWeek = resource.schedule.dayOfWeek,
                timeUtc = resource.schedule.timeUtc,
            ),
            enabled = resource.enabled,
        )
        return toResource(caseDefinitionService.update(updated))
    }

    /** PATCH /api/case-definitions/{id}/toggle?namespaceId= */
    @PatchMapping("/{id}/toggle")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @Operation(summary = "Toggle a case definition enabled/disabled")
    fun toggle(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID?,
    ): CaseDefinitionResource {
        val nsId = requireNamespaceId(namespaceId)
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        requireDefinitionInNamespace(existing, nsId)
        return toResource(caseDefinitionService.setEnabled(id, !existing.enabled))
    }

    /** DELETE /api/case-definitions/{id}?namespaceId= */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    @Operation(summary = "Delete a case definition")
    fun delete(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID?,
    ) {
        val nsId = requireNamespaceId(namespaceId)
        val existing = caseDefinitionService.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        requireDefinitionInNamespace(existing, nsId)
        caseDefinitionService.delete(id)
    }

    companion object : KLogging()
}
