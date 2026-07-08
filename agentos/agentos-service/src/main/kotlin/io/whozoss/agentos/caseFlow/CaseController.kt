package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.api.case.AddMessageRequest
import io.whozoss.agentos.sdk.api.case.CaseApi
import io.whozoss.agentos.sdk.api.case.CaseDto
import io.whozoss.agentos.sdk.api.case.ListByUserInNamespaceRequest
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for managing [Case] entities. Implements [CaseApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Authorization declared via `@PreAuthorize`:
 * - READ on Case: namespace ADMIN (transitive) OR direct ADMIN/MEMBER on the case (FR15)
 * - WRITE/DELETE on Case: same permission model
 * - CREATE: namespace **READ** (FR — a MEMBER can create their own case;
 *   the ADMIN/MEMBER grant on the new case is auto-applied in the body)
 *
 * [userService] and [permissionService] are used for two non-authorization concerns:
 * (1) the auto-ADMIN grant on the new case after create, (2) the fast-path branch in
 * [listByParent] (namespace ADMIN → unfiltered listing).
 */
@RestController
@RequestMapping(
    "/api/cases",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class CaseController(
    private val caseService: CaseService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : CaseApi {

    private val crud = EntityCrudDelegate(
        service = caseService,
        userService = userService,
        permissions = permissionService,
        entityType = EntityType.CASE,
        toResource = { toDto(it as Case) },
        toDomain = { resource ->
            val metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID())
            Case(
                metadata = metadata,
                namespaceId = resource.namespaceId,
                status = resource.status,
                title = resource.title ?: "Case ${metadata.id}",
            )
        },
    )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): CaseDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<CaseDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    /**
     * GET /api/cases/by-parentId/{parentId} — list cases in a namespace.
     *
     * Two performance paths:
     * 1. Namespace ADMIN → unfiltered listing (single query, no per-case check).
     * 2. Otherwise → permission-filtered listing via [CaseService.findAccessibleByUserInNamespace]
     *    which respects FR15 (MEMBERs see only their own cases or those they were directly
     *    granted READ on).
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<CaseDto> {
        val user = userService.getCurrentUser()
        val userId = user.id.toString()
        val isNamespaceAdmin = permissionService.hasPermission(
            userId, EntityType.NAMESPACE, parentId.toString(), Action.WRITE,
        )
        return if (isNamespaceAdmin) {
            logger.debug { "User $userId is namespace-ADMIN on $parentId — short-circuit list (no filtering)" }
            caseService.findByParent(parentId).map(::toDto)
        } else {
            logger.debug { "User $userId not namespace-ADMIN on $parentId — using permission-filtered listing" }
            caseService.findAccessibleByUserInNamespace(user.id, parentId).map(::toDto)
        }
    }

    /**
     * POST /api/cases — create a Case. Permission gate is namespace READ (a MEMBER may
     * create their own cases). After persistence, auto-grants ADMIN on the new case to
     * the creator (best-effort, non-transactional).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'READ')")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: CaseDto,
    ): CaseDto {
        val created = crud.create(resource)
        val caseId = created.id ?: error("Created case must have an id")
        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(
                userId,
                EntityType.CASE,
                caseId.toString(),
                PermissionRelation.ADMIN,
            )
            logger.info { "User $userId created case $caseId with auto-ADMIN grant" }
        }.onFailure { e ->
            logger.warn(e) {
                "Auto-ADMIN grant failed for case $caseId (user $userId) — case persisted. " +
                    "Recovery: a super-admin or namespace ADMIN must grant ADMIN on the case manually."
            }
        }
        return created
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Case', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: CaseDto,
    ): CaseDto {
        val existing = caseService.findById(id)
            ?: throw ResourceNotFoundException("Case not found: $id")
        return toDto(
            caseService.update(
                existing.copy(
                    // namespaceId and status are mass-assignment-guarded:
                    // namespaceId is the transitivity key for permissions;
                    // status is driven by the runtime lifecycle, not PUT.
                    title = resource.title ?: existing.title,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'DELETE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    @GetMapping("/by-user/{userId}")
    override fun listByUser(
        @PathVariable userId: UUID,
    ): List<CaseDto> {
        logger.debug { "Listing cases for user $userId" }
        return caseService.findConcerningUser(userId).map(::toDto)
    }

    @GetMapping("/by-user/external/{externalId}")
    override fun listByUserExternalId(
        @PathVariable externalId: String,
    ): List<CaseDto> {
        val user = userService.findByExternalId(externalId)
            ?: throw ResourceNotFoundException("User not found: $externalId")
        logger.debug { "Listing cases for user ${user.id} (externalId=$externalId)" }
        return caseService.findConcerningUser(user.id).map(::toDto)
    }

    @PostMapping("/by-user/in-namespace", consumes = [MediaType.APPLICATION_JSON_VALUE])
    override fun listByUserInNamespace(
        @RequestBody request: ListByUserInNamespaceRequest,
    ): List<CaseDto> {
        val user = userService.findByExternalId(request.userExternalId)
            ?: throw ResourceNotFoundException("User not found: ${request.userExternalId}")
        val namespace = namespaceService.findByExternalId(request.namespaceExternalId)
            ?: throw ResourceNotFoundException("Namespace not found: ${request.namespaceExternalId}")
        logger.debug { "Listing cases for user ${user.id} in namespace ${namespace.id}" }
        return caseService.findConcerningUserInNamespace(user.id, namespace.id).map(::toDto)
    }

    @PostMapping("/{caseId}/messages")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun addMessage(
        @PathVariable caseId: UUID,
        @RequestBody request: AddMessageRequest,
    ) {
        val user = userService.getCurrentUser()
        logger.info { "Adding message to case: $caseId" }
        val displayName = listOfNotNull(user.firstname, user.lastname)
            .joinToString(" ")
            .ifBlank { user.metadata.id.toString() }
        val userActor = Actor(id = user.metadata.id.toString(), displayName = displayName, role = ActorRole.USER)
        caseService.addMessage(
            caseId = caseId,
            actor = userActor,
            content = listOf(MessageContent.Text(request.content)),
            answerToEventId = request.answerToEventId,
            sessionContext = request.sessionContext,
        )
        logger.info { "Message added to case: $caseId" }
    }

    @PostMapping("/{caseId}/interrupt")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun interruptCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Interrupting case: $caseId" }
        caseService.interruptCase(caseId)
        logger.info { "Case interrupted: $caseId" }
    }

    @PostMapping("/{caseId}/kill")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'DELETE')")
    override fun killCase(
        @PathVariable caseId: UUID,
    ) {
        logger.info { "Killing case: $caseId" }
        caseService.killCase(caseId)
        logger.info { "Case killed: $caseId" }
    }

    companion object : KLogging()
}

internal fun toDto(entity: Case) =
    CaseDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        status = entity.status,
        title = entity.title,
        parentCaseId = entity.parentCaseId,
        created = entity.metadata.created,
        removed = entity.metadata.removed,
    )
