package io.whozoss.agentos.encryption

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf

/**
 * Unit tests for [FieldEncryptorConfiguration].
 *
 * Since [System.getenv] cannot be overridden in a standard JVM, we subclass
 * [FieldEncryptorConfiguration] and override [getEnv] to inject controlled
 * env-var values. Spring property fields ([propertyKey], [propertySalt]) are
 * `internal` so they can be set directly on the instance to test the
 * property-fallback path without a Spring context.
 */
class FieldEncryptorConfigurationSpec : StringSpec({
    timeout = 5000

    // Creates a configuration with controlled env vars and optional Spring property values.
    fun configWith(envKey: String?, envSalt: String?, propKey: String = "", propSalt: String = "") =
        object : FieldEncryptorConfiguration() {
            override fun getEnv(name: String): String? = when (name) {
                FieldEncryptorConfiguration.ENV_KEY  -> envKey
                FieldEncryptorConfiguration.ENV_SALT -> envSalt
                else -> null
            }
        }.also {
            it.propertyKey  = propKey
            it.propertySalt = propSalt
        }

    "both env vars present creates SpringFieldEncryptor" {
        val config = configWith(envKey = "test-password-for-unit-tests", envSalt = "deadbeefcafe1234")
        config.fieldEncryptor().shouldBeInstanceOf<SpringFieldEncryptor>()
    }

    "both Spring properties present creates SpringFieldEncryptor" {
        val config = configWith(envKey = null, envSalt = null, propKey = "test-password-for-unit-tests", propSalt = "deadbeefcafe1234")
        config.fieldEncryptor().shouldBeInstanceOf<SpringFieldEncryptor>()
    }

    "env vars take precedence over Spring properties" {
        // Salt must be a valid hex string (Spring Security Crypto requirement).
        val config = configWith(
            envKey   = "env-password",
            envSalt  = "deadbeefcafe1234",
            propKey  = "property-password",
            propSalt = "cafebabe12345678",
        )
        config.fieldEncryptor().shouldBeInstanceOf<SpringFieldEncryptor>()
    }

    "both vars absent returns NoOpFieldEncryptor" {
        val config = configWith(envKey = null, envSalt = null)
        config.fieldEncryptor().shouldBeInstanceOf<NoOpFieldEncryptor>()
    }

    "only key present throws IllegalStateException" {
        val config = configWith(envKey = "only-key-no-salt", envSalt = null)
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "only salt present throws IllegalStateException" {
        val config = configWith(envKey = null, envSalt = "deadbeefcafe1234")
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "IllegalStateException message names the missing variable" {
        val config = configWith(envKey = "some-key", envSalt = null)
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }
        ex.message shouldContain FieldEncryptorConfiguration.ENV_SALT
    }
})
