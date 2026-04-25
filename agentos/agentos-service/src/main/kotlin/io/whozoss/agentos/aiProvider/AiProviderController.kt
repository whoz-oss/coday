package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PostFilter
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * REST API for managing [AiProvider] entities (Epic 5 declarative migration).
 *
 * Authorization declared via `@PreAuthorize`:
 * - READ: namespace MEMBER (FR32 — apiKey returned in clear, no masking)
 * - WRITE/DELETE: namespace ADMIN (FR28/29/30)
 * - CREATE: namespace ADMIN. User-scoped creation (`namespaceId == null`) is
 *   refused via the SpEL itself: `hasPermission(null, ...)` evaluates to false
 *   in [AgentOsPermissionEvaluator] → 403. The body's null-check is a defense-
 *   in-depth fail-closed redundancy in case `@PreAuthorize` is ever bypassed
 *   (cleanup tracked in #809).
 * - The body also forces `userId = null` for namespace-scoped creation
 *   (mass-assignment guard against spoofed `userId` in payloads).
 *
 * Legacy endpoints `/by-namespaceId/` and `/by-userId/` are kept for backward
 * compatibility but secured (cf. RFC §8 Q7 — fail-closed posture, full removal in #809).
 */
@RestController
@RequestMapping(
    "/api/ai-providers",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiProviderController(
    private val aiProviderService: AiProviderService,
) : EntityController<AiProvider, UUID, AiProviderResource>(aiProviderService) {

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
            // Defense: when the create payload has a namespaceId, ignore any client-supplied
            // userId — namespace-scoped providers must not be tagged with an arbitrary user
            // identity. User-scoped creation is refused upstream via @PreAuthorize.
            userId = if (resource.namespaceId != null) null else resource.userId,
            name = resource.name,
            description = resource.description,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

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

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AiProviderResource = super.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PostFilter("hasPermission(filterObject.id, 'AiProvider', 'READ')")
    override fun getByIds(@RequestBody ids: List<UUID>): List<AiProviderResource> = super.getByIds(ids)

    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(@PathVariable parentId: UUID): List<AiProviderResource> = super.listByParent(parentId)

    /**
     * POST — create a new AiProvider.
     *
     * Authorization order in production (Spring AOP):
     *  1. `@PreAuthorize` evaluates `hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')`.
     *     For `namespaceId == null`, [AgentOsPermissionEvaluator] returns false → 403 Forbidden.
     *     For a valid namespaceId without WRITE permission → 403 Forbidden.
     *  2. Body runs only when the SpEL passes; the null-check is a defense-in-depth
     *     fail-closed redundancy (e.g. should @PreAuthorize ever be bypassed by misconfig).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#resource.namespaceId, 'Namespace', 'WRITE')")
    override fun create(@Valid @RequestBody resource: AiProviderResource): AiProviderResource {
        if (resource.namespaceId == null) {
            // Should never reach this in production — @PreAuthorize already denied.
            // Kept as defense-in-depth.
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "namespace-scoped AiProvider required (user-scoped deprecated, see #809)",
            )
        }
        return super.create(resource)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'WRITE')")
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiProviderResource,
    ): AiProviderResource {
        val existing = aiProviderService.findById(id)
            ?: throw ResourceNotFoundException("AiProvider not found: $id")
        return toResource(aiProviderService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'DELETE')")
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    /**
     * GET /by-namespaceId/{namespaceId} — legacy endpoint, namespace READ required.
     * Equivalent to `/by-parentId/` (backward compat). Cleanup tracked in #809.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiProviderResource> = aiProviderService.findByNamespaceId(namespaceId).map { toResource(it) }

    /**
     * GET /by-userId/{userId} — legacy endpoint surfacing pre-existing user-scoped
     * providers. Restricted to the targeted user (self) or super-admin to avoid
     * cross-user disclosure. Cleanup tracked in #809.
     */
    @GetMapping("/by-userId/{userId}")
    @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasRole('SUPER_ADMIN') or #userId.toString() == authentication.name")
    fun listByUserId(
        @PathVariable userId: UUID,
    ): List<AiProviderResource> = aiProviderService.findByUserId(userId).map { toResource(it) }

    companion object : KLogging()
}
