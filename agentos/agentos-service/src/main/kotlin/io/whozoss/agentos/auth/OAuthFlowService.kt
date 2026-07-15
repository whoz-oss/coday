package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthCustomAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthDiscoverableAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthMcpDiscoverableAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionEvent
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KLogging
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class OAuthFlowService(
    private val pendingRegistry: OAuthPendingRegistry,
    private val credentialService: CredentialService,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val config: OAuthConfigProperties,
) {
    private val redirectUri: String get() = config.redirectUri

    suspend fun resolveOAuthCredential(
        userId: UUID,
        authSetting: AuthSetting,
        namespaceId: UUID,
        caseId: UUID,
        agentId: UUID,
        agentName: String,
        emitEvent: (CaseEvent) -> CaseEvent,
    ): Credential? {
        val authSettingId = authSetting.metadata.id
        val existing = credentialService.resolve(userId, authSettingId)
        if (existing != null && !isExpired(existing)) return existing
        if (existing != null) {
            val rt = existing.data["refreshToken"]?.takeIf { it.isNotBlank() }
            if (rt != null) {
                val ep = resolveEndpoints(authSetting) ?: return null
                // For MCP discoverable, check for stored dynamic client credentials
                val (effectiveClientId, effectiveClientSecret) = resolveClientCredentials(authSetting, existing)
                val refreshed = refreshAccessToken(ep.tokenEndpoint, rt, effectiveClientId, effectiveClientSecret)
                if (refreshed != null) {
                    val dynamicInfo = extractDynamicClientInfo(existing)
                    return credentialService.store(buildCredential(userId, authSettingId, refreshed, dynamicInfo))
                }
            }
        }
        return runInteractiveFlow(userId, authSetting, namespaceId, caseId, agentId, agentName, emitEvent)
    }

    private suspend fun runInteractiveFlow(
        userId: UUID,
        authSetting: AuthSetting,
        namespaceId: UUID,
        caseId: UUID,
        agentId: UUID,
        agentName: String,
        emitEvent: (CaseEvent) -> CaseEvent,
    ): Credential? {
        if (redirectUri.isBlank()) return null
        val endpoints = resolveEndpoints(authSetting) ?: return null
        val authSettingId = authSetting.metadata.id

        // Resolve client credentials — may trigger dynamic registration for MCP discoverable
        val existing = credentialService.resolve(userId, authSettingId)
        var dynamicClientInfo: DynamicClientInfo? = extractDynamicClientInfo(existing)
        val effectiveClientId: String
        val effectiveClientSecret: String

        if (authSetting is OAuthMcpDiscoverableAuthSetting && authSetting.clientId.isBlank()) {
            // Dynamic registration needed
            if (dynamicClientInfo == null && endpoints.registrationEndpoint != null) {
                dynamicClientInfo = registerDynamicClient(
                    registrationEndpoint = endpoints.registrationEndpoint,
                    redirectUri = redirectUri,
                    clientName = authSetting.name,
                )
                if (dynamicClientInfo == null) {
                    logger.error { "[OAuth] Dynamic client registration failed for ${authSetting.name}" }
                    return null
                }
            }
            effectiveClientId = dynamicClientInfo?.clientId ?: ""
            effectiveClientSecret = dynamicClientInfo?.clientSecret ?: ""
        } else {
            effectiveClientId = clientIdOf(authSetting)
            effectiveClientSecret = clientSecretOf(authSetting)
        }

        val pkce = generatePkce()
        val state = UUID.randomUUID().toString()
        val authorizationUrl = buildAuthorizationUrl(
            authorizationEndpoint = endpoints.authorizationEndpoint,
            clientId = effectiveClientId,
            redirectUri = redirectUri,
            state = state,
            codeChallenge = pkce.codeChallenge,
            scopes = scopesOf(authSetting),
            resource = endpoints.resource,
        )
        val future = pendingRegistry.register(state)
        emitEvent(QuestionEvent(namespaceId = namespaceId, caseId = caseId, agentId = agentId, agentName = agentName, question = authorizationUrl, options = null, questionType = QuestionType.OAUTH_AUTHORIZE))
        logger.info { "[OAuth] Interactive flow started for user=$userId state=$state" }
        val code: String? = withContext(Dispatchers.IO) {
            try { future.get(FLOW_TIMEOUT_MINUTES, TimeUnit.MINUTES) }
            catch (e: TimeoutException) { pendingRegistry.cancel(state); null }
            catch (e: CancellationException) { null }
            catch (e: Exception) { logger.error("[OAuth] Error waiting for callback", e); null }
        }
        if (code == null) return null
        val tokenResponse = exchangeCodeForTokens(endpoints.tokenEndpoint, code, effectiveClientId, effectiveClientSecret, redirectUri, pkce.codeVerifier) ?: return null
        return credentialService.store(buildCredential(userId, authSettingId, tokenResponse, dynamicClientInfo))
    }

    private fun resolveEndpoints(authSetting: AuthSetting): OAuthEndpoints? = when (authSetting) {
        is OAuthDiscoverableAuthSetting -> discoverEndpoints(authSetting.discoveryUrl)
        is OAuthRegisteredAuthSetting -> OAuthEndpoints(authSetting.authorizationUrl, authSetting.tokenUrl)
        is OAuthCustomAuthSetting -> OAuthEndpoints(authSetting.authorizationUrl, authSetting.tokenUrl)
        is OAuthMcpDiscoverableAuthSetting -> discoverMcpEndpoints(authSetting.resourceUrl)
        else -> null
    }

    private fun discoverEndpoints(discoveryUrl: String): OAuthEndpoints? = try {
        httpClient.newCall(Request.Builder().url(discoveryUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) { logger.error { "[OAuth] Discovery HTTP ${response.code}" }; return null }
            val body = response.body?.string() ?: return null
            val json = objectMapper.readTree(body)
            val auth = json["authorization_endpoint"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
            val token = json["token_endpoint"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
            OAuthEndpoints(auth, token)
        }
    } catch (e: Exception) { logger.error("[OAuth] Discovery failed", e); null }

    private fun discoverMcpEndpoints(resourceUrl: String): OAuthEndpoints? = try {
        // Step 1: RFC 9728 — Protected Resource Metadata
        val resourceUri = java.net.URI.create(resourceUrl)
        val origin = "${resourceUri.scheme}://${resourceUri.authority}"
        val path = resourceUri.path.takeIf { it.isNotBlank() && it != "/" } ?: ""
        val prmUrl = "$origin/.well-known/oauth-protected-resource$path"

        val prmJson = fetchJson(prmUrl) ?: return null
        val authServerUrl = prmJson["authorization_servers"]
            ?.firstOrNull()
            ?.asText()
            ?.takeIf { it.isNotBlank() } ?: return null
        val resource = prmJson["resource"]?.asText()?.takeIf { it.isNotBlank() }

        // Step 2: RFC 8414 — Authorization Server Metadata
        val asUri = java.net.URI.create(authServerUrl)
        val asOrigin = "${asUri.scheme}://${asUri.authority}"
        val asPath = asUri.path.takeIf { it.isNotBlank() && it != "/" } ?: ""
        val asmUrl = "$asOrigin/.well-known/oauth-authorization-server$asPath"

        val asmJson = fetchJson(asmUrl) ?: return null
        val authEndpoint = asmJson["authorization_endpoint"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val tokenEndpoint = asmJson["token_endpoint"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val registrationEndpoint = asmJson["registration_endpoint"]?.asText()?.takeIf { it.isNotBlank() }

        OAuthEndpoints(
            authorizationEndpoint = authEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = registrationEndpoint,
            resource = resource,
        )
    } catch (e: Exception) {
        logger.error("[OAuth] MCP discovery failed for $resourceUrl", e)
        null
    }

    private fun fetchJson(url: String): com.fasterxml.jackson.databind.JsonNode? = try {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                logger.error { "[OAuth] Fetch $url HTTP ${response.code}" }
                return null
            }
            val body = response.body?.string() ?: return null
            objectMapper.readTree(body)
        }
    } catch (e: Exception) { logger.error("[OAuth] Fetch failed: $url", e); null }

    private fun registerDynamicClient(
        registrationEndpoint: String,
        redirectUri: String,
        clientName: String,
    ): DynamicClientInfo? = try {
        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "client_name" to clientName,
                "redirect_uris" to listOf(redirectUri),
                "grant_types" to listOf("authorization_code", "refresh_token"),
                "response_types" to listOf("code"),
                "token_endpoint_auth_method" to "client_secret_post",
            )
        )
        val mediaType = "application/json".toMediaType()
        val body = requestBody.toRequestBody(mediaType)
        httpClient.newCall(Request.Builder().url(registrationEndpoint).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) {
                logger.error { "[OAuth] Dynamic registration HTTP ${response.code}" }
                return null
            }
            val json = objectMapper.readTree(response.body?.string() ?: return null)
            val cId = json["client_id"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
            val cSecret = json["client_secret"]?.asText()?.takeIf { it.isNotBlank() } ?: ""
            DynamicClientInfo(cId, cSecret)
        }
    } catch (e: Exception) { logger.error("[OAuth] Dynamic registration failed", e); null }

    private fun extractDynamicClientInfo(credential: Credential?): DynamicClientInfo? {
        val cId = credential?.data?.get("dynamicClientId")?.takeIf { it.isNotBlank() } ?: return null
        val cSecret = credential.data["dynamicClientSecret"] ?: ""
        return DynamicClientInfo(cId, cSecret)
    }

    /**
     * Resolve the effective client ID and secret for token refresh.
     * For MCP discoverable with empty clientId, checks stored dynamic client credentials.
     */
    private fun resolveClientCredentials(authSetting: AuthSetting, existing: Credential): Pair<String, String> {
        if (authSetting is OAuthMcpDiscoverableAuthSetting && authSetting.clientId.isBlank()) {
            val dynamic = extractDynamicClientInfo(existing)
            if (dynamic != null) return Pair(dynamic.clientId, dynamic.clientSecret)
        }
        return Pair(clientIdOf(authSetting), clientSecretOf(authSetting))
    }

    private fun exchangeCodeForTokens(tokenEndpoint: String, code: String, clientId: String, clientSecret: String, redirectUri: String, codeVerifier: String): TokenResponse? = try {
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("redirect_uri", redirectUri).add("client_id", clientId).add("client_secret", clientSecret).add("code_verifier", codeVerifier).build()
        httpClient.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).execute().use { parseTokenResponse(it, tokenEndpoint, "code exchange") }
    } catch (e: Exception) { logger.error("[OAuth] Token exchange failed", e); null }

    private fun refreshAccessToken(tokenEndpoint: String, refreshToken: String, clientId: String, clientSecret: String): TokenResponse? = try {
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", clientId).add("client_secret", clientSecret).build()
        httpClient.newCall(Request.Builder().url(tokenEndpoint).post(body).build()).execute().use { parseTokenResponse(it, tokenEndpoint, "token refresh") }
    } catch (e: Exception) { logger.error("[OAuth] Token refresh failed", e); null }

    private fun parseTokenResponse(response: okhttp3.Response, endpoint: String, operation: String): TokenResponse? {
        if (!response.isSuccessful) { logger.error { "[OAuth] $operation HTTP ${response.code}" }; return null }
        val body = response.body?.string() ?: return null
        return try {
            val json = objectMapper.readTree(body)
            val accessToken = json["access_token"]?.asText()?.takeIf { it.isNotBlank() } ?: return null
            TokenResponse(accessToken, json["refresh_token"]?.asText()?.takeIf { it.isNotBlank() }, json["expires_in"]?.asLong(), json["token_type"]?.asText(), json["scope"]?.asText())
        } catch (e: Exception) { logger.error("[OAuth] Parse failed", e); null }
    }

    internal fun generatePkce(): PkceParams {
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return PkceParams(codeVerifier, codeChallenge)
    }

    private fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        scopes: String?,
        resource: String? = null,
    ): String {
        val params = buildList {
            add("response_type=code")
            add("client_id=${enc(clientId)}")
            add("redirect_uri=${enc(redirectUri)}")
            add("state=${enc(state)}")
            add("code_challenge=${enc(codeChallenge)}")
            add("code_challenge_method=S256")
            if (!scopes.isNullOrBlank()) add("scope=${enc(scopes)}")
            if (!resource.isNullOrBlank()) add("resource=${enc(resource)}")
        }
        val sep = if (authorizationEndpoint.contains('?')) "&" else "?"
        return "$authorizationEndpoint$sep${params.joinToString("&")}"
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    internal fun isExpired(credential: Credential): Boolean {
        val s = credential.data["expiresAt"] ?: return true
        return try { Instant.now().isAfter(Instant.parse(s).minusSeconds(60)) } catch (e: Exception) { true }
    }

    private fun buildCredential(
        userId: UUID,
        authSettingId: UUID,
        t: TokenResponse,
        dynamicClientInfo: DynamicClientInfo? = null,
    ): Credential {
        val data = buildMap {
            put("accessToken", t.accessToken)
            put("refreshToken", t.refreshToken ?: "")
            put("expiresAt", Instant.now().plusSeconds(t.expiresIn ?: 3600L).toString())
            put("tokenType", t.tokenType ?: "Bearer")
            put("scope", t.scope ?: "")
            if (dynamicClientInfo != null) {
                put("dynamicClientId", dynamicClientInfo.clientId)
                put("dynamicClientSecret", dynamicClientInfo.clientSecret)
            }
        }
        return Credential(
            metadata = EntityMetadata(),
            userId = userId,
            authSettingId = authSettingId,
            credentialType = CredentialType.OAUTH_TOKENS,
            data = data,
        )
    }

    private fun clientIdOf(a: AuthSetting): String = when (a) {
        is OAuthDiscoverableAuthSetting -> a.clientId
        is OAuthRegisteredAuthSetting -> a.clientId
        is OAuthCustomAuthSetting -> a.clientId
        is OAuthMcpDiscoverableAuthSetting -> a.clientId
        else -> ""
    }

    private fun clientSecretOf(a: AuthSetting): String = when (a) {
        is OAuthDiscoverableAuthSetting -> a.clientSecret
        is OAuthRegisteredAuthSetting -> a.clientSecret
        is OAuthCustomAuthSetting -> a.clientSecret
        is OAuthMcpDiscoverableAuthSetting -> a.clientSecret
        else -> ""
    }

    private fun scopesOf(a: AuthSetting): String? = when (a) {
        is OAuthDiscoverableAuthSetting -> a.scopes
        is OAuthRegisteredAuthSetting -> a.scopes
        is OAuthCustomAuthSetting -> a.scopes
        is OAuthMcpDiscoverableAuthSetting -> a.scopes
        else -> null
    }

    companion object : KLogging() { const val FLOW_TIMEOUT_MINUTES = 5L }
}

private data class OAuthEndpoints(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String? = null,
    val resource: String? = null,
)

data class PkceParams(val codeVerifier: String, val codeChallenge: String)
private data class TokenResponse(val accessToken: String, val refreshToken: String?, val expiresIn: Long?, val tokenType: String?, val scope: String?)
private data class DynamicClientInfo(val clientId: String, val clientSecret: String)
