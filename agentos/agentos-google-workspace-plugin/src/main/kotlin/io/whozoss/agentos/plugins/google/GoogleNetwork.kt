package io.whozoss.agentos.plugins.google

import io.whozoss.agentos.plugins.http.net.HttpClientHolder
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.BoundedBodyReader
import io.whozoss.agentos.plugins.http.net.UrlCheck
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reuses the HTTP plugin's audited client, DNS policy and bounded reader, never its OpenAPI proxy. */
internal object GoogleNetwork {
    private var holder: HttpClientHolder? = null
    val transport: GoogleTransport get() = GoogleHttpTransport(checkNotNull(holder).client)
    @Synchronized fun start() { if (holder == null) holder = HttpClientHolder(OutboundUrlPolicy()) }
    @Synchronized fun stop() { holder?.shutdown(); holder = null }
}

internal class GoogleHttpTransport(private val client: okhttp3.OkHttpClient) : GoogleTransport {
    override fun get(request: GoogleRequest, token: String): GoogleResponse {
        val id = "[A-Za-z0-9_@.+-]{1,254}"
        val allowedPath = Regex("/drive/v3/files(?:/$id(?:/export)?)?|/calendar/v3/calendars/$id/events(?:/$id)?|/gmail/v1/users/me/(?:profile|messages(?:/$id)?)")
        requireGoverned(allowedPath.matches(request.path) && request.path.split('/').none { it == "." || it == ".." }, "UNSUPPORTED")
        val builder = "https://www.googleapis.com".toHttpUrl().newBuilder()
        request.path.removePrefix("/").split('/').forEach { builder.addPathSegment(it) }
        request.query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val url = builder.build()
        requireGoverned(url.scheme == "https" && url.host == "www.googleapis.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && OutboundUrlPolicy().validate(url.toString()) is UrlCheck.Ok, "UPSTREAM_ERROR")
        val call = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(
            Request.Builder().url(url).get().header("Authorization", "Bearer $token").header("Accept", "application/json, text/plain").build())
        return call.execute().use { response ->
            // Error bodies and redirect locations are neither read nor exposed.
            requireGoverned(response.code == 200, "UPSTREAM_ERROR")
            val body = response.body ?: throw Refusal("UPSTREAM_ERROR")
            val bounded = BoundedBodyReader.read(body.source(), 262144)
            requireGoverned(!bounded.truncated, "LIMIT_EXCEEDED")
            GoogleResponse(response.code, response.header("Content-Type").orEmpty(), bounded.bytes.toString(Charsets.UTF_8))
        }
    }
}
