package io.whozoss.factory.verification.redaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretRedactionTest {

    private data class Case(val name: String, val input: String, val secret: String)

    private val cases = listOf(
        Case("assignment", "GITHUB_TOKEN=ghs_notReallyASecret done", "ghs_notReallyASecret"),
        Case("api key assignment", "my_API_KEY = hunter2", "hunter2"),
        Case("authorization header", "Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz"),
        Case("bearer", "curl -H 'x' Bearer abc.def.ghi", "abc.def.ghi"),
        Case("classic github token", "cloned with ghp_" + "a".repeat(36), "ghp_" + "a".repeat(36)),
        Case("server github token", "ghs_" + "B".repeat(40), "B".repeat(40)),
        Case("fine grained pat", "github_pat_" + "A1_".repeat(10), "A1_".repeat(10)),
        Case("jwt", "cookie eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl end", "eyJzdWIiOiIxMjM0In0"),
        Case("aws access key", "id AKIAIOSFODNN7EXAMPLE end", "AKIAIOSFODNN7EXAMPLE"),
        Case("slack token", "hook xoxb-1234567890-abcdef end", "xoxb-1234567890-abcdef"),
        Case(
            "private key block",
            "before\n-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\nline2\n-----END RSA PRIVATE KEY-----\nafter",
            "MIIEowIBAAKCAQEA",
        ),
    )

    @Test
    fun `every known secret shape is removed`() {
        for (case in cases) {
            val redacted = SecretRedaction.redact(case.input)
            assertFalse(redacted.contains(case.secret), "${case.name}: secret survived in <$redacted>")
        }
    }

    @Test
    fun `an assignment keeps its name and a plain text is untouched`() {
        assertEquals("TOKEN=<redacted> ./test", SecretRedaction.redact("TOKEN=abc ./test"))
        assertEquals("nothing secret here", SecretRedaction.redact("nothing secret here"))
    }

    @Test
    fun `the text around a private key block is preserved`() {
        val redacted = SecretRedaction.redact(cases.last().input)
        assertEquals("before\n<redacted>\nafter", redacted)
    }
}
