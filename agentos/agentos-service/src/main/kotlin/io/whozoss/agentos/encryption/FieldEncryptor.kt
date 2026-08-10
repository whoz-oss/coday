package io.whozoss.agentos.encryption

/**
 * Encrypts and decrypts sensitive field values at rest.
 *
 * This is an internal service concern — plugins never interact with encryption
 * directly; they receive pre-decrypted credentials via [CredentialProvider].
 *
 * Implementations may use strong cryptography ([SpringFieldEncryptor]) or
 * provide a development-only passthrough ([NoOpFieldEncryptor]).
 */
interface FieldEncryptor {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}
