package io.whozoss.agentos.encryption

import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the appropriate [FieldEncryptor] bean based on the presence of
 * [ENV_KEY] and [ENV_SALT] environment variables.
 *
 * - Both present  → [SpringFieldEncryptor] (AES-256-GCM via Spring Security Crypto)
 * - Both absent   → [Base64FieldEncryptor] (obfuscation only — logs a WARN)
 * - Only one set  → fails fast with [IllegalStateException]
 */
@Configuration
class FieldEncryptorConfiguration {

    /**
     * Reads an environment variable by name. Extracted as an open method so that
     * tests can override it without spawning a new process or using a Java agent.
     */
    open fun getEnv(name: String): String? = System.getenv(name)

    @Bean
    fun fieldEncryptor(): FieldEncryptor {
        val key  = getEnv(ENV_KEY)
        val salt = getEnv(ENV_SALT)

        return when {
            key != null && salt != null -> {
                logger.info { "[Encryption] AES-256-GCM encryption configured via $ENV_KEY / $ENV_SALT" }
                SpringFieldEncryptor(key, salt)
            }

            key == null && salt == null -> {
                logger.warn {
                    "[Encryption] No encryption key configured, using Base64 obfuscation" +
                        " — NOT suitable for production. Set $ENV_KEY and $ENV_SALT to enable AES-256-GCM encryption."
                }
                Base64FieldEncryptor()
            }

            else -> {
                val missing = if (key == null) ENV_KEY else ENV_SALT
                throw IllegalStateException(
                    "Both $ENV_KEY and $ENV_SALT must be set, or neither. Missing: $missing"
                )
            }
        }
    }

    companion object : KLogging() {
        const val ENV_KEY  = "AGENTOS_ENCRYPTION_KEY"
        const val ENV_SALT = "AGENTOS_ENCRYPTION_SALT"
    }
}
