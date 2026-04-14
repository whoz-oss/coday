package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
import java.util.UUID

/**
 * REST API for managing [AiProvider] entities.
 *
 * Extends [EntityController] with [AiProviderResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/ai-providers/{id}
 *   POST   /api/ai-providers/by-ids
 *   GET    /api/ai-providers/by-parentId/{namespaceId}  — list by namespace (convenience alias)
 *   POST   /api/ai-providers
 *   PUT    /api/ai-providers/{id}
 *   DELETE /api/ai-providers/{id}
 *
 * Additional endpoints:
 *   GET    /api/ai-providers/by-namespaceId/{namespaceId}  — list by namespace
 *   GET    /api/ai-providers/by-userId/{userId}            — list by user
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
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = maskApiKey(entity.apiKey),
        )

    override fun toDomain(resource: AiProviderResource): AiProvider =
        AiProvider(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

    /**
     * PUT /{id} — update an existing AI provider.
     *
     * Preserves the persisted [apiKey] when the incoming value is masked.
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
        val existing =
            aiProviderService.findById(id)
                ?: throw ResourceNotFoundException("Entity not found: $id")
        val resolvedApiKey =
            when {
                isMasked(resource.apiKey) -> existing.apiKey
                else -> resource.apiKey
            }
        val updated =
            toDomain(resource).copy(
                metadata = existing.metadata,
                apiKey = resolvedApiKey,
            )
        return toResource(aiProviderService.update(updated))
    }

    /**
     * GET /by-namespaceId/{namespaceId} — list all configs scoped to a namespace.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiProviderResource> = aiProviderService.findByNamespaceId(namespaceId).map { toResource(it) }

    /**
     * GET /by-userId/{userId} — list all configs scoped to a user.
     */
    @GetMapping("/by-userId/{userId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByUserId(
        @PathVariable userId: UUID,
    ): List<AiProviderResource> = aiProviderService.findByUserId(userId).map { toResource(it) }

    companion object : KLogging()
}
