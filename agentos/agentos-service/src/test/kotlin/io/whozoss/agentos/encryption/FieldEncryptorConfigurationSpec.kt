package io.whozoss.agentos.encryption

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Unit tests for [FieldEncryptorConfiguration].
 *
 * [FieldEncryptorConfiguration.key] and [FieldEncryptorConfiguration.salt] are
 * `internal`, so they can be set directly on an instance to supply controlled
 * values without a Spring context.
 */
class FieldEncryptorConfigurationSpec : StringSpec({
    timeout = 5000

    fun configWith(key: String = "", salt: String = "") =
        FieldEncryptorConfiguration().also {
            it.key  = key
            it.salt = salt
        }

    "both key and salt present with real values creates SpringFieldEncryptor" {
        val config = configWith(key = "test-password-for-unit-tests", salt = "deadbeefcafe1234")
        config.fieldEncryptor().shouldBeInstanceOf<SpringFieldEncryptor>()
    }

    "both set to NONE returns NoOpFieldEncryptor" {
        val config = configWith(key = "NONE", salt = "NONE")
        config.fieldEncryptor().shouldBeInstanceOf<NoOpFieldEncryptor>()
    }

    "both set to NONE case-insensitive returns NoOpFieldEncryptor" {
        val config = configWith(key = "none", salt = "none")
        config.fieldEncryptor().shouldBeInstanceOf<NoOpFieldEncryptor>()
    }

    "both vars absent throws IllegalStateException" {
        val config = configWith()
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }
        ex.message shouldContain FieldEncryptorConfiguration.NONE_SENTINEL
    }

    "only key present throws IllegalStateException" {
        val config = configWith(key = "only-key-no-salt")
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "only salt present throws IllegalStateException" {
        val config = configWith(salt = "deadbeefcafe1234")
        shouldThrow<IllegalStateException> { config.fieldEncryptor() }
    }

    "key=NONE salt=real value throws IllegalStateException" {
        val config = configWith(key = "NONE", salt = "deadbeefcafe1234")
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }
        ex.message shouldContain FieldEncryptorConfiguration.ENV_KEY
    }

    "key=real value salt=NONE throws IllegalStateException" {
        val config = configWith(key = "some-real-key", salt = "NONE")
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }
        ex.message shouldContain FieldEncryptorConfiguration.ENV_SALT
    }

    "IllegalStateException message names the missing variable" {
        val config = configWith(key = "some-key")
        val ex = shouldThrow<IllegalStateException> { config.fieldEncryptor() }
        ex.message shouldContain FieldEncryptorConfiguration.ENV_SALT
    }
})
