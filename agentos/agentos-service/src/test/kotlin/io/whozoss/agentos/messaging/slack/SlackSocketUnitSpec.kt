package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The Socket Mode connection against a local server standing for Slack: the HTTP call that opens it, then the WebSocket. */
class SlackSocketUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()
        val client = OkHttpClient()

        /** Slack on a local port: [onOpen] says what the server sends on each new connection. */
        class FakeSlack(
            val openAnswer: String? = null,
            val onOpen: (WebSocket, Int) -> Unit,
        ) : AutoCloseable {
            val server = MockWebServer()
            val opened = AtomicInteger()
            val acknowledged = CopyOnWriteArrayList<String>()
            private val serverSockets = CopyOnWriteArrayList<WebSocket>()

            init {
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/apps.connections.open" ->
                                    MockResponse().setBody(
                                        openAnswer ?: """{"ok":true,"url":"${server.url("/link").toString().replaceFirst("http", "ws")}"}""",
                                    )
                                "/link" ->
                                    MockResponse().withWebSocketUpgrade(
                                        object : WebSocketListener() {
                                            override fun onOpen(
                                                webSocket: WebSocket,
                                                response: Response,
                                            ) {
                                                serverSockets.add(webSocket)
                                                onOpen(webSocket, opened.incrementAndGet())
                                            }

                                            override fun onMessage(
                                                webSocket: WebSocket,
                                                text: String,
                                            ) {
                                                acknowledged.add(text)
                                            }
                                        },
                                    )
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                server.start()
            }

            fun socket(
                envelopes: MutableList<SlackEvents.Frame.Envelope>,
                statuses: MutableList<MessagingBotStatus>,
            ) = SlackSocket(
                appToken = "xapp-test",
                web = SlackWebClient(server.url("/").toString(), client, mapper),
                httpClient = client,
                objectMapper = mapper,
                name = "@helper",
                onEnvelope = { envelopes.add(it) },
                onStatus = { statuses.add(it) },
            )

            /**
             * The server waits for its WebSocket connections to end before it stops: they are closed first, and a
             * slow end is not the test's concern.
             */
            override fun close() {
                serverSockets.forEach { it.close(NORMAL_CLOSURE, "done") }
                runCatching { server.shutdown() }
            }
        }

        "a connection says it runs, acknowledges each envelope at once, and hands it on" {
            FakeSlack(onOpen = { socket, _ ->
                socket.send("""{"type":"hello"}""")
                socket.send("""{"envelope_id":"e1","type":"events_api","payload":{"event":{"type":"message"}}}""")
            }).use { slack ->
                val envelopes = CopyOnWriteArrayList<SlackEvents.Frame.Envelope>()
                val statuses = CopyOnWriteArrayList<MessagingBotStatus>()
                val socket = slack.socket(envelopes, statuses)

                socket.start()

                eventually(5.seconds) {
                    statuses shouldContain MessagingBotStatus.RUNNING
                    slack.acknowledged shouldContain """{"envelope_id":"e1"}"""
                    envelopes.map { it.envelopeId } shouldBe listOf("e1")
                }
                socket.stop()
            }
        }

        "a disconnect warning opens another connection" {
            FakeSlack(onOpen = { socket, count ->
                socket.send("""{"type":"hello"}""")
                if (count == 1) socket.send("""{"type":"disconnect","reason":"warning"}""")
            }).use { slack ->
                val socket = slack.socket(CopyOnWriteArrayList(), CopyOnWriteArrayList())

                socket.start()

                eventually(5.seconds) { slack.opened.get() shouldBeGreaterThanOrEqual 2 }
                socket.stop()
            }
        }

        "a refused app-level token stops the connection for good" {
            FakeSlack(openAnswer = """{"ok":false,"error":"invalid_auth"}""", onOpen = { _, _ -> }).use { slack ->
                val statuses = CopyOnWriteArrayList<MessagingBotStatus>()
                val socket = slack.socket(CopyOnWriteArrayList(), statuses)

                socket.start()

                eventually(5.seconds) { statuses shouldContain MessagingBotStatus.TOKEN_REJECTED }
                Thread.sleep(300)
                slack.server.requestCount shouldBe 1
                socket.stop()
            }
        }
    })

private const val NORMAL_CLOSURE = 1000
