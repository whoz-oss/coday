package io.whozoss.agentos.encryption

import mu.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the appropriate [FieldEncryptor] bean based on the presence of
 * [ENV_KEY] and [ENV_SALT] environment variables (or equivalent Spring properties).
 *
 * Resolution order (env vars take precedence over Spring properties):
 * - Both key and salt resolve to real values → [SpringFieldEncryptor] (AES-256-GCM)
 * - Both key and salt set to `NONE` (case-insensitive) → [NoOpFieldEncryptor] (no encryption, WARN logged)
 * - Any other case → fails fast with [IllegalStateException]
 *
 * **You must explicitly configure encryption.** There is no silent fallback to no-encryption.
 * To opt out of encryption (e.g. for local development), set both [ENV_KEY] and [ENV_SALT]
 * to the sentinel value `NONE` (case-insensitive).
 *
 * Spring properties `agentos.encryption.key` and `agentos.encryption.salt` are
 * supported as an alternative to env vars, primarily to allow test profiles to
 * supply fixed credentials via `application-test.yml`.
 *
 * ## Key requirements
 *
 * - [ENV_KEY] must be a **strong random key** (≥ 32 characters),
 *   **not** a human-memorable password. Use a cryptographically random generator,
 *   e.g. `openssl rand -base64 32`.
 * - [ENV_SALT] must be a **hex-encoded string** (≥ 16 hex characters = 8 bytes),
 *   e.g. `openssl rand -hex 16`.
 * - Both can be set to `NONE` (case-insensitive) to **explicitly disable encryption**
 *   for development or testing. This is a conscious opt-out, not a default.
 * - In production, always use strong random values — never `NONE`.
 */
@Configuration
class FieldEncryptorConfiguration {

    @Value("\${agentos.encryption.key:}")
    internal var propertyKey: String = ""

    @Value("\${agentos.encryption.salt:}")
    internal var propertySalt: String = ""

    /**
     * Reads an environment variable by name. Extracted as an open method so that
     * tests can override it without spawning a new process or using a Java agent.
     */
    open fun getEnv(name: String): String? = System.getenv(name)

    @Bean
    fun fieldEncryptor(): FieldEncryptor {
        // Env vars take precedence; fall back to Spring properties.
        val rawKey  = getEnv(ENV_KEY)?.takeIf  { it.isNotBlank() } ?: propertyKey.takeIf  { it.isNotBlank() }
        val rawSalt = getEnv(ENV_SALT)?.takeIf { it.isNotBlank() } ?: propertySalt.takeIf { it.isNotBlank() }

        val keyIsNone  = rawKey?.equals(NONE_SENTINEL, ignoreCase = true) == true
        val saltIsNone = rawSalt?.equals(NONE_SENTINEL, ignoreCase = true) == true

        return when {
            !keyIsNone && !saltIsNone && rawKey != null && rawSalt != null -> {
                logger.info { "[Encryption] AES-256-GCM encryption configured" }
                SpringFieldEncryptor(rawKey, rawSalt)
            }

            keyIsNone && saltIsNone -> {
                logger.warn {
                    "[Encryption] No encryption configured \u2014 using no-op encryptor. " +
                        "Sensitive data will be stored in PLAINTEXT. " +
                        "To enable encryption, set $ENV_KEY and $ENV_SALT to real values."
                }
                NoOpFieldEncryptor()
            }

            else -> {
                val detail = when {
                    rawKey == null && rawSalt == null ->
                        "Both $ENV_KEY and $ENV_SALT are absent. " +
                            "Set them to real values to enable encryption, " +
                            "or to '$NONE_SENTINEL' to explicitly disable it."
                    rawKey == null ->
                        "$ENV_KEY is absent but $ENV_SALT is set. " +
                            "Both must be provided together."
                    rawSalt == null ->
                        "$ENV_SALT is absent but $ENV_KEY is set. " +
                            "Both must be provided together."
                    keyIsNone ->
                        "$ENV_KEY is set to '$NONE_SENTINEL' but $ENV_SALT is a real value. " +
                            "Both must be '$NONE_SENTINEL' to disable encryption."
                    else ->
                        "$ENV_SALT is set to '$NONE_SENTINEL' but $ENV_KEY is a real value. " +
                            "Both must be '$NONE_SENTINEL' to disable encryption."
                }
                throw IllegalStateException("[Encryption] Misconfiguration: $detail")
            }
        }
    }

    companion object : KLogging() {
        const val ENV_KEY  = "AGENTOS_ENCRYPTION_KEY"
        const val ENV_SALT = "AGENTOS_ENCRYPTION_SALT"

        /** Sentinel value: set both [ENV_KEY] and [ENV_SALT] to this string (case-insensitive) to explicitly disable encryption. */
        const val NONE_SENTINEL = "NONE"
    }
}
