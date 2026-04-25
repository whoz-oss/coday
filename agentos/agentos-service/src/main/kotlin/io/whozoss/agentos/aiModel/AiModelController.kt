package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing [AiModel] entities (Epic 4 Story 4.4).
 *
 * Extends [SecuredEntityController] — CREATE / UPDATE / DELETE are restricted
 * to namespace ADMINs (FR33), READ / LIST are open to namespace MEMBERs via
 * the standard transitive permission rules (FR35). The `[:BELONGS_TO]` edge
 * between each AiModel node and its parent Namespace node is maintained by
 * [Neo4JAiModelRepository.save] for namespace-scoped models only.
 *
 * Models whose parent AiProvider is user-scoped (legacy) have no namespace
 * edge and therefore no permission path — they are effectively hidden from
 * the secured endpoints. Cleanup is tracked in issue #809.
 *
 * Standard CRUD endpoints (inherited, permission-gated):
 *   GET    /api/ai-models/{id}
 *   POST   /api/ai-models/by-ids
 *   GET    /api/ai-models/by-parentId/{aiProviderId}  — list by provider
 *   POST   /api/ai-models
 *   PUT    /api/ai-models/{id}
 *   DELETE /api/ai-models/{id}
 *
 * Additional endpoints (secured):
 *   GET    /api/ai-models/by-namespaceId/{namespaceId}  — list all models in a namespace
 */
@RestController
@RequestMapping(
    "/api/ai-models",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiModelController(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    userService: UserService,
    permissionService: PermissionService,
) : SecuredEntityController<AiModel, UUID, AiModelResource>(
    aiModelService,
    userService,
    permissionService,
) {

    override fun getEntityType(): String = ENTITY_TYPE

    /**
     * AiModel creation is restricted to ADMINs of the namespace hosting the
     * parent AiProvider (FR33). The caller sends only `aiProviderId` in the
     * payload — [entity.namespaceId] is null at this point (denormalisation
     * happens later in [AiModelServiceImpl.create]). We therefore look up the
     * parent provider to resolve the namespace.
     *
     * A missing / soft-deleted provider returns 403 (not 404) to hide
     * existence from callers that do not have access.
     */
    override fun checkCreatePermission(userId: String, entity: AiModel) {
        val provider = aiProviderService.findById(entity.aiProviderId)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        val nsId = provider.namespaceId
            ?: throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "namespace-scoped AiProvider required (user-scoped deprecated, see #809)",
            )
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, nsId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - namespace ADMIN role required")
        }
    }

    /**
     * GET /by-parentId/{aiProviderId} — list models under a provider.
     *
     * Short-circuits the N+1 per-entity `hasPermission` cost by resolving the
     * parent provider's namespace and checking READ once on the namespace
     * (Story 4.4 AC5). User-scoped or missing providers yield an empty list.
     */
    override fun listByParent(@PathVariable parentId: UUID): List<AiModelResource> {
        val userId = userService.getCurrentUser().id.toString()
        val provider = aiProviderService.findById(parentId) ?: return emptyList()
        val nsId = provider.namespaceId ?: return emptyList()
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, nsId.toString(), Action.READ)) {
            logger.debug { "User $userId has no READ on namespace $nsId — returning empty AiModel list" }
            return emptyList()
        }
        return aiModelService.findByParent(parentId).map { toResource(it) }
    }

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
     * Convert a resource to a domain entity for **create** only.
     *
     * [namespaceId] and [userId] are intentionally omitted here — they are
     * server-resolved from the parent [io.whozoss.agentos.aiProvider.AiProvider] by
     * [AiModelServiceImpl.create] before persisting. The nil-UUID placeholder
     * is never stored; the service always overwrites it.
     *
     * For **update**, use [toDomainForUpdate] so server-owned fields are preserved
     * from the persisted record rather than accepted from the client.
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
     * Merge an update resource onto an existing persisted entity.
     *
     * Server-owned fields ([AiModel.namespaceId], [AiModel.userId],
     * [AiModel.aiProviderId]) are always taken from [existing] — the client
     * cannot change them via a PUT. Client-supplied fields overwrite the rest.
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

    /**
     * PUT /{id} — update mutable fields of an existing model config.
     *
     * Combines the secured-controller's WRITE permission check (namespace
     * ADMIN via transitivity) with the pre-existing server-owned-field
     * preservation pattern: `namespaceId`, `userId`, and `aiProviderId`
     * always come from the persisted record, never from the payload.
     */
    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiModelResource,
    ): AiModelResource {
        val existing = aiModelService.findById(id)
            ?: throw ResourceNotFoundException("AiModel not found: $id")

        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, ENTITY_TYPE, id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        return toResource(aiModelService.update(toDomainForUpdate(resource, existing)))
    }

    /**
     * GET /by-namespaceId/{namespaceId} — list all model configs in a namespace.
     *
     * Secured by a single namespace-level READ check (Story 4.4 AC5). Uses the
     * denormalised [AiModel.namespaceId] property so no join through
     * [io.whozoss.agentos.aiProvider.AiProvider] is needed.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiModelResource> {
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, namespaceId.toString(), Action.READ)) {
            logger.debug { "User $userId has no READ on namespace $namespaceId — returning empty AiModel list" }
            return emptyList()
        }
        return aiModelService.findByNamespaceId(namespaceId).map { toResource(it) }
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "AiModel"
        private const val NAMESPACE_TYPE = "Namespace"
    }
}
