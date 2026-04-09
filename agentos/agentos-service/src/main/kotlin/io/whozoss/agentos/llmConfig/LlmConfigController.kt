package io.whozoss.agentos.llmConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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
 *   GET    /api/llm-configs/by-parentId/{namespaceId}  — list by namespace
 *   POST   /api/llm-configs
 *   PUT    /api/llm-configs/{id}
 *   DELETE /api/llm-configs/{id}
 *
 * Models are managed as independent [LlmModelConfig] entities via /api/llm-model-configs.
 *
 * API key handling:
 * - [toResource] always masks [LlmConfig.apiKey] before returning it to the client.
 * - [update] detects a masked sentinel in the incoming resource and preserves the
 *   persisted key, so clients that echo back a masked value do not accidentally
 *   clear or corrupt the stored credential.
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
            name = entity.name,
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = maskApiKey(entity.apiKey),
        )

    override fun toDomain(resource: LlmConfigResource): LlmConfig =
        LlmConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId!!,
            name = resource.name,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

    /**
     * PUT /{id} — update an existing LLM config.
     *
     * Overrides [EntityController.update] to handle masked [apiKey] values:
     * if the incoming resource carries a masked sentinel (contains "****"),
     * the persisted key is preserved unchanged. This lets clients echo back
     * the masked representation without accidentally clearing the credential.
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

    companion object : KLogging()
}
