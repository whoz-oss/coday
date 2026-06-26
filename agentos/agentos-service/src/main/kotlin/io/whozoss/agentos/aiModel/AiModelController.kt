package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
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

/**
 * REST API for managing [AiModel] entities.
 *
 * Authorization declared via `@PreAuthorize`. Because permission depends on the parent
 * AiProvider's namespace (and the AiModel's own namespaceId is denormalised at creation
 * by the service), `create` and `listByParent` use [AiModelGuard] which encapsulates
 * the parent lookup. Single-entity operations (READ/PUT/DELETE) use direct permission
 * checks against `'AiModel'` since the entity's `[:BELONGS_TO]` edge is wired up by
 * the repository at save time.
 */
@RestController
@RequestMapping(
    "/api/ai-models",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiModelController(
    private val aiModelService: AiModelService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<AiModel, UUID, AiModelResource>(aiModelService, userService, permissionService) {

    override val entityType = EntityType.AI_MODEL

    override fun toResource(entity: AiModel): AiModelResource =
        AiModelResource(
            id = entity.metadata.id,
            aiProviderId = entity.aiProviderId,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            apiModelName = entity.apiModelName,
            description = entity.description,
            alias = entity.alias,
            priority = entity.priority,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens,
        )

    /**
     * Convert a resource to a domain entity for **create** only. [namespaceId] and
     * [userId] are server-resolved from the parent AiProvider by `AiModelServiceImpl.create`.
     */
    override fun toDomain(resource: AiModelResource): AiModel =
        AiModel(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            aiProviderId = resource.aiProviderId!!,
            namespaceId = null,
            userId = resource.userId,
            apiModelName = resource.apiModelName,
            description = resource.description,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    /**
     * Merge an update resource onto an existing persisted entity. Server-owned fields
     * ([AiModel.namespaceId], [AiModel.userId], [AiModel.aiProviderId]) are preserved
     * (mass-assignment guard).
     */
    private fun toDomainForUpdate(
        resource: AiModelResource,
        existing: AiModel,
    ): AiModel =
        existing.copy(
            apiModelName = resource.apiModelName,
            description = resource.description,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AiModelResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (story 5-4 factorisation).

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("@aiModelGuard.canListByProvider(#parentId)")
    override fun listByParent(@PathVariable parentId: UUID): List<AiModelResource> = super.listByParent(parentId)

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("@aiModelGuard.canCreate(#resource)")
    override fun create(@Valid @RequestBody resource: AiModelResource): AiModelResource = super.create(resource)

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiModel', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiModelResource,
    ): AiModelResource {
        val existing = aiModelService.findById(id)
            ?: throw ResourceNotFoundException("AiModel not found: $id")
        return toResource(aiModelService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    /**
     * GET /by-namespaceId/{namespaceId} — list all model configs in a namespace.
     * Secured by namespace READ check (the denormalised [AiModel.namespaceId] is what
     * the underlying query uses).
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiModelResource> = aiModelService.findByNamespaceId(namespaceId).map { toResource(it) }

    /**
     * GET /platform-level — list all platform-level model configs (namespaceId IS NULL).
     * Readable by any authenticated user; the models are owned by platform-level AiProviders.
     */
    @GetMapping("/platform-level")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("isAuthenticated()")
    fun listPlatformLevel(): List<AiModelResource> = aiModelService.findPlatformLevel().map { toResource(it) }

    companion object : KLogging()
}
