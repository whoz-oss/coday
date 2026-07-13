package io.whozoss.agentos.auth

import okhttp3.OkHttpClient
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

/**
 * Configuration properties for the OAuth 2.1 flow.
 *
 * Bound from the `agentos.oauth` prefix (Spring relaxed binding).
 * Override with env var: AGENTOS_OAUTH_REDIRECT_URI
 */
@ConfigurationProperties(prefix = "agentos.oauth")
data class OAuthConfigProperties(
    /**
     * Absolute redirect URI sent to the OAuth provider as redirect_uri.
     * Must match the frontend OAuth callback page URL exactly.
     * Example: https://app.example.com/oauth/callback
     * When blank, interactive OAuth flows are disabled.
     */
    val redirectUri: String = "",
)

/**
 * Spring configuration for OAuth 2.1 infrastructure.
 *
 * Provides a shared [OkHttpClient] bean used by [OAuthFlowService] for discovery
 * and token-exchange HTTP calls. Timeouts are set conservatively: OAuth token
 * endpoints are external services and can be slow under load.
 */
@Configuration
@EnableConfigurationProperties(OAuthConfigProperties::class)
class OAuthConfiguration {

    @Bean
    fun oauthHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
}
