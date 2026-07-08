package io.whozoss.agentos.encryption

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

class Base64FieldEncryptorSpec : StringSpec({
    timeout = 5000

    val encryptor = Base64FieldEncryptor()

    "encrypt then decrypt returns the original plaintext" {
        val original = "some-value"
        encryptor.decrypt(encryptor.encrypt(original)) shouldBe original
    }

    "ciphertext is valid Base64" {
        val cipherText = encryptor.encrypt("hello")
        // If this throws, the string is not valid Base64
        val decoded = Base64.getDecoder().decode(cipherText)
        String(decoded, Charsets.UTF_8) shouldBe "hello"
    }

    "handles empty string" {
        val cipherText = encryptor.encrypt("")
        encryptor.decrypt(cipherText) shouldBe ""
    }

    "handles special characters and unicode" {
        val special = "p\u00e4\u00df\u00f6\u00fc\u20acword! @#\$%^&*()"
        encryptor.decrypt(encryptor.encrypt(special)) shouldBe special
    }
})
