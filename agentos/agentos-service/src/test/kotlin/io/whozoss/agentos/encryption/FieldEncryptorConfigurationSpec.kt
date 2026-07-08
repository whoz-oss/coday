package io.whozoss.agentos.encryption

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [FieldEncryptorConfiguration].
 *
 * Because the configuration reads directly from [System.getenv], we use
 * [withEnv] to temporarily set environment-like values via system properties
 * as a shim — or, more precisely, we call the factory method directly with
 * controlled inputs by subclassing / using a test-friendly overload.
 *
 * Since [System.getenv] cannot be overridden in a standard JVM, we instead
 * test the configuration by subclassing it and overriding [getEnv] so that
 * the logic can be exercised without spawning a new process.
 */
class FieldEncryptorConfigurationSpec : StringSpec({
    timeout = 5000

    // Helper: a subclass that lets us inject environment values without
    // touching the real process environment.
    fun configurationWith(key: String?, salt: String?) =
        object : FieldEncryptorConfiguration() {
            override fun getEnv(name: String): String? = when (name) {
                FieldEncryptorConfiguration.ENV_KEY  -> key
                FieldEncryptorConfiguration.ENV_SALT -> salt
                else -> null
            }
        }

    "both vars present creates SpringFieldEncryptor" {
        val config = configurationWith(
            key  = "test-password-for-unit-tests",
            salt = "deadbeefcafe1234"
        )
        val encryptor = config.fieldEncryptor()

        encryptor.shouldBeInstanceOf<SpringFieldEncryptor>()
    }

    "both vars absent creates Base64FieldEncryptor" {
        val config = configurationWith(key = null, salt = null)
        val encryptor = config.fieldEncryptor()

        encryptor.shouldBeInstanceOf<Base64FieldEncryptor>()
    }

    "only key present throws IllegalStateException" {
        val config = configurationWith(
            key  = "only-key-no-salt",
            salt = null
        )
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "only salt present throws IllegalStateException" {
        val config = configurationWith(
            key  = null,
            salt = "deadbeefcafe1234"
        )
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "IllegalStateException message names the missing variable" {
        val config = configurationWith(key = "some-key", salt = null)
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }

        ex.message?.contains(FieldEncryptorConfiguration.ENV_SALT) shouldBe true
    }
})
