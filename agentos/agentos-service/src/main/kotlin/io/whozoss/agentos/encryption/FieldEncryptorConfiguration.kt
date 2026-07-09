package io.whozoss.agentos.encryption

import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the appropriate [FieldEncryptor] bean based on the presence of
 * [ENV_KEY] and [ENV_SALT] environment variables (or equivalent Spring properties).
 *
 * Resolution order (env vars take precedence over Spring properties):
 * - Both key and salt resolved → [SpringFieldEncryptor] (AES-256-GCM)
 * - Both absent               → [NoOpFieldEncryptor] (no encryption, WARN logged)
 * - Only one resolved         → fails fast with [IllegalStateException]
 *
 * Spring properties `agentos.encryption.key` and `agentos.encryption.salt` are
 * supported as an alternative to env vars, primarily to allow test profiles to
 * supply fixed credentials via `application-test.yml`.
 */
@Configuration
open class FieldEncryptorConfiguration {

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
    open fun fieldEncryptor(): FieldEncryptor {
        // Env vars take precedence; fall back to Spring properties.
        val key  = getEnv(ENV_KEY)?.takeIf  { it.isNotBlank() } ?: propertyKey.takeIf  { it.isNotBlank() }
        val salt = getEnv(ENV_SALT)?.takeIf { it.isNotBlank() } ?: propertySalt.takeIf { it.isNotBlank() }

        return when {
            key != null && salt != null -> {
                logger.info { "[Encryption] AES-256-GCM encryption configured" }
                SpringFieldEncryptor(key, salt)
            }

            key == null && salt == null -> {
                logger.warn {
                    "[Encryption] No encryption configured — using no-op encryptor. " +
                        "Sensitive data will be stored in PLAINTEXT. " +
                        "For production, set $ENV_KEY and $ENV_SALT environment variables."
                }
                NoOpFieldEncryptor()
            }

            else -> {
                val missing = if (key == null) "$ENV_KEY / agentos.encryption.key" else "$ENV_SALT / agentos.encryption.salt"
                throw IllegalStateException(
                    "Both encryption key and salt must be set, or neither. Missing: $missing"
                )
            }
        }
    }

    companion object : KLogging() {
        const val ENV_KEY  = "AGENTOS_ENCRYPTION_KEY"
        const val ENV_SALT = "AGENTOS_ENCRYPTION_SALT"
    }
}
