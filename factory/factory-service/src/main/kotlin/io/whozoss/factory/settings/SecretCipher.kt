package io.whozoss.factory.settings

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field encryption of the secrets Factory persists (today: the GitHub token set from the Admin page).
 *
 * It uses the `factory.encryption.key` / `factory.encryption.salt` settings (`FACTORY_ENCRYPTION_KEY` /
 * `FACTORY_ENCRYPTION_SALT`), JDK classes only: PBKDF2WithHmacSHA256 derives an AES-256 key, AES/GCM seals each value
 * with a fresh random IV. A sealed value is `enc:v1:<base64(iv + ciphertext + tag)>`.
 *
 * Encryption is on only when both settings are non blank and not the `NONE` sentinel (same migration rule as the
 * Coday `secrets.ts`): otherwise values are stored and read in plain text. A plain stored value is always readable,
 * whatever the current settings. A sealed value that cannot be opened (wrong key, tampering, encryption now off)
 * yields null: the caller treats the secret as not set rather than failing.
 *
 * The key material is never exposed: this class has no accessor for it and no `toString()` that prints it.
 */
@Component
class SecretCipher(
    @Value("\${factory.encryption.key:NONE}") key: String,
    @Value("\${factory.encryption.salt:NONE}") salt: String,
) {
    /** True when values are sealed at rest. */
    val enabled: Boolean = key.isNotBlank() && salt.isNotBlank() && key != NONE && salt != NONE

    private val secretKey: SecretKeySpec? = if (enabled) derive(key, salt) else null
    private val random = SecureRandom()

    /** Seals [plain] when encryption is on, otherwise returns it unchanged. */
    fun encrypt(plain: String): String {
        val aesKey = secretKey ?: return plain
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(TAG_BITS, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(UTF_8)))
    }

    /** Opens a stored value: a sealed one when possible, a plain one as is, null when a sealed one cannot be opened. */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        val aesKey = secretKey ?: return null
        return runCatching {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            require(raw.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), UTF_8)
        }.getOrNull()
    }

    companion object {
        const val NONE = "NONE"
        const val PREFIX = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_BITS = 256

        private fun derive(key: String, salt: String): SecretKeySpec {
            val spec = PBEKeySpec(key.toCharArray(), salt.toByteArray(UTF_8), PBKDF2_ITERATIONS, KEY_BITS)
            try {
                val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                return SecretKeySpec(bytes, "AES")
            } finally {
                spec.clearPassword()
            }
        }
    }
}
