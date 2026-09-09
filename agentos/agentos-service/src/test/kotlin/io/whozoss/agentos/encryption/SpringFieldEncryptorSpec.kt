package io.whozoss.agentos.encryption

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty

/**
 * Unit tests for [SpringFieldEncryptor].
 *
 * Uses a fixed test password and hex-encoded salt (8 hex chars = 4 bytes,
 * which is the minimum required by Spring Security Crypto).
 */
class SpringFieldEncryptorSpec : StringSpec({
    timeout = 5000

    // Spring Security Crypto requires the salt to be a hex-encoded string.
    // 16 hex chars = 8 bytes, well above the 8-hex-char minimum.
    val password = "test-password-for-unit-tests"
    val salt = "deadbeefcafe1234"
    val encryptor = SpringFieldEncryptor(password, salt)

    "encrypt then decrypt returns the original plaintext" {
        val original = "sensitive-value"
        val cipherText = encryptor.encrypt(original)
        val decrypted = encryptor.decrypt(cipherText)

        decrypted shouldBe original
    }

    "different plaintexts produce different ciphertexts" {
        val cipher1 = encryptor.encrypt("value-one")
        val cipher2 = encryptor.encrypt("value-two")

        cipher1 shouldNotBe cipher2
    }

    "ciphertext differs from the original plaintext" {
        val plainText = "my-secret"
        val cipherText = encryptor.encrypt(plainText)

        cipherText shouldNotBe plainText
    }

    "same plaintext encrypted twice yields different ciphertexts (random IV)" {
        val plainText = "same-value"
        val cipher1 = encryptor.encrypt(plainText)
        val cipher2 = encryptor.encrypt(plainText)

        // AES-GCM uses a random IV per call, so ciphertexts must differ
        cipher1 shouldNotBe cipher2
        // But both must decrypt to the same original
        encryptor.decrypt(cipher1) shouldBe plainText
        encryptor.decrypt(cipher2) shouldBe plainText
    }

    "handles empty string" {
        val cipherText = encryptor.encrypt("")
        cipherText.shouldNotBeEmpty()
        encryptor.decrypt(cipherText) shouldBe ""
    }

    "handles long strings" {
        val long = "a".repeat(10_000)
        val cipherText = encryptor.encrypt(long)
        encryptor.decrypt(cipherText) shouldBe long
    }

    "handles special characters and unicode" {
        val special = "p\u00e4\u00df\u00f6\u00fc\u20acword! @#\$%^&*()"
        val cipherText = encryptor.encrypt(special)
        encryptor.decrypt(cipherText) shouldBe special
    }
})
