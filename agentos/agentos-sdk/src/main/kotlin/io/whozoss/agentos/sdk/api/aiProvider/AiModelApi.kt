package io.whozoss.agentos.sdk.api.aiProvider

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for AiModel entities.
 *
 * Implemented by `AiModelController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * [namespaceId] and [userId] on [AiModelDto] are server-resolved from the parent
 * [AiProviderDto] at creation time and must not be set by the caller.
 */
interface AiModelApi : EntityCrudApi<AiModelDto> {

    /** GET /api/ai-models/by-parentId/{parentId} — list all models for an AiProvider. */
    fun listByParent(parentId: UUID): List<AiModelDto>

    /** GET /api/ai-models/by-namespaceId/{namespaceId} — list all model configs in a namespace. */
    fun listByNamespaceId(namespaceId: UUID): List<AiModelDto>
}
