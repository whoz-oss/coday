package io.whozoss.agentos.sdk.authSetting

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent authentication setting — describes *how* to authenticate against an external service.
 *
 * Scoped to a namespace, a user, or both, following the same 4-tier shadowing as AiProvider:
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
 * Each subtype carries typed properties instead of a generic map. Use [toDataMap] to obtain
 * the flat property map for persistence (all values are encrypted at rest) and [toSensitiveKeys]
 * to identify which keys to mask in API responses.
 *
 * Uniqueness constraint: (namespaceId, userId, name) must be unique — enforced by the
 * service layer.
 *
 * Jackson polymorphism: [authType] is used as the discriminant.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "authType",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = ApiKeyAuthSetting::class, name = "API_KEY"),
    JsonSubTypes.Type(value = BearerTokenAuthSetting::class, name = "BEARER_TOKEN"),
    JsonSubTypes.Type(value = BasicAuthAuthSetting::class, name = "BASIC_AUTH"),
    JsonSubTypes.Type(value = OAuthDiscoverableAuthSetting::class, name = "OAUTH_DISCOVERABLE"),
    JsonSubTypes.Type(value = OAuthRegisteredAuthSetting::class, name = "OAUTH_REGISTERED"),
    JsonSubTypes.Type(value = OAuthCustomAuthSetting::class, name = "OAUTH_CUSTOM"),
)
sealed interface AuthSetting : Entity {
    val namespaceId: UUID?
    val userId: UUID?
    val name: String
    val description: String?
    val authType: AuthType
}

