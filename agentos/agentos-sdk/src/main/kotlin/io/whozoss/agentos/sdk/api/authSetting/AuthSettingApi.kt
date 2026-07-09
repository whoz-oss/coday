package io.whozoss.agentos.sdk.api.authSetting

import io.whozoss.agentos.sdk.api.common.EntityCrudApi

/**
 * HTTP API contract for AuthSetting entities.
 *
 * Implemented by `AuthSettingController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * **Scope query parameters** on [list]:
 * - `namespaceId=<uuid>` — NS-shared auth settings for that namespace
 * - `namespaceId=<uuid>&userId=me` — user x namespace overlay
 * - `namespaceId=none&userId=me` — user-global auth settings
 * - `userId=me` (no namespace) — all caller's auth settings across scopes
 *
 * [data] values are masked in all responses (`"****"`). On PUT, sending the masked sentinel
 * preserves the stored value; sending `""` clears it; sending a new value replaces it.
 */
interface AuthSettingApi : EntityCrudApi<AuthSettingDto> {

    /**
     * GET /api/auth-settings — list auth settings by scope.
     *
     * Scope is inferred from the combination of query parameters:
     * - (no params)                       → platform-level auth settings
     * - `namespaceId=<uuid>`              → NS-shared auth settings for that namespace
     * - `namespaceId=<uuid>&userId=me`    → user × namespace overlay for the caller
     * - `namespaceId=none&userId=me`      → user-global auth settings (no namespace)
     * - `userId=me` (no namespace)        → all caller's auth settings across scopes
     *
     * [namespaceId] accepts a UUID string or the sentinel `"none"` (meaning `namespaceId IS NULL`).
     * [userId] accepts only the sentinel `"me"` or absent.
     */
    fun list(namespaceId: String? = null, userId: String? = null): List<AuthSettingDto>
}
