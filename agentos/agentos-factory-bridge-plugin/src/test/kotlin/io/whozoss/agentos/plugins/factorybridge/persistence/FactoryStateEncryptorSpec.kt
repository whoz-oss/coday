package io.whozoss.agentos.plugins.factorybridge.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * The encryption decision table of [FactoryStateEncryptor].
 *
 * The contract mirrors the host's `FieldEncryptorConfiguration`: encryption is either
 * configured or **explicitly** disabled, never silently absent. An inconsistent key/salt
 * pair fails fast — the failure mode that matters, because the alternative is writing
 * secrets in plaintext while believing they are encrypted.
 *
 * These tests exercise [FactoryStateEncryptor.from] over explicit values rather than
 * [FactoryStateEncryptor.fromEnvironment]. Driving the rule through system properties
 * would make the outcome depend on process-wide state: `fromEnvironment` falls back to
 * `System.getenv`, which a test cannot clear, so a developer machine or CI runner that
 * exports `AGENTOS_ENCRYPTION_*` would silently change what the test observes. Resolution
 * (property, then environment) is a thin, separately trivial concern; the decision table
 * is the part worth pinning down, and it is pure.
 */
class FactoryStateEncryptorSpec : StringSpec({
    val realKey = "a-strong-random-key-of-sufficient-length"
    val realSalt = "0123456789abcdef"

    "a real key and salt yield an enabled encryptor" {
        FactoryStateEncryptor.from(realKey, realSalt).enabled shouldBe true
    }

    "both set to NONE disable encryption explicitly, case-insensitively" {
        FactoryStateEncryptor.from("NONE", "none").enabled shouldBe false
        FactoryStateEncryptor.from("none", "NONE").enabled shouldBe false
    }

    "a half-configured pair fails fast rather than silently storing plaintext" {
        shouldThrow<IllegalStateException> { FactoryStateEncryptor.from(realKey, null) }
            .message!! shouldContain FactoryStateEncryptor.ENV_SALT
        shouldThrow<IllegalStateException> { FactoryStateEncryptor.from(null, realSalt) }
            .message!! shouldContain FactoryStateEncryptor.ENV_KEY
    }

    "a NONE sentinel mixed with a real value fails fast" {
        shouldThrow<IllegalStateException> { FactoryStateEncryptor.from("NONE", realSalt) }
        shouldThrow<IllegalStateException> { FactoryStateEncryptor.from(realKey, "NONE") }
    }

    "an absent configuration fails fast — there is no implicit default" {
        shouldThrow<IllegalStateException> { FactoryStateEncryptor.from(null, null) }
            .message!! shouldContain FactoryStateEncryptor.NONE_SENTINEL
    }

    "the disabled encryptor is a passthrough in both directions" {
        val passthrough = FactoryStateEncryptor.disabled()
        passthrough.encrypt("value") shouldBe "value"
        passthrough.decrypt("value") shouldBe "value"
    }

    "decrypt returns null on undecryptable input rather than throwing" {
        FactoryStateEncryptor.of(realKey, realSalt).decrypt("not-valid-ciphertext") shouldBe null
    }

    "a value encrypted with one key is unreadable with another" {
        val cipher = FactoryStateEncryptor.of(realKey, realSalt).encrypt("secret")
        cipher shouldNotBe "secret"
        FactoryStateEncryptor.of("a-different-strong-random-key-value-here", "fedcba9876543210").decrypt(cipher) shouldBe null
    }

    "a round-trip through the same key recovers the plaintext" {
        val encryptor = FactoryStateEncryptor.of(realKey, realSalt)
        encryptor.decrypt(encryptor.encrypt("secret")) shouldBe "secret"
    }
})
