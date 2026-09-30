package io.whozoss.agentos.plugins.http.net

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

class HttpClientHolderUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    "builds a client that never follows redirects nor retries, with the policy DNS and the JVM proxy selector" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        val client = holder.client
        client.followRedirects shouldBe false
        client.followSslRedirects shouldBe false
        client.retryOnConnectionFailure shouldBe false
        client.connectTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(10).toInt()
        client.dns.shouldBeInstanceOf<PolicyDns>()
        client.proxySelector shouldBe ProxySelector.getDefault()
        holder.shutdown()
    }

    "leaves read and write timeouts unbounded so that the call timeout of each caller alone bounds a call" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        holder.client.readTimeoutMillis shouldBe 0
        holder.client.writeTimeoutMillis shouldBe 0
        holder.shutdown()
    }

    "the dispatcher does not cap calls per host below its global limit (the plugin bounds calls itself)" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        holder.client.dispatcher.maxRequestsPerHost shouldBe holder.client.dispatcher.maxRequests
        holder.shutdown()
    }

    "shutdown stops the dispatcher executor" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        holder.shutdown()
        holder.client.dispatcher.executorService.isShutdown shouldBe true
    }
})
