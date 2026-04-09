package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
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
 * REST API for managing [LlmConfig] entities.
 *
 * Extends [EntityController] with [LlmConfigResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/llm-configs/{id}
 *   POST   /api/llm-configs/by-ids
 *   GET    /api/llm-configs/by-parentId/{namespaceId}  — list by namespace (convenience alias)
 *   POST   /api/llm-configs
 *   PUT    /api/llm-configs/{id}
 *   DELETE /api/llm-configs/{id}
 *
 * Additional endpoints:
 *   GET    /api/llm-configs/by-namespaceId/{namespaceId}  — list by namespace
 *   GET    /api/llm-configs/by-userId/{userId}            — list by user
 */
@RestController
@RequestMapping(
    "/api/llm-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class LlmConfigController(
    private val llmConfigService: LlmConfigService,
) : EntityController<LlmConfig, UUID, LlmConfigResource>(llmConfigService) {

    override fun toResource(entity: LlmConfig): LlmConfigResource =
        LlmConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = maskApiKey(entity.apiKey),
        )

    override fun toDomain(resource: LlmConfigResource): LlmConfig =
        LlmConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

    /**
     * PUT /{id} — update an existing LLM config.
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
        @Valid @RequestBody resource: LlmConfigResource,
    ): LlmConfigResource {
        val existing = llmConfigService.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")
        val resolvedApiKey = when {
            isMasked(resource.apiKey) -> existing.apiKey
            else -> resource.apiKey
        }
        val updated = toDomain(resource).copy(
            metadata = existing.metadata,
            apiKey = resolvedApiKey,
        )
        return toResource(llmConfigService.update(updated))
    }

    /**
     * GET /by-namespaceId/{namespaceId} — list all configs scoped to a namespace.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<LlmConfigResource> =
        llmConfigService.findByNamespaceId(namespaceId).map { toResource(it) }

    /**
     * GET /by-userId/{userId} — list all configs scoped to a user.
     */
    @GetMapping("/by-userId/{userId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByUserId(
        @PathVariable userId: UUID,
    ): List<LlmConfigResource> =
        llmConfigService.findByUserId(userId).map { toResource(it) }

    companion object : KLogging()
}
