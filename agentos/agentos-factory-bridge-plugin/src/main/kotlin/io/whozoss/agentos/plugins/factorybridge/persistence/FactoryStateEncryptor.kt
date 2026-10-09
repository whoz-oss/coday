package io.whozoss.agentos.plugins.factorybridge.persistence

import mu.KLogging
import org.springframework.security.crypto.encrypt.Encryptors

/**
 * Encrypts the secret-bearing fields of the Factory Bridge durable state.
 *
 * This is the plugin-side counterpart of the host's
 * `io.whozoss.agentos.encryption.FieldEncryptor`. It deliberately mirrors that contract
 * and reads the **same** `AGENTOS_ENCRYPTION_KEY` / `AGENTOS_ENCRYPTION_SALT`
 * configuration, so a deployment configures encryption once and both the service and this
 * plugin honour it. It is a separate type only because `FieldEncryptor` lives in
 * `agentos-service`, which the plugin does not — and must not — depend on.
 *
 * Like the host, it uses [Encryptors.text]: AES-256 in GCM mode with a random IV per call,
 * so the same plaintext yields a different ciphertext each time.
 *
 * ### Resolution, identical to the host's `FieldEncryptorConfiguration`
 *
 * - both key and salt set to real values → AES-256-GCM
 * - both set to `NONE` (case-insensitive) → passthrough, WARN logged
 * - anything else → [IllegalStateException]
 *
 * There is no silent fallback to plaintext: opting out is explicit, exactly as in the host.
 *
 * ### Reading back
 *
 * [decrypt] returns `null` when the ciphertext cannot be decrypted — a rotated key, a
 * state file written while encryption was disabled, or a corrupt value. The caller drops
 * the affected binding, which is **fail-closed**: a capability that cannot be read cannot
 * be redeemed, and the Factory can reissue one. Resurrecting a half-readable binding would
 * be the dangerous outcome, not losing it.
 */
class FactoryStateEncryptor private constructor(
    private val delegate: ((String) -> String)?,
    private val undelegate: ((String) -> String)?,
) {
    /** `true` when values are actually encrypted at rest. */
    val enabled: Boolean get() = delegate != null

    fun encrypt(plainText: String): String = delegate?.invoke(plainText) ?: plainText

    /** Returns the plaintext, or `null` when the value cannot be decrypted (fail-closed). */
    fun decrypt(cipherText: String): String? {
        // Bound to a local: the nullable function type is not smart-cast inside the
        // runCatching lambda.
        val decryptor = undelegate ?: return cipherText
        return runCatching { decryptor(cipherText) }
            .onFailure { logger.warn { "Factory bridge state: a persisted secret could not be decrypted — dropping it (fail-closed)" } }
            .getOrNull()
    }

    companion object : KLogging() {
        const val ENV_KEY = "AGENTOS_ENCRYPTION_KEY"
        const val ENV_SALT = "AGENTOS_ENCRYPTION_SALT"
        const val PROPERTY_KEY = "agentos.encryption.key"
        const val PROPERTY_SALT = "agentos.encryption.salt"

        /** Sentinel: both key and salt set to this (case-insensitive) explicitly disables encryption. */
        const val NONE_SENTINEL = "NONE"

        /** Passthrough encryptor, for unit tests and in-memory stores. */
        fun disabled(): FactoryStateEncryptor = FactoryStateEncryptor(null, null)

        /** AES-256-GCM encryptor from an explicit key/salt pair. */
        fun of(
            key: String,
            salt: String,
        ): FactoryStateEncryptor {
            val encryptor = Encryptors.text(key, salt)
            return FactoryStateEncryptor(encryptor::encrypt, encryptor::decrypt)
        }

        /**
         * Resolves the encryptor from the host's encryption configuration (system
         * property first, then environment variable).
         *
         * @throws IllegalStateException when key and salt are inconsistent — same
         *   fail-fast contract as the host's `FieldEncryptorConfiguration`.
         */
        fun fromEnvironment(): FactoryStateEncryptor =
            from(resolve(PROPERTY_KEY, ENV_KEY), resolve(PROPERTY_SALT, ENV_SALT))

        /**
         * The resolution rule itself, over explicit values.
         *
         * Separate from [fromEnvironment] so the decision table can be exercised without
         * touching process-wide state: a unit test that mutates system properties or
         * depends on the ambient environment is testing the JVM as much as the rule, and
         * is at the mercy of whatever the surrounding shell happens to export.
         *
         * @param key the encryption key, or `null` when unset/blank
         * @param salt the hex-encoded salt, or `null` when unset/blank
         */
        fun from(
            key: String?,
            salt: String?,
        ): FactoryStateEncryptor {
            val keyIsNone = key?.equals(NONE_SENTINEL, ignoreCase = true) == true
            val saltIsNone = salt?.equals(NONE_SENTINEL, ignoreCase = true) == true

            return when {
                key != null && salt != null && !keyIsNone && !saltIsNone -> {
                    logger.info { "[FactoryBridge] AES-256-GCM encryption configured for durable state" }
                    of(key, salt)
                }

                keyIsNone && saltIsNone -> {
                    logger.warn {
                        "[FactoryBridge] No encryption configured — capability tokens will be stored in PLAINTEXT " +
                            "at rest. Set $ENV_KEY and $ENV_SALT to real values to enable encryption."
                    }
                    disabled()
                }

                else -> throw IllegalStateException(
                    "[FactoryBridge] Encryption misconfiguration: " +
                        when {
                            key == null && salt == null ->
                                "both $ENV_KEY and $ENV_SALT are absent. Set them to real values, " +
                                    "or to '$NONE_SENTINEL' to explicitly disable encryption."
                            key == null -> "$ENV_KEY is absent but $ENV_SALT is set. Both must be provided together."
                            salt == null -> "$ENV_SALT is absent but $ENV_KEY is set. Both must be provided together."
                            keyIsNone -> "$ENV_KEY is '$NONE_SENTINEL' but $ENV_SALT is a real value. Both must be '$NONE_SENTINEL'."
                            else -> "$ENV_SALT is '$NONE_SENTINEL' but $ENV_KEY is a real value. Both must be '$NONE_SENTINEL'."
                        },
                )
            }
        }

        private fun resolve(
            systemProperty: String,
            environmentVariable: String,
        ): String? =
            System.getProperty(systemProperty)?.takeIf { it.isNotBlank() }
                ?: System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }
    }
}
