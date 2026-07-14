package io.whozoss.agentos.encryption

import io.whozoss.agentos.sdk.encryption.FieldEncryptor

/**
 * No-op field encryptor for development use only.
 * Values are stored as-is without encryption.
 * NOT suitable for production — sensitive data will be stored in plaintext.
 */
class NoOpFieldEncryptor : FieldEncryptor {
    override fun encrypt(plainText: String): String = plainText
    override fun decrypt(cipherText: String): String = cipherText
}
