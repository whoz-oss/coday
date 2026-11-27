package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.*
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.UUID

enum class ConnectionPolicyMode { COMPATIBILITY, STRICT }

/** Server-owned ceilings. No policy fields are accepted from tenant configuration. */
@ConfigurationProperties(prefix = "agentos.connections", ignoreUnknownFields = false)
data class ConnectionPolicyProperties(
    val mode: ConnectionPolicyMode = ConnectionPolicyMode.COMPATIBILITY,
    val policies: Map<String, ConnectionPolicy> = emptyMap(),
    val authSettingNames: Map<String, Set<String>> = emptyMap(),
    val authSettingIds: Map<UUID, Set<String>> = emptyMap(),
)

data class ConnectionPolicy(
    val allowedAuthTypes: Set<AuthType> = emptySet(),
    val authorizationUrls: Set<String> = emptySet(),
    val tokenUrls: Set<String> = emptySet(),
    val discoveryUrls: Set<String> = emptySet(),
    val registrationUrls: Set<String> = emptySet(),
    val allowedScopes: Set<String> = emptySet(),
    val allowedIntegrationTypes: Set<String> = emptySet(),
    val resourceOrigins: Set<String> = emptySet(),
)

/** Immutable startup snapshot; all matching bindings intersect, never override. */
@Component
class ConnectionPolicyRegistry(config: ConnectionPolicyProperties = ConnectionPolicyProperties()) {
    private val mode = config.mode
    private val policies = config.policies.mapValues { (_, p) -> p.copy(
        allowedAuthTypes = p.allowedAuthTypes.toSet(), authorizationUrls = p.authorizationUrls.map(::endpoint).toSet(),
        tokenUrls = p.tokenUrls.map(::endpoint).toSet(), discoveryUrls = p.discoveryUrls.map(::endpoint).toSet(),
        registrationUrls = p.registrationUrls.map(::endpoint).toSet(), allowedScopes = p.allowedScopes.toSet(),
        allowedIntegrationTypes = p.allowedIntegrationTypes.toSet(), resourceOrigins = p.resourceOrigins.map { origin(it, true) }.toSet(),
    ) }
    private val names = config.authSettingNames.mapValues { it.value.toSet() }
    private val ids = config.authSettingIds.mapValues { it.value.toSet() }

    init {
        check(policies.keys.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) }) { DENIED }
        check((names.values + ids.values).all { binding -> binding.isNotEmpty() && binding.all { it in policies } }) { DENIED }
    }

    fun isGoverned(setting: AuthSetting): Boolean = selected(setting).isNotEmpty()

    private fun selected(setting: AuthSetting): List<ConnectionPolicy> {
        val data = setting.toDataMap()
        val explicit = names[setting.name].orEmpty() + ids[setting.metadata.id].orEmpty()
        // Endpoint classification follows aliases, even in compatibility mode. Any one endpoint binds
        // the whole policy, so redirecting only the token endpoint cannot escape its ceiling.
        val classified = policies.filterValues { p ->
            listOf("authorizationUrl" to p.authorizationUrls, "tokenUrl" to p.tokenUrls,
                "discoveryUrl" to p.discoveryUrls).any { (key, allowed) ->
                data[key]?.let { url -> runCatching { endpoint(url) in allowed }.getOrDefault(false) } == true
            }
        }.keys
        return (explicit + classified).map { policies[it] ?: error(DENIED) }
    }

    fun requireSetting(setting: AuthSetting) {
        val selected = selected(setting)
        check(selected.isNotEmpty() || mode == ConnectionPolicyMode.COMPATIBILITY) { DENIED }
        val data = setting.toDataMap()
        selected.forEach { p ->
            check(setting.authType in p.allowedAuthTypes) { DENIED }
            when (setting) {
                is OAuthRegisteredAuthSetting, is OAuthCustomAuthSetting -> {
                    requireEndpoint(data["authorizationUrl"].orEmpty(), p.authorizationUrls)
                    requireEndpoint(data["tokenUrl"].orEmpty(), p.tokenUrls)
                }
                is OAuthDiscoverableAuthSetting -> requireEndpoint(setting.discoveryUrl, p.discoveryUrls)
                is OAuthMcpDiscoverableAuthSetting -> error("$DENIED: MCP discovery is not supported for governed connections")
                else -> Unit
            }
            if (setting.authType in OAUTH_TYPES) {
                val requested = scopes(data["scopes"])
                check(requested.isNotEmpty() && p.allowedScopes.containsAll(requested)) { DENIED }
            }
        }
    }

    fun requireIntegration(setting: AuthSetting, integrationType: String) {
        requireSetting(setting)
        selected(setting).forEach { check(integrationType in it.allowedIntegrationTypes) { DENIED } }
    }

    fun requireResource(setting: AuthSetting, integrationType: String, destination: String) {
        requireIntegration(setting, integrationType)
        selected(setting).forEach { check(origin(destination) in it.resourceOrigins) { DENIED } }
    }

    fun requireEndpoint(setting: AuthSetting, kind: String, url: String) {
        requireSetting(setting)
        selected(setting).forEach { p -> requireEndpoint(url, when (kind) {
            "authorization" -> p.authorizationUrls
            "token" -> p.tokenUrls
            "discovery" -> p.discoveryUrls
            "registration" -> p.registrationUrls
            else -> error(DENIED)
        }) }
    }

    fun requireGrant(setting: AuthSetting, credential: Credential, userId: UUID) {
        requireSetting(setting)
        if (isGoverned(setting)) {
            check(credential.userId == userId && credential.authSettingId == setting.metadata.id) { DENIED }
            val expected = when (setting.authType) {
                AuthType.API_KEY -> CredentialType.API_KEY
                AuthType.BEARER_TOKEN -> CredentialType.BEARER_TOKEN
                AuthType.BASIC_AUTH -> CredentialType.BASIC_AUTH
                else -> CredentialType.OAUTH_TOKENS
            }
            check(credential.credentialType == expected) { DENIED }
            if (expected == CredentialType.OAUTH_TOKENS) {
                val actual = scopes(credential.data["scope"])
                check(actual.isNotEmpty() && scopes(setting.toDataMap()["scopes"]).containsAll(actual) &&
                    selected(setting).all { it.allowedScopes.containsAll(actual) }) { "$DENIED: unknown or disallowed OAuth grant; reconnect" }
            }
        }
    }

    companion object {
        const val DENIED = "CONNECTION_POLICY_DENIED"
        private val OAUTH_TYPES = setOf(AuthType.OAUTH_REGISTERED, AuthType.OAUTH_CUSTOM, AuthType.OAUTH_DISCOVERABLE, AuthType.OAUTH_MCP_DISCOVERABLE)
        private fun scopes(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))?.toSet().orEmpty()
        private fun endpoint(raw: String): String {
            val url = raw.toHttpUrlOrNull()
            check(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.fragment == null && raw == raw.trim() && !raw.contains('\\')) { DENIED }
            // Reject ambiguous dot segments and encoded path separators rather than repairing them.
            check(!Regex("(?i)%2e|%2f|%5c|(?:^|/)\\.\\.?(?:/|$)").containsMatchIn(java.net.URI(raw).rawPath.orEmpty())) { DENIED }
            return url.toString()
        }
        private fun origin(raw: String, configured: Boolean = false): String {
            val url = endpoint(raw).toHttpUrlOrNull()!!
            check(!configured || (url.encodedPath == "/" && url.query == null)) { DENIED }
            return "https://${url.host}:${url.port}"
        }
        private fun requireEndpoint(raw: String, allowed: Set<String>) { check(endpoint(raw) in allowed) { DENIED } }
    }
}
