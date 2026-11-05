package io.whozoss.agentos.messaging.telegram

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.messaging.ManagedBotInfo
import io.whozoss.agentos.messaging.MessagingAudio
import io.whozoss.agentos.messaging.MessagingBot
import io.whozoss.agentos.messaging.MessagingBotRole
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.MessagingInbound
import io.whozoss.agentos.messaging.MessagingProperties
import io.whozoss.agentos.messaging.MessagingSink
import io.whozoss.agentos.messaging.ProbedBot
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The Telegram driver against a Bot API served on localhost: checking a token, and running a bot from start to stop. */
class TelegramDriverUnitSpec :
    StringSpec({

        val mapper: ObjectMapper = jacksonObjectMapper()

        /** Answers each Bot API method with what [answer] says, `{"ok":true,"result":true}` by default, and records the calls. */
        class FakeBotApi(
            val answer: (method: String, call: Int) -> Pair<Int, String>? = { _, _ -> null },
        ) : AutoCloseable {
            val calls = CopyOnWriteArrayList<Pair<String, String>>()
            private val counts = mutableMapOf<String, AtomicInteger>()
            private val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        val method = exchange.requestURI.path.substringAfterLast('/')
                        calls.add(method to exchange.requestBody.readAllBytes().decodeToString())
                        val call = synchronized(counts) { counts.getOrPut(method) { AtomicInteger() }.incrementAndGet() }
                        // An empty long poll still takes a moment, as Telegram's would.
                        if (method == "getUpdates") Thread.sleep(20)
                        val (code, body) = answer(method, call) ?: (200 to """{"ok":true,"result":${if (method == "getUpdates") "[]" else "true"}}""")
                        val bytes = body.toByteArray()
                        exchange.sendResponseHeaders(code, bytes.size.toLong())
                        exchange.responseBody.use { it.write(bytes) }
                    }
                    start()
                }

            val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

            fun methods(): List<String> = calls.map { it.first }

            override fun close() = server.stop(0)
        }

        fun driver(api: FakeBotApi) =
            TelegramDriver(MessagingProperties(telegram = MessagingProperties.Telegram(apiBaseUrl = api.baseUrl, pollTimeoutSeconds = 1)), mapper)

        fun bot(role: MessagingBotRole = MessagingBotRole.AGENT) =
            MessagingBot(
                platform = MessagingPlatform.TELEGRAM,
                role = role,
                accountId = "4242",
                username = "helper_bot",
                displayName = "Helper",
                url = "https://t.me/bot",
                token = TOKEN,
                agentId = UUID.randomUUID().takeIf { role == MessagingBotRole.AGENT },
                namespaceId = UUID.randomUUID().takeIf { role == MessagingBotRole.AGENT },
            )

        val me = """{"ok":true,"result":{"id":4242,"is_bot":true,"first_name":"Helper","username":"helper_bot","can_manage_bots":true}}"""

        "a token is checked with getMe" {
            FakeBotApi { method, _ -> (200 to me).takeIf { method == "getMe" } }.use { api ->
                driver(api).probe(TOKEN) shouldBe
                    ProbedBot(
                        accountId = "4242",
                        username = "helper_bot",
                        displayName = "Helper",
                        url = "https://t.me/helper_bot",
                        canManageBots = true,
                    )
            }
        }

        "a token Telegram refuses, a user's, or one that is not a token at all is a bad request" {
            FakeBotApi { _, _ -> 401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}""" }.use { api ->
                shouldThrow<BadRequestException> { driver(api).probe(TOKEN) }
                shouldThrow<BadRequestException> { driver(api).probe("not a token") }
                api.calls.size shouldBe 1
            }
            FakeBotApi { _, _ -> 200 to """{"ok":true,"result":{"id":42,"is_bot":false,"first_name":"Ada"}}""" }.use { api ->
                shouldThrow<BadRequestException> { driver(api).probe(TOKEN) }
            }
        }

        "a started bot sets its commands, receives, and stops" {
            FakeBotApi().use { api ->
                val statuses = CopyOnWriteArrayList<MessagingBotStatus>()
                val connection = driver(api).start(bot(), MessagingSink { _, _ -> }) { statuses.add(it) }

                eventually(5.seconds) { statuses shouldContain MessagingBotStatus.RUNNING }
                api.methods().first() shouldBe "setMyCommands"
                connection.adapter.bot.username shouldBe "helper_bot"
                connection.stop()
                // A poll already sent when the bot stops still reaches the server: let it land before counting.
                Thread.sleep(200)
                val polled = api.methods().count { it == "getUpdates" }
                Thread.sleep(300)
                api.methods().count { it == "getUpdates" } shouldBe polled
            }
        }

        "a bot whose token Telegram refuses stops at once" {
            FakeBotApi { _, _ -> 401 to """{"ok":false,"error_code":401,"description":"Unauthorized"}""" }.use { api ->
                val statuses = CopyOnWriteArrayList<MessagingBotStatus>()
                driver(api).start(bot(), MessagingSink { _, _ -> }) { statuses.add(it) }

                eventually(5.seconds) { statuses shouldContain MessagingBotStatus.TOKEN_REJECTED }
                Thread.sleep(200)
                api.methods() shouldNotContain "getUpdates"
            }
        }

        "a bot manager asks for the updates about its bots, and hands them on" {
            val created =
                """{"ok":true,"result":[{"update_id":1,"managed_bot":{"user":{"id":1001,"first_name":"Ada"},""" +
                    """"bot":{"id":7777,"is_bot":true,"first_name":"Helper","username":"helper_x1y2_bot"}}}]}"""
            FakeBotApi { method, call -> (200 to created).takeIf { method == "getUpdates" && call == 1 } }.use { api ->
                val received = CopyOnWriteArrayList<MessagingInbound>()
                val connection = driver(api).start(bot(MessagingBotRole.MANAGER), MessagingSink { _, inbound -> received.add(inbound) }) {}

                eventually(5.seconds) { received.size shouldBe 1 }
                connection.stop()
                received.single() shouldBe
                    MessagingInbound.BotManaged(
                        chatId = "1001",
                        sender = io.whozoss.agentos.messaging.MessagingSender("1001", "Ada", bot = false),
                        bot = ManagedBotInfo(accountId = "7777", username = "helper_x1y2_bot", displayName = "Helper"),
                    )
                val firstPoll = mapper.readTree(api.calls.first { it.first == "getUpdates" }.second)
                firstPoll["allowed_updates"].map { it.asText() } shouldBe listOf("message", "managed_bot")
                api.methods() shouldNotContain "setMyCommands"
            }
        }

        "a voice message is handed on with its file, a photo as a message the bot cannot read" {
            val updates =
                """{"ok":true,"result":[""" +
                    """{"update_id":1,"message":{"message_id":5,"from":{"id":1001,"first_name":"Ada"},"chat":{"id":1001,"type":"private"},""" +
                    """"voice":{"file_id":"VOICE1","file_unique_id":"u1","duration":4,"mime_type":"audio/ogg","file_size":2100}}},""" +
                    """{"update_id":2,"message":{"message_id":6,"from":{"id":1001,"first_name":"Ada"},"chat":{"id":1001,"type":"private"},""" +
                    """"photo":[{"file_id":"P1","file_unique_id":"p1","width":90,"height":90}],"caption":"look"}}]}"""
            FakeBotApi { method, call -> (200 to updates).takeIf { method == "getUpdates" && call == 1 } }.use { api ->
                val received = CopyOnWriteArrayList<MessagingInbound>()
                val connection = driver(api).start(bot(), MessagingSink { _, inbound -> received.add(inbound) }) {}

                eventually(5.seconds) { received.size shouldBe 2 }
                connection.stop()
                val ada = io.whozoss.agentos.messaging.MessagingSender("1001", "Ada", bot = false)
                received shouldBe
                    listOf(
                        MessagingInbound.Voice(
                            chatId = "1001",
                            sender = ada,
                            messageId = 5,
                            audio = MessagingAudio(ref = "VOICE1", mimeType = "audio/ogg", durationSeconds = 4, sizeBytes = 2100),
                            direct = true,
                        ),
                        MessagingInbound.Unsupported(chatId = "1001", sender = ada, messageId = 6, direct = true),
                    )
            }
        }

        "a voice message downloads from the path getFile gives, within the size allowed" {
            FakeBotApi { method, _ ->
                when (method) {
                    "getFile" -> 200 to """{"ok":true,"result":{"file_id":"VOICE1","file_size":7,"file_path":"voice/file_3.oga"}}"""
                    "file_3.oga" -> 200 to "OggS..."
                    else -> null
                }
            }.use { api ->
                val connection = driver(api).start(bot(), MessagingSink { _, _ -> }) {}
                val audio = MessagingAudio(ref = "VOICE1", mimeType = "audio/ogg", durationSeconds = 4, sizeBytes = 7)

                connection.adapter.downloadAudio(audio, maxBytes = 1024).decodeToString() shouldBe "OggS..."
                shouldThrow<MessagingDeliveryException> { connection.adapter.downloadAudio(audio, maxBytes = 3) }
                connection.stop()
                mapper.readTree(api.calls.first { it.first == "getFile" }.second)["file_id"].asText() shouldBe "VOICE1"
            }
        }
    })

private const val TOKEN = "4242:helper-token-helper-token-helper-token"