// ---------------------------------------------------------------------------
// Subtypes
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class ApiKeyAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val apiKey: String = "",
) : AuthSetting {
    override val authType: AuthType = AuthType.API_KEY
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BearerTokenAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val token: String = "",
) : AuthSetting {
    override val authType: AuthType = AuthType.BEARER_TOKEN
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BasicAuthAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val username: String = "",
    val password: String = "",
) : AuthSetting {
    override val authType: AuthType = AuthType.BASIC_AUTH
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthDiscoverableAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val discoveryUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: String? = null,
) : AuthSetting {
    override val authType: AuthType = AuthType.OAUTH_DISCOVERABLE
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthRegisteredAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizationUrl: String = "",
    val tokenUrl: String = "",
    val scopes: String? = null,
) : AuthSetting {
    override val authType: AuthType = AuthType.OAUTH_REGISTERED
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OAuthCustomAuthSetting(
    override val metadata: EntityMetadata = EntityMetadata(),
    override val namespaceId: UUID? = null,
    override val userId: UUID? = null,
    override val name: String,
    override val description: String? = null,
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizationUrl: String = "",
    val tokenUrl: String = "",
    val scopes: String? = null,
) : AuthSetting {
    override val authType: AuthType = AuthType.OAUTH_CUSTOM
}

// ---------------------------------------------------------------------------
// Map conversion helpers
// ---------------------------------------------------------------------------

/**
 * All type-specific properties as a flat string map, with blank values omitted.
 *
 * Used by the persistence layer — every value in the returned map is encrypted at rest.
 * Blank (empty or whitespace-only) values are excluded because absent and blank are
 * semantically equivalent: [authSettingFromDataMap] reconstructs missing keys as `""`.
 * This keeps the stored map minimal and makes round-trip equality predictable.
 */
fun AuthSetting.toDataMap(): Map<String, String> =
    when (this) {
        is ApiKeyAuthSetting -> buildMap {
            if (apiKey.isNotBlank()) put("apiKey", apiKey)
        }
        is BearerTokenAuthSetting -> buildMap {
            if (token.isNotBlank()) put("token", token)
        }
        is BasicAuthAuthSetting -> buildMap {
            if (username.isNotBlank()) put("username", username)
            if (password.isNotBlank()) put("password", password)
        }
        is OAuthDiscoverableAuthSetting -> buildMap {
            if (discoveryUrl.isNotBlank()) put("discoveryUrl", discoveryUrl)
            if (clientId.isNotBlank()) put("clientId", clientId)
            if (clientSecret.isNotBlank()) put("clientSecret", clientSecret)
            if (scopes != null) put("scopes", scopes)
        }
        is OAuthRegisteredAuthSetting -> buildMap {
            if (clientId.isNotBlank()) put("clientId", clientId)
            if (clientSecret.isNotBlank()) put("clientSecret", clientSecret)
            if (authorizationUrl.isNotBlank()) put("authorizationUrl", authorizationUrl)
            if (tokenUrl.isNotBlank()) put("tokenUrl", tokenUrl)
            if (scopes != null) put("scopes", scopes)
        }
        is OAuthCustomAuthSetting -> buildMap {
            if (clientId.isNotBlank()) put("clientId", clientId)
            if (clientSecret.isNotBlank()) put("clientSecret", clientSecret)
            if (authorizationUrl.isNotBlank()) put("authorizationUrl", authorizationUrl)
            if (tokenUrl.isNotBlank()) put("tokenUrl", tokenUrl)
            if (scopes != null) put("scopes", scopes)
        }
    }

/**
 * The subset of keys in [toDataMap] that are considered sensitive and must be masked
 * in API responses (replaced with `"****"` or a partial mask).
 *
 * Non-sensitive keys (e.g. [BasicAuthAuthSetting.username], [OAuthDiscoverableAuthSetting.clientId])
 * are intentionally excluded — they can be surfaced in plain text to help users identify
 * which setting they are looking at.
 */
fun AuthSetting.toSensitiveKeys(): Set<String> =
    when (this) {
        is ApiKeyAuthSetting -> setOf("apiKey")
        is BearerTokenAuthSetting -> setOf("token")
        is BasicAuthAuthSetting -> setOf("password")
        is OAuthDiscoverableAuthSetting -> setOf("clientSecret")
        is OAuthRegisteredAuthSetting -> setOf("clientSecret")
        is OAuthCustomAuthSetting -> setOf("clientSecret")
    }

/**
 * Reconstruct a typed [AuthSetting] from a flat [data] map.
 *
 * Used by the repository's `toDomain` conversion and by the merge strategy when
 * folding overlay layers that were originally persisted as maps.
 *
 * Missing keys in [data] fall back to the default empty-string values declared on
 * each subtype — this ensures forward-compatibility when new optional properties are
 * added to a subtype.
 */
fun authSettingFromDataMap(
    authType: AuthType,
    data: Map<String, String>,
    metadata: EntityMetadata,
    namespaceId: UUID?,
    userId: UUID?,
    name: String,
    description: String?,
): AuthSetting =
    when (authType) {
        AuthType.API_KEY ->
            ApiKeyAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                apiKey = data["apiKey"] ?: "",
            )
        AuthType.BEARER_TOKEN ->
            BearerTokenAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                token = data["token"] ?: "",
            )
        AuthType.BASIC_AUTH ->
            BasicAuthAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                username = data["username"] ?: "",
                password = data["password"] ?: "",
            )
        AuthType.OAUTH_DISCOVERABLE ->
            OAuthDiscoverableAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                discoveryUrl = data["discoveryUrl"] ?: "",
                clientId = data["clientId"] ?: "",
                clientSecret = data["clientSecret"] ?: "",
                scopes = data["scopes"],
            )
        AuthType.OAUTH_REGISTERED ->
            OAuthRegisteredAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                clientId = data["clientId"] ?: "",
                clientSecret = data["clientSecret"] ?: "",
                authorizationUrl = data["authorizationUrl"] ?: "",
                tokenUrl = data["tokenUrl"] ?: "",
                scopes = data["scopes"],
            )
        AuthType.OAUTH_CUSTOM ->
            OAuthCustomAuthSetting(
                metadata = metadata,
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                description = description,
                clientId = data["clientId"] ?: "",
                clientSecret = data["clientSecret"] ?: "",
                authorizationUrl = data["authorizationUrl"] ?: "",
                tokenUrl = data["tokenUrl"] ?: "",
                scopes = data["scopes"],
            )
    }
