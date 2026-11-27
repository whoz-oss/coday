package io.whozoss.factory.settings

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Field encryption of the persisted secrets: JDK crypto only, plain text when no key is configured. */
class SecretCipherTest {

    private val token = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"

    @Test
    fun `without a key the value is stored and read in plain text`() {
        listOf("NONE" to "NONE", "" to "", "key" to "NONE", "NONE" to "salt", "  " to "salt").forEach { (key, salt) ->
            val cipher = SecretCipher(key, salt)

            assertThat(cipher.enabled).describedAs("$key/$salt").isFalse()
            assertThat(cipher.encrypt(token)).isEqualTo(token)
            assertThat(cipher.decrypt(token)).isEqualTo(token)
        }
    }

    @Test
    fun `with a key the value is sealed, versioned, salted per call and read back`() {
        val cipher = SecretCipher("a-strong-key", "0123456789abcdef")

        val first = cipher.encrypt(token)
        val second = cipher.encrypt(token)

        assertThat(cipher.enabled).isTrue()
        assertThat(first).startsWith("enc:v1:").doesNotContain(token)
        assertThat(first).isNotEqualTo(second)
        assertThat(cipher.decrypt(first)).isEqualTo(token)
        assertThat(cipher.decrypt(second)).isEqualTo(token)
    }

    @Test
    fun `a plain stored value stays readable once encryption is turned on`() {
        assertThat(SecretCipher("a-strong-key", "0123456789abcdef").decrypt(token)).isEqualTo(token)
    }

    @Test
    fun `a sealed value that cannot be opened is null, never an exception or garbage`() {
        val sealed = SecretCipher("a-strong-key", "0123456789abcdef").encrypt(token)

        assertThat(SecretCipher("another-key", "0123456789abcdef").decrypt(sealed)).isNull()
        assertThat(SecretCipher("a-strong-key", "fedcba9876543210").decrypt(sealed)).isNull()
        assertThat(SecretCipher("NONE", "NONE").decrypt(sealed)).isNull()
        assertThat(SecretCipher("a-strong-key", "0123456789abcdef").decrypt(sealed.dropLast(4) + "AAAA")).isNull()
        assertThat(SecretCipher("a-strong-key", "0123456789abcdef").decrypt("enc:v1:%%%")).isNull()
        assertThat(SecretCipher("a-strong-key", "0123456789abcdef").decrypt("enc:v1:")).isNull()
    }
}
