package io.whozoss.agentos.sdk.api.aiProvider

import io.whozoss.agentos.sdk.api.common.EntityCrudApi

/**
 * HTTP API contract for AiProvider entities.
 *
 * Implemented by `AiProviderController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * **Scope query parameters** on [list]:
 * - `namespaceId=<uuid>` — NS-shared providers for that namespace
 * - `namespaceId=<uuid>&userId=me` — user x namespace overlay
 * - `namespaceId=none&userId=me` — user-global providers
 * - `userId=me` (no namespace) — all caller's providers across scopes
 *
 * [apiKey] is masked in all responses (`"****"`). On PUT, sending the masked sentinel
 * preserves the stored key; sending `""` clears it; sending a new value replaces it.
 */
interface AiProviderApi : EntityCrudApi<AiProviderDto> {

    /**
     * GET /api/ai-providers — list providers by scope.
     *
     * [namespaceId] accepts a UUID string or the sentinel `"none"` (meaning `namespaceId IS NULL`).
     * [userId] accepts only the sentinel `"me"` or absent.
     */
    fun list(namespaceId: String? = null, userId: String? = null): List<AiProviderDto>
}
