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
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [Case] entities. Implements [CaseApi] so external consumers
 * (e.g. whoz Copilot) can declare a Feign client against the SDK interface.
 *
 * Standard CRUD operations are delegated to [crud] explicitly by name.
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

    private val crud = EntityCrudDelegate<CaseDto>(
        service = caseService,
        userService = userService,
        permissions = permissionService,
        entityType = EntityType.CASE,
        toResource = { (it as Case).toDto() },
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
    override fun getById(@PathVariable id: UUID): CaseDto = crud.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody request: SdkGetByIdsRequest): List<CaseDto> =
        crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<CaseDto> {
        val user = userService.getCurrentUser()
        val isNamespaceAdmin = permissionService.hasPermission(
            user.id.toString(), EntityType.NAMESPACE, parentId.toString(), Action.WRITE,
        )
        return if (isNamespaceAdmin) {
            caseService.findByParent(parentId).map { it.toDto() }
        } else {
            caseService.findAccessibleByUserInNamespace(user.id, parentId).map { it.toDto() }
        }
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'READ')")
    override fun create(@Valid @RequestBody resource: CaseDto): CaseDto {
        val created = crud.create(resource)
        val caseId = created.id ?: error("Created case must have an id")
        val userId = userService.getCurrentUser().id.toString()
        runCatching {
            permissionService.grantPermission(userId, EntityType.CASE, caseId.toString(), PermissionRelation.ADMIN)
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
    override fun update(@PathVariable id: UUID, @Valid @RequestBody resource: CaseDto): CaseDto {
        val existing = caseService.findById(id) ?: throw ResourceNotFoundException("Case not found: $id")
        return caseService.update(existing.copy(title = resource.title ?: existing.title)).toDto()
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Case', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = crud.delete(id)

    @GetMapping("/by-user/{userId}")
    override fun listByUser(@PathVariable userId: UUID): List<CaseDto> {
        logger.debug { "Listing cases for user $userId" }
        return caseService.findConcerningUser(userId).map { it.toDto() }
    }

    @GetMapping("/by-user/external/{externalId}")
    override fun listByUserExternalId(@PathVariable externalId: String): List<CaseDto> {
        val user = userService.findByExternalId(externalId)
            ?: throw ResourceNotFoundException("User not found: $externalId")
        return caseService.findConcerningUser(user.id).map { it.toDto() }
    }

    @PostMapping("/by-user/in-namespace", consumes = [MediaType.APPLICATION_JSON_VALUE])
    override fun listByUserInNamespace(@RequestBody request: ListByUserInNamespaceRequest): List<CaseDto> {
        val user = userService.findByExternalId(request.userExternalId)
            ?: throw ResourceNotFoundException("User not found: ${request.userExternalId}")
        val namespace = namespaceService.findByExternalId(request.namespaceExternalId)
            ?: throw ResourceNotFoundException("Namespace not found: ${request.namespaceExternalId}")
        return caseService.findConcerningUserInNamespace(user.id, namespace.id).map { it.toDto() }
    }

    @PostMapping("/{caseId}/messages", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun addMessage(@PathVariable caseId: UUID, @RequestBody request: AddMessageRequest) {
        val user = userService.getCurrentUser()
        val displayName = listOfNotNull(user.firstname, user.lastname)
            .joinToString(" ").ifBlank { user.metadata.id.toString() }
        caseService.addMessage(
            caseId = caseId,
            actor = Actor(id = user.metadata.id.toString(), displayName = displayName, role = ActorRole.USER),
            content = listOf(MessageContent.Text(request.content)),
            answerToEventId = request.answerToEventId,
            sessionContext = request.sessionContext,
        )
    }

    @PostMapping("/{caseId}/interrupt")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun interruptCase(@PathVariable caseId: UUID) = caseService.interruptCase(caseId)

    @PostMapping("/{caseId}/kill")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'DELETE')")
    override fun killCase(@PathVariable caseId: UUID) = caseService.killCase(caseId)

    companion object : KLogging()
}

// ---------------------------------------------------------------------------
// Extension: Case → CaseDto
// ---------------------------------------------------------------------------

fun Case.toDto() = CaseDto(
    id = metadata.id,
    namespaceId = namespaceId,
    status = status,
    title = title,
    created = metadata.created,
    removed = metadata.removed,
)
