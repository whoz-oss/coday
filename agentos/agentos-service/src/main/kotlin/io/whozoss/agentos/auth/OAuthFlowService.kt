package io.whozoss.agentos.auth

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthCustomAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthDiscoverableAuthSetting
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
                val refreshed = refreshAccessToken(ep.tokenEndpoint, rt, clientIdOf(authSetting), clientSecretOf(authSetting))
                if (refreshed != null) return credentialService.store(buildCredential(userId, authSettingId, refreshed))
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
        val pkce = generatePkce()
        val state = UUID.randomUUID().toString()
        val authorizationUrl = buildAuthorizationUrl(endpoints.authorizationEndpoint, clientIdOf(authSetting), redirectUri, state, pkce.codeChallenge, scopesOf(authSetting))
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
        val tokenResponse = exchangeCodeForTokens(endpoints.tokenEndpoint, code, clientIdOf(authSetting), clientSecretOf(authSetting), redirectUri, pkce.codeVerifier) ?: return null
        return credentialService.store(buildCredential(userId, authSettingId, tokenResponse))
    }

    private fun resolveEndpoints(authSetting: AuthSetting): OAuthEndpoints? = when (authSetting) {
        is OAuthDiscoverableAuthSetting -> discoverEndpoints(authSetting.discoveryUrl)
        is OAuthRegisteredAuthSetting -> OAuthEndpoints(authSetting.authorizationUrl, authSetting.tokenUrl)
        is OAuthCustomAuthSetting -> OAuthEndpoints(authSetting.authorizationUrl, authSetting.tokenUrl)
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

    private fun buildAuthorizationUrl(authorizationEndpoint: String, clientId: String, redirectUri: String, state: String, codeChallenge: String, scopes: String?): String {
        val params = buildList {
            add("response_type=code"); add("client_id=${enc(clientId)}"); add("redirect_uri=${enc(redirectUri)}"); add("state=${enc(state)}"); add("code_challenge=${enc(codeChallenge)}"); add("code_challenge_method=S256")
            if (!scopes.isNullOrBlank()) add("scope=${enc(scopes)}")
        }
        val sep = if (authorizationEndpoint.contains('?')) "&" else "?"
        return "$authorizationEndpoint$sep${params.joinToString("&")}"
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    internal fun isExpired(credential: Credential): Boolean {
        val s = credential.data["expiresAt"] ?: return true
        return try { Instant.now().isAfter(Instant.parse(s).minusSeconds(60)) } catch (e: Exception) { true }
    }

    private fun buildCredential(userId: UUID, authSettingId: UUID, t: TokenResponse): Credential =
        Credential(metadata = EntityMetadata(), userId = userId, authSettingId = authSettingId, credentialType = CredentialType.OAUTH_TOKENS, data = mapOf("accessToken" to t.accessToken, "refreshToken" to (t.refreshToken ?: ""), "expiresAt" to Instant.now().plusSeconds(t.expiresIn ?: 3600L).toString(), "tokenType" to (t.tokenType ?: "Bearer"), "scope" to (t.scope ?: "")))

    private fun clientIdOf(a: AuthSetting): String = when (a) { is OAuthDiscoverableAuthSetting -> a.clientId; is OAuthRegisteredAuthSetting -> a.clientId; is OAuthCustomAuthSetting -> a.clientId; else -> "" }
    private fun clientSecretOf(a: AuthSetting): String = when (a) { is OAuthDiscoverableAuthSetting -> a.clientSecret; is OAuthRegisteredAuthSetting -> a.clientSecret; is OAuthCustomAuthSetting -> a.clientSecret; else -> "" }
    private fun scopesOf(a: AuthSetting): String? = when (a) { is OAuthDiscoverableAuthSetting -> a.scopes; is OAuthRegisteredAuthSetting -> a.scopes; is OAuthCustomAuthSetting -> a.scopes; else -> null }

    companion object : KLogging() { const val FLOW_TIMEOUT_MINUTES = 5L }
}

private data class OAuthEndpoints(val authorizationEndpoint: String, val tokenEndpoint: String)
data class PkceParams(val codeVerifier: String, val codeChallenge: String)
private data class TokenResponse(val accessToken: String, val refreshToken: String?, val expiresIn: Long?, val tokenType: String?, val scope: String?)
