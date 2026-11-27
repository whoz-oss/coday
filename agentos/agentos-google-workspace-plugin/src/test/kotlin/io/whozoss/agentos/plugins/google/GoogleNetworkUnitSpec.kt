package io.whozoss.agentos.plugins.google

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType

class GoogleNetworkUnitSpec : StringSpec({
    "synthetic interceptor exercises fixed Google origin auth and no redirects" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        try {
            val client = holder.client.newBuilder().addInterceptor(Interceptor { chain ->
                chain.request().url.host shouldBe "www.googleapis.com"
                chain.request().url.scheme shouldBe "https"
                chain.request().url.port shouldBe 443
                chain.request().method shouldBe "GET"
                chain.request().header("Authorization") shouldBe "Bearer synthetic-token"
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", "application/json").body("{}".toResponseBody("application/json".toMediaType())).build()
            }).build()
            GoogleHttpTransport(client).get(GoogleRequest("/drive/v3/files/good"), "synthetic-token").body shouldBe "{}"
            client.followRedirects shouldBe false
            client.followSslRedirects shouldBe false
            client.retryOnConnectionFailure shouldBe false
        } finally { holder.shutdown() }
    }
    "transport refuses arbitrary paths even under allowed Google prefixes" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        try {
            val client = holder.client.newBuilder().addInterceptor { error("Unexpected network attempt") }.build()
            listOf("/drive/v3/files/good/permissions", "/gmail/v1/users/me/settings", "/calendar/v3/calendars/team/acl", "/drive/v3/files/../secret", "https://evil.test").forEach {
                shouldThrow<Refusal> { GoogleHttpTransport(client).get(GoogleRequest(it), "synthetic-token") }.code shouldBe "UNSUPPORTED"
            }
        } finally { holder.shutdown() }
    }
    "transport refuses oversized bodies using reused bounded reader" {
        val holder = HttpClientHolder(OutboundUrlPolicy())
        try {
            val client = holder.client.newBuilder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("x".repeat(262145).toResponseBody("text/plain".toMediaType())).build()
            }.build()
            shouldThrow<Refusal> { GoogleHttpTransport(client).get(GoogleRequest("/drive/v3/files/good"), "synthetic-token") }.code shouldBe "LIMIT_EXCEEDED"
        } finally { holder.shutdown() }
    }
})
