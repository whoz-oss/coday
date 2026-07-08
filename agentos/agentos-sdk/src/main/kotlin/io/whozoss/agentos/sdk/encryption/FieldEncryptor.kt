package io.whozoss.agentos.sdk.encryption

/**
 * Encrypts and decrypts sensitive field values at rest.
 * Implementations may use strong cryptography or provide obfuscation fallbacks.
 */
interface FieldEncryptor {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
