package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.messaging.MessagingProperties
import io.whozoss.agentos.messaging.ProbedBot
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** The Slack driver and Web API client against a Web API served on localhost. */
class SlackDriverUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()

        /** Answers each Web API method with what [answer] says, `{"ok":true}` by default, and records the calls. */
        class FakeSlackApi(
            val answer: (method: String) -> Triple<Int, String, Map<String, String>>? = { null },
        ) : AutoCloseable {
            val calls = CopyOnWriteArrayList<Pair<String, String?>>()
            private val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        val method = exchange.requestURI.path.substringAfterLast('/')
                        calls.add(method to exchange.requestHeaders.getFirst("Authorization"))
                        val (code, body, headers) = answer(method) ?: Triple(200, """{"ok":true}""", emptyMap())
                        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                        val bytes = body.toByteArray()
                        exchange.sendResponseHeaders(code, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    start()
                }

            val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

            override fun close() = server.stop(0)
        }

        fun driver(api: FakeSlackApi) = SlackDriver(MessagingProperties(slack = MessagingProperties.Slack(apiBaseUrl = api.baseUrl)), mapper)

        val workspace: (String) -> Triple<Int, String, Map<String, String>>? = { method ->
            when (method) {
                "auth.test" -> Triple(200, """{"ok":true,"team_id":"T1","user_id":"UBOT","bot_id":"B1","user":"helper"}""", emptyMap())
                "bots.info" -> Triple(200, """{"ok":true,"bot":{"id":"B1","app_id":"A1","name":"Helper"}}""", emptyMap())
                "apps.connections.open" -> Triple(200, """{"ok":true,"url":"wss://example.test/link"}""", emptyMap())
                else -> null
            }
        }

        "a bot is checked with both its tokens, each with the call it serves" {
            FakeSlackApi(workspace).use { api ->
                driver(api).probe("xoxb-bot", "xapp-app") shouldBe
                    ProbedBot(
                        accountId = "T1.UBOT",
                        username = "helper",
                        displayName = "Helper",
                        url = "https://slack.com/app_redirect?app=A1&team=T1",
                        canManageBots = false,
                    )
                api.calls shouldBe
                    listOf(
                        "auth.test" to "Bearer xoxb-bot",
                        "bots.info" to "Bearer xoxb-bot",
                        "apps.connections.open" to "Bearer xapp-app",
                    )
            }
        }

        "a missing or misplaced token is refused before calling Slack" {
            FakeSlackApi(workspace).use { api ->
                shouldThrow<BadRequestException> { driver(api).probe("xoxb-bot", null) }.message shouldContain "app-level token"
                shouldThrow<BadRequestException> { driver(api).probe("xapp-app", "xoxb-bot") }
                api.calls.size shouldBe 0
            }
        }

        "a token Slack refuses is a bad request that says which one" {
            FakeSlackApi { method ->
                if (method == "apps.connections.open") Triple(200, """{"ok":false,"error":"invalid_auth"}""", emptyMap()) else workspace(method)
            }.use { api ->
                shouldThrow<BadRequestException> { driver(api).probe("xoxb-bot", "xapp-app") }.message shouldContain "app-level token"
            }
            FakeSlackApi { Triple(200, """{"ok":false,"error":"invalid_auth"}""", emptyMap()) }.use { api ->
                shouldThrow<BadRequestException> { driver(api).probe("xoxb-bot", "xapp-app") }.message shouldContain "bot token"
            }
        }

        "a rate limit says how long to wait" {
            FakeSlackApi { Triple(429, "", mapOf("Retry-After" to "7")) }.use { api ->
                val client = SlackWebClient(api.baseUrl, OkHttpClient(), mapper)

                val refused = shouldThrow<SlackApiException> { client.postMessage("xoxb-bot", "D1", "hi", null) }

                refused.error shouldBe "ratelimited"
                refused.retryAfterSeconds shouldBe 7
            }
        }

        "the manifest of an agent's app asks for its direct chats, by Socket Mode" {
            FakeSlackApi().use { api ->
                val manifest = mapper.readTree(driver(api).appManifest("Release Helper", null).manifest)

                manifest["display_information"]["name"].asText() shouldBe "Release Helper"
                manifest["features"]["bot_user"]["display_name"].asText() shouldBe "release-helper"
                manifest["features"]["app_home"]["messages_tab_enabled"].asBoolean() shouldBe true
                manifest["settings"]["socket_mode_enabled"].asBoolean() shouldBe true
                manifest["settings"]["event_subscriptions"]["bot_events"].map { it.asText() } shouldBe listOf("message.im")
                manifest["oauth_config"]["scopes"]["bot"].map { it.asText() } shouldContainAll listOf("chat:write", "im:history", "users:read")
            }
        }
    })
