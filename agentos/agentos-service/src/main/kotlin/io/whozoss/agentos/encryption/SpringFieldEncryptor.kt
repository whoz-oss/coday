package io.whozoss.agentos.encryption

import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor

/**
 * AES-256-GCM field encryptor backed by Spring Security Crypto.
 *
 * Uses [Encryptors.text] which applies AES-256 in GCM mode with a random IV per
 * encryption call, making ciphertexts non-deterministic (same plaintext → different
 * ciphertext each time).
 *
 * @param password the encryption key (from env var [AGENTOS_ENCRYPTION_KEY])
 * @param salt     hex-encoded salt (from env var [AGENTOS_ENCRYPTION_SALT])
 */
class SpringFieldEncryptor(
    password: String,
    salt: String,
) : FieldEncryptor {
    private val delegate: TextEncryptor = Encryptors.text(password, salt)

    override fun encrypt(plainText: String): String = delegate.encrypt(plainText)

    override fun decrypt(cipherText: String): String = delegate.decrypt(cipherText)
}
