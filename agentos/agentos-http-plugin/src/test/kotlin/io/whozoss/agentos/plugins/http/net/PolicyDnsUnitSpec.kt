package io.whozoss.agentos.plugins.http.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

class PolicyDnsUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val public = InetAddress.getByName("93.184.216.34")
    val private = InetAddress.getByName("10.0.0.1")
    val loopback = InetAddress.getByName("127.0.0.1")
    val linkLocal = InetAddress.getByName("169.254.169.254")

    fun delegate(vararg addresses: InetAddress): Dns =
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
        }

    "keeps only the addresses the policy allows" {
        val dns = PolicyDns(OutboundUrlPolicy(), delegate(private, public, linkLocal))
        dns.lookup("api.example.com") shouldBe listOf(public)
    }

    "refuses a host resolving only to disallowed addresses so rebinding cannot reach a private range" {
        val dns = PolicyDns(OutboundUrlPolicy(), delegate(private, loopback))
        val ex = shouldThrow<UnknownHostException> { dns.lookup("rebind.example.com") }
        ex.message shouldContain "rebind.example.com"
        ex.message shouldContain "disallowed"
    }

    "keeps loopback when the policy allows it for tests" {
        val dns = PolicyDns(OutboundUrlPolicy(allowLoopbackForTests = true), delegate(loopback))
        dns.lookup("localhost") shouldBe listOf(loopback)
    }

    "propagates the delegate failure" {
        val failing = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("unknown host")
        }
        shouldThrow<UnknownHostException> { PolicyDns(OutboundUrlPolicy(), failing).lookup("nope.invalid") }
    }
})
