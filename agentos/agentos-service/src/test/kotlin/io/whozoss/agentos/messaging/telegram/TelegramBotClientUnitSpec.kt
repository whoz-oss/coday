package io.whozoss.agentos.messaging.telegram

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class TelegramBotClientUnitSpec :
    StringSpec({

        val token = "4242:secret-part-of-the-token"
        val mapper = jacksonObjectMapper()

        /** Answers every call with [status] and [body], and keeps the requests. */
        class FakeTelegram(
            private val status: Int,
            private val body: String,
        ) : Interceptor {
            val requests = mutableListOf<Request>()

            override fun intercept(chain: Interceptor.Chain): Response {
                requests.add(chain.request())
                return Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("fake")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        fun client(fake: FakeTelegram) =
            TelegramBotClient(token, "https://api.telegram.test/", OkHttpClient.Builder().addInterceptor(fake).build(), mapper)

        fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

        "updates are read, snake case included, and only the updates AgentOS handles are asked for" {
            val fake =
                FakeTelegram(
                    200,
                    """{"ok":true,"result":[{"update_id":7,"message":{"message_id":3,"from":{"id":11,"is_bot":false,
                    "first_name":"Ada","unknown_field":1},"chat":{"id":11,"type":"private"},"text":"hi"}}]}""",
                )

            val updates = client(fake).getUpdates(offset = 7, timeoutSeconds = 25, allowedUpdates = listOf("message", "callback_query"))

            updates.single().updateId shouldBe 7
            updates.single().message?.from?.displayName shouldBe "Ada"
            updates.single().message?.chat?.private shouldBe true
            fake.requests.single().url.encodedPath shouldBe "/bot$token/getUpdates"
            mapper.readTree(fake.requests.single().bodyText()).let { body ->
                body["offset"].asLong() shouldBe 7
                body["allowed_updates"].map { it.asText() } shouldBe listOf("message", "callback_query")
            }
        }

        "an error of Telegram carries its code, description and wait, and never the token" {
            val fake = FakeTelegram(429, """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":3}}""")

            val error = shouldThrow<TelegramApiException> { client(fake).sendMessage("11", "hi", null, null) }

            error.errorCode shouldBe 429
            error.retryAfterSeconds shouldBe 3
            error.message.orEmpty() shouldNotContain "secret-part-of-the-token"
        }

        "an answer that is not Telegram's is an error with the HTTP status" {
            val fake = FakeTelegram(502, "<html>bad gateway</html>")

            val error = shouldThrow<TelegramApiException> { client(fake).getMe() }

            error.errorCode shouldBe 502
        }

        "absent options are not sent" {
            val fake = FakeTelegram(200, """{"ok":true,"result":{"message_id":5,"chat":{"id":11,"type":"private"}}}""")

            client(fake).sendMessage("11", "hi", parseMode = null, replyMarkup = null)

            mapper.readTree(fake.requests.single().bodyText()).has("parse_mode") shouldBe false
        }
    })
