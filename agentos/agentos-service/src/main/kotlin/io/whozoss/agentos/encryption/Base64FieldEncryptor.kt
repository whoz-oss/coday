package io.whozoss.agentos.encryption

import io.whozoss.agentos.sdk.encryption.FieldEncryptor
import java.util.Base64

/**
 * Base64 obfuscation fallback for [FieldEncryptor].
 *
 * ⚠️ This is NOT cryptographically secure. It is intended solely for local
 * development environments where no encryption key has been configured.
 * Never use this in production.
 */
class Base64FieldEncryptor : FieldEncryptor {
    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    override fun encrypt(plainText: String): String =
        encoder.encodeToString(plainText.toByteArray(Charsets.UTF_8))

    override fun decrypt(cipherText: String): String =
        String(decoder.decode(cipherText), Charsets.UTF_8)
}
