package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The calls of the Slack Web API AgentOS makes. Each takes the token it runs with: the bot token (`xoxb-`) for
 * everything but [openConnection], which takes the app-level token (`xapp-`).
 *
 * Tokens travel in the `Authorization` header only: no request or response is ever logged or put in an exception
 * message, only the method and Slack's own error code.
 *
 * @throws SlackApiException when Slack answers with an error
 * @throws IOException when Slack cannot be reached
 */
class SlackWebClient(
    apiBaseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')

    /** Who [token] is: `team_id`, `user_id` (the bot user), `bot_id`, `user` (its name). */
    fun authTest(token: String): JsonNode = form("auth.test", token, emptyMap())

    /** The bot [botId]: `app_id`, `name`, `user_id`. */
    fun botsInfo(
        token: String,
        botId: String,
    ): JsonNode = form("bots.info", token, mapOf("bot" to botId))["bot"]

    /** The WebSocket URL of a new Socket Mode connection; it works once and for a short time. */
    fun openConnection(appToken: String): String = form("apps.connections.open", appToken, emptyMap())["url"].asText()

    /** The name Slack shows for [userId]: their display name, else their real name, else their id. */
    fun userName(
        token: String,
        userId: String,
    ): String {
        val user = form("users.info", token, mapOf("user" to userId))["user"]
        val profile = user?.get("profile")
        return listOfNotNull(
            profile?.get("display_name")?.asText(),
            profile?.get("real_name")?.asText(),
            user?.get("name")?.asText(),
        ).firstOrNull { it.isNotBlank() } ?: userId
    }

    /** Posts a message to [channel] and returns its timestamp, the id Slack gives it. */
    fun postMessage(
        token: String,
        channel: String,
        text: String,
        blocks: List<Map<String, Any?>>?,
    ): String =
        json(
            "chat.postMessage",
            token,
            mapOf("channel" to channel, "text" to text, "blocks" to blocks, "unfurl_links" to false, "unfurl_media" to false),
        )["ts"].asText()

    /** Replaces the text and blocks of the message [ts] the bot posted in [channel]. */
    fun updateMessage(
        token: String,
        channel: String,
        ts: String,
        text: String,
        blocks: List<Map<String, Any?>>?,
    ) {
        json("chat.update", token, mapOf("channel" to channel, "ts" to ts, "text" to text, "blocks" to blocks))
    }

    /** Shows [text] to [user] alone in [channel]. */
    fun postEphemeral(
        token: String,
        channel: String,
        user: String,
        text: String,
    ) {
        json("chat.postEphemeral", token, mapOf("channel" to channel, "user" to user, "text" to text))
    }

    private fun form(
        method: String,
        token: String,
        params: Map<String, String>,
    ): JsonNode = call(method, token, FormBody.Builder().apply { params.forEach { (key, value) -> add(key, value) } }.build())

    private fun json(
        method: String,
        token: String,
        payload: Map<String, Any?>,
    ): JsonNode = call(method, token, objectMapper.writeValueAsString(payload.filterValues { it != null }).toRequestBody(JSON))

    private fun call(
        method: String,
        token: String,
        body: RequestBody,
    ): JsonNode {
        val request =
            Request
                .Builder()
                .url("$baseUrl/$method")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        return httpClient.newCall(request).execute().use { response ->
            val envelope =
                try {
                    objectMapper.readTree(response.body?.string().orEmpty())
                } catch (e: IOException) {
                    null
                }
            when {
                response.code == TOO_MANY_REQUESTS ->
                    throw SlackApiException(method, "ratelimited", response.header("Retry-After")?.toIntOrNull())
                envelope?.get("ok")?.asBoolean() == true -> envelope
                else -> throw SlackApiException(method, envelope?.get("error")?.asText() ?: "HTTP ${response.code}", null)
            }
        }
    }

    companion object {
        private const val TOO_MANY_REQUESTS = 429
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Slack answered [method] with [error], its own code (`invalid_auth`, `channel_not_found`...), never the request. */
class SlackApiException(
    val method: String,
    val error: String,
    val retryAfterSeconds: Int?,
) : RuntimeException("Slack $method failed: $error") {
    /** True when the token itself is refused: no retry will help. */
    val tokenRejected: Boolean get() = error in TOKEN_ERRORS

    private companion object {
        val TOKEN_ERRORS =
            setOf("invalid_auth", "not_authed", "token_expired", "token_revoked", "account_inactive", "not_allowed_token_type")
    }
}
