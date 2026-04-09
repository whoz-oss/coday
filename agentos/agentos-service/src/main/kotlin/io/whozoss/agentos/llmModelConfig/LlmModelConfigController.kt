package io.whozoss.agentos.llmModelConfig

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RequestMapping
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
            apiName = resource.apiName,
            alias = resource.alias,
            displayName = resource.displayName,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    companion object : KLogging()
}
