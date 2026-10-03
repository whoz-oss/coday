package io.whozoss.factory.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FakeIdpTest {

    private val secret = "test-secret"

    @Test
    fun `verifies a freshly issued JWT`() {
        val token = TestJwt.issueJwt(
            mapOf("sub" to "abc", "exp" to (System.currentTimeMillis() / 1000 + 60)),
            secret,
        )
        val result = FakeIdp.verifyJwt(token, secret)
        assertThat(result.valid).isTrue()
        assertThat(result.claims["sub"]).isEqualTo("abc")
    }

    @Test
    fun `rejects a JWT signed with another secret`() {
        val token = TestJwt.issueJwt(
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
    fun `verifies signed proxy headers`() {
        val headers = TestJwt.signProxyHeaders("p1", "human", listOf("a", "b"), secret = secret)
        val result = FakeIdp.verifyProxyHeaders(headers, secret)
        assertThat(result.valid).isTrue()
        assertThat(result.principalId).isEqualTo("p1")
        assertThat(result.scopes).containsExactly("a", "b")
    }

    @Test
    fun `rejects a stale proxy signature`() {
        val headers = TestJwt.signProxyHeaders(
            "p1",
            "human",
            timestamp = System.currentTimeMillis() - 10L * 60L * 1000L,
            secret = secret,
        )
        assertThat(FakeIdp.verifyProxyHeaders(headers, secret).valid).isFalse()
    }
}
