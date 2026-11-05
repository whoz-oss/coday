package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Downloading a file shared with the app: the token goes to Slack's file host only. */
class SlackWebClientUnitSpec :
    StringSpec({

        /** Answers every request with [body] as [contentType], and keeps the requests. */
        class FakeFiles(
            private val body: String,
            private val contentType: String = "audio/webm",
        ) : Interceptor {
            val requests = mutableListOf<Request>()

            override fun intercept(chain: Interceptor.Chain): Response {
                requests.add(chain.request())
                return Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fake")
                    .body(body.toResponseBody(contentType.toMediaType()))
                    .build()
            }
        }

        fun client(fake: FakeFiles) = SlackWebClient("https://slack.test/api", OkHttpClient.Builder().addInterceptor(fake).build(), jacksonObjectMapper())

        val url = "https://files.slack.com/files-pri/T1-F1/download/audio_message.webm"

        "a file of Slack's file host downloads with the bot token, within the size allowed" {
            val fake = FakeFiles("webm bytes")

            client(fake).download("xoxb-token", url, maxBytes = 1024).decodeToString() shouldBe "webm bytes"
            fake.requests.single().header("Authorization") shouldBe "Bearer xoxb-token"
            shouldThrow<SlackApiException> { client(fake).download("xoxb-token", url, maxBytes = 3) }.error shouldBe "file_too_large"
        }

        "the token is never sent to another host" {
            val fake = FakeFiles("anything")

            listOf("https://evil.example.com/file", "http://files.slack.com/files-pri/x", "not a url").forEach { other ->
                shouldThrow<SlackApiException> { client(fake).download("xoxb-token", other, maxBytes = 1024) }.error shouldBe "not_a_slack_file"
            }
            fake.requests.shouldBeEmpty()
        }

        "the sign-in page Slack answers without files:read is a missing scope" {
            val fake = FakeFiles("<html>sign in</html>", contentType = "text/html; charset=utf-8")

            shouldThrow<SlackApiException> { client(fake).download("xoxb-token", url, maxBytes = 1024) }.error shouldBe "missing_scope files:read"
        }
    })
