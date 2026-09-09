package io.whozoss.agentos.plugins.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.whozoss.agentos.plugins.http.net.PolicyDns
import io.whozoss.agentos.plugins.http.net.UrlCheck
import io.whozoss.agentos.plugins.http.openapi.json
import io.whozoss.agentos.plugins.http.testing.ExecutionFixture

/**
 * The PF4J lifecycle path: the only wiring production executes. Every test leaves the holder stopped so
 * that the specs building [HttpApiPluginServices] by hand are not affected.
 */
class HttpApiPluginUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    beforeTest { HttpApiPluginHolder.shutdown() }
    afterTest { HttpApiPluginHolder.shutdown() }

    val config = json("""{"spec": {"inline": "openapi: 3.0.0"}, "baseUrl": "https://api.example.com"}""")

    "the services are unavailable before the plugin starts" {
        shouldThrow<IllegalStateException> { HttpApiPluginHolder.services }
    }

    "start wires the production policy, the policy DNS and one instance of every shared service" {
        val plugin = HttpApiPlugin()
        plugin.start()
        val services = HttpApiPluginHolder.services
        services.client.dns.shouldBeInstanceOf<PolicyDns>()
        services.client.followRedirects shouldBe false
        services.urlPolicy.validate("https://127.0.0.1/").shouldBeInstanceOf<UrlCheck.Rejected>()
        val again = HttpApiPluginHolder.services
        again shouldBeSameInstanceAs services
        again.catalogueCache shouldBeSameInstanceAs services.catalogueCache
        again.failures shouldBeSameInstanceAs services.failures
        again.limiters shouldBeSameInstanceAs services.limiters
    }

    "stop releases the client and the services" {
        val plugin = HttpApiPlugin()
        plugin.start()
        val client = HttpApiPluginHolder.services.client
        plugin.stop()
        client.dispatcher.executorService.isShutdown shouldBe true
        shouldThrow<IllegalStateException> { HttpApiPluginHolder.services }
    }

    "a provider instantiated the PF4J way exposes nothing while the plugin is not started" {
        val provider = HttpApiToolProvider::class.java.getDeclaredConstructor().newInstance()
        provider.provideTools(config, "PETS", ExecutionFixture.context()) shouldBe emptyList()
        provider.describeNamespace(config, "PETS", ExecutionFixture.context()).shouldBeNull()
    }
})
