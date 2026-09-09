package io.whozoss.agentos.sdk.api.integrationConfig

import io.whozoss.agentos.sdk.api.common.EntityCrudApi

/**
 * HTTP API contract for IntegrationConfig entities.
 *
 * Implemented by `IntegrationConfigController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * **Scope query parameters** on [list] follow the same convention as AiProviderApi:
 * - `namespaceId=<uuid>` — NS-shared configs
 * - `namespaceId=<uuid>&userId=me` — user x namespace overlay
 * - `namespaceId=none&userId=me` — user-global configs
 * - `userId=me` (no namespace) — all caller's configs
 *
 * Note: `GET /by-parentId/{parentId}` is removed from the service; use [list] instead.
 */
interface IntegrationConfigApi : EntityCrudApi<IntegrationConfigDto> {

    /**
     * GET /api/integration-configs — list configs by scope.
     *
     * [namespaceId] accepts a UUID string or the sentinel `"none"` (meaning `namespaceId IS NULL`).
     * [userId] accepts only the sentinel `"me"` or absent.
     */
    fun list(namespaceId: String? = null, userId: String? = null): List<IntegrationConfigDto>

    /**
     * GET /by-parentId/{parentId} — kept for interface compatibility; always throws on the server.
     * Use [list] with a `namespaceId` parameter instead.
     */
    fun listByParent(parentId: java.util.UUID): List<IntegrationConfigDto>
}
