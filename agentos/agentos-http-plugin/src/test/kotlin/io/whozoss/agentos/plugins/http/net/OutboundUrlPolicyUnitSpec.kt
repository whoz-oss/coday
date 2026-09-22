package io.whozoss.agentos.plugins.http.net

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress

class OutboundUrlPolicyUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val policy = OutboundUrlPolicy()

    "accepts a public https URL" {
        val check = policy.validate("https://api.example.com/v2")
        check.shouldBeInstanceOf<UrlCheck.Ok>().uri.host shouldBe "api.example.com"
    }

    "rejects http scheme" {
        val check = policy.validate("http://api.example.com")
        check.shouldBeInstanceOf<UrlCheck.Rejected>().reason shouldContain "https"
    }

    "rejects a relative URL" {
        policy.validate("/relative/path").shouldBeInstanceOf<UrlCheck.Rejected>()
    }

    "rejects an unparsable URL with a reason that composes after a subject" {
        policy.validate("https://exa mple.com").shouldBeInstanceOf<UrlCheck.Rejected>().reason shouldBe
            "is not a valid URL"
    }

    "rejects userinfo without echoing the credentials" {
        val check = policy.validate("https://user:s3cret@api.example.com")
        val reason = check.shouldBeInstanceOf<UrlCheck.Rejected>().reason
        reason shouldContain "userinfo"
        reason shouldNotContain "s3cret"
    }

    "never echoes the query string in a rejection reason" {
        val check = policy.validate("http://api.example.com/openapi.yaml?token=s3cret")
        check.shouldBeInstanceOf<UrlCheck.Rejected>().reason shouldNotContain "s3cret"
    }

    "rejects an empty host" {
        policy.validate("https:///path").shouldBeInstanceOf<UrlCheck.Rejected>()
    }

    "rejects a port the HTTP client refuses although java.net.URI parses it, without echoing the query" {
        val check = policy.validate("https://api.example.com:99999/v2?token=s3cret")
        val reason = check.shouldBeInstanceOf<UrlCheck.Rejected>().reason
        reason shouldContain "port"
        reason shouldContain "99999"
        reason shouldNotContain "s3cret"
    }

    "rejects localhost" {
        val check = policy.validate("https://localhost/api")
        check.shouldBeInstanceOf<UrlCheck.Rejected>().reason shouldContain "localhost"
    }

    listOf(
        "127.0.0.1",
        "10.0.0.1",
        "172.16.5.5",
        "192.168.1.1",
        "169.254.169.254",
        "100.64.0.1",
        "0.0.0.0",
        "0.0.0.1",
        "[::1]",
        "[fd00::1]",
        "[fe80::1]",
        "[::ffff:10.0.0.1]",
        "[::ffff:127.0.0.1]",
    ).forEach { host ->
        "rejects literal private or loopback host $host" {
            policy.validate("https://$host/api").shouldBeInstanceOf<UrlCheck.Rejected>()
        }
    }

    listOf("[fe80::zz]", "999.1.1.1", "1.2.3.4.5").forEach { host ->
        "rejects the malformed IP-looking host $host" {
            policy.validate("https://$host/api").shouldBeInstanceOf<UrlCheck.Rejected>()
        }
    }

    listOf("[fe80::1%25eth0]", "[fe80::1%eth0]").forEach { host ->
        "ignores the zone id of $host and still rejects the link-local address" {
            val check = policy.validate("https://$host/api")
            check.shouldBeInstanceOf<UrlCheck.Rejected>().reason shouldContain "link-local"
        }
    }

    "accepts a host name without resolving it" {
        policy.validate("https://no-such-host.invalid/api").shouldBeInstanceOf<UrlCheck.Ok>()
    }

    "accepts a literal public IPv4 host" {
        policy.validate("https://93.184.216.34/api").shouldBeInstanceOf<UrlCheck.Ok>()
    }

    "isDisallowedAddress flags private and loopback addresses" {
        policy.isDisallowedAddress(InetAddress.getByName("127.0.0.1")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("10.1.2.3")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("100.127.255.254")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("0.255.255.255")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("fc00::1")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("::ffff:192.168.0.1")) shouldBe true
        policy.isDisallowedAddress(InetAddress.getByName("93.184.216.34")) shouldBe false
        policy.isDisallowedAddress(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")) shouldBe false
    }

    "test seam accepts loopback and localhost" {
        val lenient = OutboundUrlPolicy(allowLoopbackForTests = true)
        lenient.validate("https://127.0.0.1:8443/api").shouldBeInstanceOf<UrlCheck.Ok>()
        lenient.validate("https://localhost:8443/api").shouldBeInstanceOf<UrlCheck.Ok>()
        lenient.isDisallowedAddress(InetAddress.getByName("127.0.0.1")) shouldBe false
    }

    "test seam accepts plain http on loopback only, so a local test server needs no TLS" {
        val lenient = OutboundUrlPolicy(allowLoopbackForTests = true)
        lenient.validate("http://127.0.0.1:8080/api").shouldBeInstanceOf<UrlCheck.Ok>()
        lenient.validate("http://localhost:8080/api").shouldBeInstanceOf<UrlCheck.Ok>()
        val rejected = lenient.validate("http://api.example.com/api").shouldBeInstanceOf<UrlCheck.Rejected>()
        rejected.reason shouldContain "https"
    }

    "test seam still rejects private networks" {
        val lenient = OutboundUrlPolicy(allowLoopbackForTests = true)
        lenient.validate("https://10.0.0.1/api").shouldBeInstanceOf<UrlCheck.Rejected>()
        lenient.validate("http://10.0.0.1/api").shouldBeInstanceOf<UrlCheck.Rejected>()
    }
})
