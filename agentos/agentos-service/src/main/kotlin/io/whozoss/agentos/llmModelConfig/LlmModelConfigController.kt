package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [LlmModelConfig] entities.
 *
 * Extends [EntityController] with [LlmModelConfigResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/llm-model-configs/{id}
 *   POST   /api/llm-model-configs/by-ids
 *   GET    /api/llm-model-configs/by-parentId/{llmConfigId}  — list by provider config
 *   POST   /api/llm-model-configs
 *   PUT    /api/llm-model-configs/{id}
 *   DELETE /api/llm-model-configs/{id}
 *
 * Additional endpoints:
 *   GET    /api/llm-model-configs/by-namespaceId/{namespaceId}  — list all models in a namespace
 */
@RestController
@RequestMapping(
    "/api/llm-model-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class LlmModelConfigController(
    private val llmModelConfigService: LlmModelConfigService,
) : EntityController<LlmModelConfig, UUID, LlmModelConfigResource>(llmModelConfigService) {

    override fun toResource(entity: LlmModelConfig): LlmModelConfigResource =
        LlmModelConfigResource(
            id = entity.metadata.id,
            llmConfigId = entity.llmConfigId,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            apiName = entity.apiName,
            alias = entity.alias,
            displayName = entity.displayName,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens,
        )

    override fun toDomain(resource: LlmModelConfigResource): LlmModelConfig =
        LlmModelConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            llmConfigId = resource.llmConfigId!!,
            // namespaceId and userId are server-resolved at create time — a placeholder
            // is used here; LlmModelConfigServiceImpl.create() overwrites them from the
            // parent LlmConfig before persisting.
            namespaceId = resource.namespaceId ?: UUID.fromString("00000000-0000-0000-0000-000000000000"),
            userId = resource.userId,
            apiName = resource.apiName,
            alias = resource.alias,
            displayName = resource.displayName,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    /**
     * GET /by-namespaceId/{namespaceId} — list all model configs in a namespace.
     *
     * Uses the denormalised [LlmModelConfig.namespaceId] property so no join through
     * [io.whozoss.agentos.llmConfig.LlmConfig] is needed.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<LlmModelConfigResource> =
        llmModelConfigService.findByNamespaceId(namespaceId).map { toResource(it) }

    companion object : KLogging()
}
