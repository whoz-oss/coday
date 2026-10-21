package io.whozoss.factory.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FakeIdpTest {

    private val secret = "test-secret"

    @Test
    fun `verifies a freshly issued JWT`() {
        val token = FakeIdp.issueJwt(
            mapOf("sub" to "abc", "exp" to (System.currentTimeMillis() / 1000 + 60)),
            secret,
        )
        val result = FakeIdp.verifyJwt(token, secret)
        assertThat(result.valid).isTrue()
        assertThat(result.claims["sub"]).isEqualTo("abc")
    }

    @Test
    fun `rejects a JWT signed with another secret`() {
        val token = FakeIdp.issueJwt(
            mapOf("sub" to "abc", "exp" to (System.currentTimeMillis() / 1000 + 60)),
            "other-secret",
        )
        assertThat(FakeIdp.verifyJwt(token, secret).valid).isFalse()
    }

    @Test
    fun `rejects a malformed token`() {
        assertThat(FakeIdp.verifyJwt("not-a-jwt", secret).valid).isFalse()
        assertThat(FakeIdp.verifyJwt("a.b", secret).valid).isFalse()
    }

    @Test
    fun `rejects an expired token`() {
        val token = FakeIdp.issueJwt(
            mapOf("sub" to "abc", "exp" to (System.currentTimeMillis() / 1000 - 10)),
            secret,
        )
        assertThat(FakeIdp.verifyJwt(token, secret).reason).isEqualTo("expired")
    }
}
