package io.whozoss.agentos.messaging.telegram

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * The calls of the Telegram Bot API AgentOS makes, over HTTPS with JSON bodies.
 *
 * The bot token is part of every URL (`/bot<token>/<method>`): no URL, request or response is ever logged or
 * put in an exception message, only the method and Telegram's own description of a failure.
 *
 * @throws TelegramApiException when Telegram answers with an error
 * @throws IOException when Telegram cannot be reached
 */
class TelegramBotClient(
    private val token: String,
    apiBaseUrl: String,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
) {
    private val baseUrl = apiBaseUrl.trimEnd('/')

    /** The long poll in flight, so that a shutdown does not wait for it to time out. */
    private val pendingPoll = AtomicReference<Call?>()

    fun getMe(): TelegramUser = call("getMe", emptyMap(), type(TelegramUser::class.java))

    /**
     * Waits up to [timeoutSeconds] for updates after [offset]; [offset] also confirms the updates before it. Only
     * the update types of [allowedUpdates] are sent.
     */
    fun getUpdates(
        offset: Long?,
        timeoutSeconds: Int,
        allowedUpdates: List<String>,
    ): List<TelegramUpdate> =
        call(
            method = "getUpdates",
            payload = mapOf("offset" to offset, "timeout" to timeoutSeconds, "allowed_updates" to allowedUpdates),
            resultType = objectMapper.typeFactory.constructCollectionType(List::class.java, TelegramUpdate::class.java),
            track = pendingPoll,
        )

    /** Cancels the long poll in flight, if any: its caller gets an [IOException]. */
    fun cancelPoll() {
        pendingPoll.get()?.cancel()
    }

    fun sendMessage(
        chatId: String,
        text: String,
        parseMode: String?,
        replyMarkup: Map<String, Any>?,
    ): TelegramMessage =
        call(
            method = "sendMessage",
            payload =
                mapOf(
                    "chat_id" to chatId,
                    "text" to text,
                    "parse_mode" to parseMode,
                    "reply_markup" to replyMarkup,
                    "link_preview_options" to mapOf("is_disabled" to true),
                ),
            resultType = type(TelegramMessage::class.java),
        )

    /** Removes the inline keyboard of a message the bot sent. */
    fun removeInlineKeyboard(
        chatId: String,
        messageId: Long,
    ) {
        call<JsonNode>(
            method = "editMessageReplyMarkup",
            payload =
                mapOf(
                    "chat_id" to chatId,
                    "message_id" to messageId,
                    "reply_markup" to mapOf("inline_keyboard" to emptyList<Any>()),
                ),
            resultType = type(JsonNode::class.java),
        )
    }

    fun answerCallbackQuery(
        callbackQueryId: String,
        text: String?,
    ) {
        call<JsonNode>(
            method = "answerCallbackQuery",
            payload = mapOf("callback_query_id" to callbackQueryId, "text" to text),
            resultType = type(JsonNode::class.java),
        )
    }

    fun sendChatAction(
        chatId: String,
        action: String,
    ) {
        call<JsonNode>("sendChatAction", mapOf("chat_id" to chatId, "action" to action), type(JsonNode::class.java))
    }

    /** The token of [botUserId], a bot this one manages. */
    fun getManagedBotToken(botUserId: Long): String =
        call("getManagedBotToken", mapOf("user_id" to botUserId), type(String::class.java))

    /** Sets the command menu Telegram shows in the chat with the bot. */
    fun setMyCommands(commands: Map<String, String>) {
        call<JsonNode>(
            method = "setMyCommands",
            payload =
                mapOf(
                    "commands" to commands.map { (command, description) -> mapOf("command" to command, "description" to description) },
                ),
            resultType = type(JsonNode::class.java),
        )
    }

    private fun type(type: Class<*>): JavaType = objectMapper.typeFactory.constructType(type)

    private fun <T> call(
        method: String,
        payload: Map<String, Any?>,
        resultType: JavaType,
        track: AtomicReference<Call?>? = null,
    ): T {
        val body = objectMapper.writeValueAsString(payload.filterValues { it != null }).toRequestBody(JSON)
        val request = Request.Builder().url("$baseUrl/bot$token/$method").post(body).build()
        val call = httpClient.newCall(request)
        track?.set(call)
        return try {
            call.execute().use { response ->
                val envelope =
                    try {
                        objectMapper.readTree(response.body?.string().orEmpty())
                    } catch (e: IOException) {
                        null
                    }
                when (envelope?.get("ok")?.asBoolean()) {
                    true -> objectMapper.convertValue<T>(envelope.get("result"), resultType)
                    else ->
                        throw TelegramApiException(
                            method = method,
                            errorCode = envelope?.get("error_code")?.asInt() ?: response.code,
                            description = envelope?.get("description")?.asText() ?: "HTTP ${response.code}, not a Telegram answer",
                            retryAfterSeconds = envelope?.get("parameters")?.get("retry_after")?.asInt(),
                        )
                }
            }
        } finally {
            track?.compareAndSet(call, null)
        }
    }

    /** The token is a credential: it never appears in a log. */
    override fun toString(): String = "TelegramBotClient(bot=${token.substringBefore(':')})"

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Telegram answered [method] with an error. [description] is Telegram's, never the request. */
class TelegramApiException(
    val method: String,
    val errorCode: Int,
    val description: String,
    val retryAfterSeconds: Int?,
) : RuntimeException("Telegram $method failed: $errorCode $description")
