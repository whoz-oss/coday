package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.SecuredEntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
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
 * REST API for managing [AiProvider] entities (Epic 4 Story 4.3).
 *
 * Extends [SecuredEntityController] — CREATE / UPDATE / DELETE are restricted
 * to namespace ADMINs (FR28/FR29/FR30). LIST / READ are open to every caller
 * with at least MEMBER access on the parent namespace (FR32) via the standard
 * transitive permission rules. The `[:BELONGS_TO]` edge between each
 * AiProvider node and its parent Namespace node is maintained by
 * [Neo4jAiProviderRepository.save] for namespace-scoped providers only.
 *
 * User-scoped providers (`userId != null`, `namespaceId == null`) are legacy
 * and cannot be created via this controller (403). The `/by-userId/` endpoint
 * remains reachable but is restricted to self-or-super-admin — full cleanup
 * tracked in issue #809.
 *
 * Standard CRUD endpoints (inherited, permission-gated):
 *   GET    /api/ai-providers/{id}
 *   POST   /api/ai-providers/by-ids
 *   GET    /api/ai-providers/by-parentId/{namespaceId}
 *   POST   /api/ai-providers
 *   PUT    /api/ai-providers/{id}
 *   DELETE /api/ai-providers/{id}
 *
 * Additional endpoints (legacy, secured — full removal tracked in #809):
 *   GET    /api/ai-providers/by-namespaceId/{namespaceId}  — namespace READ required
 *   GET    /api/ai-providers/by-userId/{userId}            — caller must be the same user (or super-admin)
 */
@RestController
@RequestMapping(
    "/api/ai-providers",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiProviderController(
    private val aiProviderService: AiProviderService,
    userService: UserService,
    permissionService: PermissionService,
) : SecuredEntityController<AiProvider, UUID, AiProviderResource>(
    aiProviderService,
    userService,
    permissionService,
) {

    override fun getEntityType(): String = ENTITY_TYPE

    /**
     * AiProvider creation is restricted to namespace ADMINs (FR28). User-scoped
     * creation (`namespaceId == null`) is refused — it's a legacy path with no
     * active consumer and no security model, tracked for removal in issue #809.
     */
    override fun checkCreatePermission(userId: String, entity: AiProvider) {
        val nsId = entity.namespaceId
            ?: throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "namespace-scoped AiProvider required (user-scoped deprecated, see #809)",
            )
        if (!permissionService.hasPermission(userId, NAMESPACE_TYPE, nsId.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied - namespace ADMIN role required")
        }
    }

    /**
     * GET /api/ai-providers/by-parentId/{parentId} — list providers in a namespace.
     *
     * Short-circuits the N+1 per-entity `hasPermission` cost by checking the
     * namespace-level READ once (Story 4.3 AC4).
     */
    override fun listByParent(@PathVariable parentId: UUID): List<AiProviderResource> {
        val userId = userService.getCurrentUser().id.toString()
        val canRead = permissionService.hasPermission(userId, NAMESPACE_TYPE, parentId.toString(), Action.READ)
        if (!canRead) {
            logger.debug { "User $userId has no READ on namespace $parentId — returning empty AiProvider list" }
            return emptyList()
        }
        return aiProviderService.findByParent(parentId).map { toResource(it) }
    }

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: AiProvider): AiProviderResource =
        AiProviderResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            description = entity.description,
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = entity.apiKey,
        )

    override fun toDomain(resource: AiProviderResource): AiProvider =
        AiProvider(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            description = resource.description,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

    /**
     * Merge an update resource onto an existing persisted entity.
     *
     * [AiProvider.namespaceId] and [AiProvider.userId] are server-owned — the
     * client cannot relocate a provider across namespaces nor downgrade it to
     * user-scoped via PUT. [AiProvider.apiKey] is mutable: a non-null payload
     * value replaces the persisted key, a null/blank one keeps it.
     */
    private fun toDomainForUpdate(
        resource: AiProviderResource,
        existing: AiProvider,
    ): AiProvider =
        existing.copy(
            name = resource.name,
            description = resource.description,
            apiType = resource.apiType ?: existing.apiType,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey?.takeIf { it.isNotBlank() } ?: existing.apiKey,
        )

    /**
     * PUT /{id} — update mutable fields of an existing AiProvider.
     *
     * Combines the secured-controller's WRITE permission check (namespace
     * ADMIN via transitivity) with namespace/user-pinning: the persisted
     * [AiProvider.namespaceId] and [AiProvider.userId] are preserved,
     * blocking cross-namespace privilege escalation and forced legacy
     * downgrades (a single-namespace ADMIN cannot move a provider out of
     * their namespace, nor convert a namespace-scoped provider into a
     * user-scoped one).
     */
    @PutMapping(
        "/{id}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiProviderResource,
    ): AiProviderResource {
        val existing = aiProviderService.findById(id)
            ?: throw ResourceNotFoundException("AiProvider not found: $id")
        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, ENTITY_TYPE, id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }
        return toResource(aiProviderService.update(toDomainForUpdate(resource, existing)))
    }

    /**
     * GET /by-namespaceId/{namespaceId} — list all configs scoped to a namespace.
     *
     * Legacy endpoint kept for backward compatibility with consumers that
     * predate the `/by-parentId/` convention. Same permission semantics as
     * `listByParent`: namespace-level READ check, empty list on deny. Full
     * removal tracked in issue #809.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiProviderResource> {
        val callerId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(callerId, NAMESPACE_TYPE, namespaceId.toString(), Action.READ)) {
            logger.debug { "User $callerId has no READ on namespace $namespaceId — returning empty AiProvider list" }
            return emptyList()
        }
        return aiProviderService.findByNamespaceId(namespaceId).map { toResource(it) }
    }

    /**
     * GET /by-userId/{userId} — list all configs scoped to a user.
     *
     * Legacy endpoint. New user-scoped providers can no longer be created
     * (see [checkCreatePermission]); this endpoint only surfaces pre-existing
     * data. Restricted to the targeted user (self) or a super-admin to avoid
     * cross-user disclosure of legacy providers (apiKeys are no longer
     * masked). Full cleanup tracked in issue #809.
     */
    @GetMapping("/by-userId/{userId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByUserId(
        @PathVariable userId: UUID,
    ): List<AiProviderResource> {
        val caller = userService.getCurrentUser()
        if (caller.id != userId && !caller.isAdmin) {
            logger.debug { "User ${caller.id} cannot list AiProviders of user $userId — returning empty list" }
            return emptyList()
        }
        return aiProviderService.findByUserId(userId).map { toResource(it) }
    }

    companion object : KLogging() {
        private const val ENTITY_TYPE = "AiProvider"
        private const val NAMESPACE_TYPE = "Namespace"
    }
}
