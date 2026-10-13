package io.whozoss.factory.agentattempt.domain

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of [CanonicalJsonHash]: recursive key sorting, compact JSON
 * and the `sha256:<hex>` digest contract of the Node domain.
 */
class CanonicalJsonHashTest {

    private val mapper = ObjectMapper()

    @Test
    fun `canonical JSON sorts object keys recursively`() {
        val node = mapper.readTree("""{"b":1,"a":{"d":2,"c":3},"z":[{"y":1,"x":2}]}""")

        assertThat(CanonicalJsonHash.canonicalJson(node))
            .isEqualTo("""{"a":{"c":3,"d":2},"b":1,"z":[{"x":2,"y":1}]}""")
    }

    @Test
    fun `hash is independent of key order`() {
        val first = mapper.readTree("""{"status":"PASS","summary":"ok","claims":{"modifiedFiles":[]}}""")
        val second = mapper.readTree("""{"claims":{"modifiedFiles":[]},"summary":"ok","status":"PASS"}""")

        assertThat(CanonicalJsonHash.hash(first)).isEqualTo(CanonicalJsonHash.hash(second))
        assertThat(CanonicalJsonHash.hash(first)).startsWith("sha256:")
    }

    @Test
    fun `sha256 matches the known empty-string digest`() {
        assertThat(CanonicalJsonHash.sha256(""))
            .isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(CanonicalJsonHash.sha256Hex(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `safeEqual is true only for identical strings`() {
        assertThat(CanonicalJsonHash.safeEqual("sha256:abc", "sha256:abc")).isTrue()
        assertThat(CanonicalJsonHash.safeEqual("sha256:abc", "sha256:abd")).isFalse()
        assertThat(CanonicalJsonHash.safeEqual("short", "a-much-longer-value")).isFalse()
    }

    @Test
    fun `safe identifier and brief hash grammar`() {
        assertThat(CanonicalJsonHash.isSafeId("attempt-1.2_A")).isTrue()
        assertThat(CanonicalJsonHash.isSafeId("bad id!")).isFalse()
        assertThat(CanonicalJsonHash.isSafeId("")).isFalse()
        assertThat(CanonicalJsonHash.isSafeId(null)).isFalse()
        assertThat(CanonicalJsonHash.isSafeId("x".repeat(129))).isFalse()

        assertThat(CanonicalJsonHash.isBriefHash("sha256:${"a".repeat(64)}")).isTrue()
        assertThat(CanonicalJsonHash.isBriefHash("sha256:not-hex")).isFalse()
        assertThat(CanonicalJsonHash.isBriefHash(null)).isFalse()
    }
}
