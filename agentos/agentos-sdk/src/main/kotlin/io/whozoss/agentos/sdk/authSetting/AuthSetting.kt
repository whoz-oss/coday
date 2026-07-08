package io.whozoss.agentos.sdk.authSetting

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent authentication setting — describes *how* to authenticate against an external service.
 *
 * Scoped to a namespace, a user, or both, following the same 4-tier shadowing as [AiProvider]:
 * - platform-level: (null, null) — built-in defaults, read-only for tenants
 * - namespace-shared: (namespaceId, null) — shared config for all users of a namespace
 * - user-global: (null, userId) — personal config across all namespaces
 * - user × namespace: (namespaceId, userId) — user-specific override within a namespace
 *
 * At least one of [namespaceId] / [userId] must be non-null for tenant-owned settings.
 * This constraint is enforced by the service layer.
 *
 * [authType] is immutable after creation — changing the authentication mechanism requires
 * deleting and recreating the setting.
 *
 * [data] holds all sensitive configuration for the auth method (e.g. clientId, clientSecret,
 * discoveryUrl, authorizationUrl, tokenUrl, scopes for OAuth; key for API_KEY;
 * username, password for BASIC_AUTH). Values are stored encrypted at rest and masked
 * in API responses.
 *
 * Uniqueness constraint: (namespaceId, userId, name) must be unique — enforced by the
 * service layer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    val namespaceId: UUID? = null,
    val userId: UUID? = null,
    val name: String,
    val description: String? = null,
    val authType: AuthType,
    val data: Map<String, String> = emptyMap(),
) : Entity
