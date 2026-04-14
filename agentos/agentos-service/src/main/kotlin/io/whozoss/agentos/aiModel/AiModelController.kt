package io.whozoss.agentos.aiModel

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.entity.EntityMetadata
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing [AiModel] entities.
 *
 * Extends [EntityController] with [AiModelResource] as the HTTP DTO.
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
class AiModelController(
    private val aiModelService: AiModelService,
) : EntityController<AiModel, UUID, AiModelResource>(aiModelService) {
    override fun toResource(entity: AiModel): AiModelResource =
        AiModelResource(
            id = entity.metadata.id,
            aiProviderId = entity.aiProviderId,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            apiName = entity.apiName,
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
            apiName = resource.apiName,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    /**
     * Merge an update resource onto an existing persisted entity.
     *
     * Server-owned fields ([AiModel.namespaceId], [AiModel.userId],
     * [AiModel.llmConfigId]) are always taken from [existing] — the client
     * cannot change them via a PUT. Client-supplied fields overwrite the rest.
     */
    private fun toDomainForUpdate(
        resource: AiModelResource,
        existing: AiModel,
    ): AiModel =
        existing.copy(
            apiName = resource.apiName,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    /**
     * PUT /{id} — update mutable fields of an existing model config.
     *
     * Server-owned fields (namespaceId, userId, llmConfigId) are preserved from the
     * persisted record and cannot be changed by the client.
     */
    override fun update(
        id: UUID,
        @Valid @RequestBody resource: AiModelResource,
    ): AiModelResource {
        val existing =
            service.findById(id)
                ?: throw ResourceNotFoundException("LlmModelConfig not found: $id")
        return toResource(service.update(toDomainForUpdate(resource, existing)))
    }

    /**
     * GET /by-namespaceId/{namespaceId} — list all model configs in a namespace.
     *
     * Uses the denormalised [AiModel.namespaceId] property so no join through
     * [io.whozoss.agentos.aiProvider.AiProvider] is needed.
     */
    @GetMapping("/by-namespaceId/{namespaceId}")
    @ResponseStatus(HttpStatus.OK)
    fun listByNamespaceId(
        @PathVariable namespaceId: UUID,
    ): List<AiModelResource> = aiModelService.findByNamespaceId(namespaceId).map { toResource(it) }

    companion object : KLogging()
}
