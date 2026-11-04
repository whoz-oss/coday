package io.whozoss.agentos.messaging.telegram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.messaging.BotCreationOffer
import io.whozoss.agentos.messaging.FakeMessagingAdapter
import io.whozoss.agentos.messaging.MessagingAction
import io.whozoss.agentos.messaging.MessagingButton
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.OutboundMessage
import java.util.UUID

class TelegramAdapterUnitSpec :
    StringSpec({

        val sent = TelegramMessage(messageId = 1, chat = TelegramChat(id = 11, type = "private"))
        val bot = FakeMessagingAdapter.botRef(username = "helper_bot")

        fun refused(
            code: Int,
            retryAfter: Int? = null,
        ) = TelegramApiException("sendMessage", code, "refused", retryAfter)

        "a link opens the bot with its start parameter" {
            TelegramAdapter(mockk(), bot).linkUrl("abc") shouldBe "https://t.me/helper_bot?start=abc"
        }

        "a bot creation offer is a one-time reply keyboard with Telegram's managed bot request" {
            val client = mockk<TelegramBotClient>()
            val markup = slot<Map<String, Any>>()
            every { client.sendMessage("11", "Create <b>Helper</b>", "HTML", capture(markup)) } returns sent

            TelegramAdapter(client, bot).offerBotCreation("11", BotCreationOffer("Create **Helper**", "Create it", "Helper", "helper_ab12_bot"))

            markup.captured shouldBe
                mapOf(
                    "keyboard" to
                        listOf(
                            listOf(
                                mapOf(
                                    "text" to "Create it",
                                    "request_managed_bot" to
                                        mapOf("request_id" to 1, "suggested_name" to "Helper", "suggested_username" to "helper_ab12_bot"),
                                ),
                            ),
                        ),
                    "resize_keyboard" to true,
                    "one_time_keyboard" to true,
                )
        }

        "the token of a managed bot comes from Telegram, a refusal is a delivery failure" {
            val client = mockk<TelegramBotClient>()
            every { client.getManagedBotToken(7777) } returns "7777:new" andThenThrows TelegramApiException("getManagedBotToken", 400, "no", null)

            TelegramAdapter(client, bot).managedBotToken("7777") shouldBe "7777:new"
            shouldThrow<MessagingDeliveryException> { TelegramAdapter(client, bot).managedBotToken("7777") }
        }

        "a message goes as HTML with its buttons, a question's options as callbacks and links as URLs" {
            val client = mockk<TelegramBotClient>()
            val markup = slot<Map<String, Any>>()
            every { client.sendMessage("11", "<b>Pick</b>", "HTML", capture(markup)) } returns sent
            val questionId = UUID.randomUUID()

            TelegramAdapter(client, bot).send(
                "11",
                OutboundMessage(
                    "**Pick**",
                    listOf(
                        MessagingButton("Yes", MessagingAction.AnswerQuestion(questionId, 0)),
                        MessagingButton("Docs", MessagingAction.OpenUrl("https://example.com")),
                    ),
                ),
            )

            markup.captured shouldBe
                mapOf(
                    "inline_keyboard" to
                        listOf(
                            listOf(mapOf("text" to "Yes", "callback_data" to "q:$questionId:0")),
                            listOf(mapOf("text" to "Docs", "url" to "https://example.com")),
                        ),
                )
        }

        "a message Telegram refuses as HTML goes again as plain text, without the link buttons it may refuse" {
            val client = mockk<TelegramBotClient>()
            every { client.sendMessage(any(), any(), "HTML", any()) } throws refused(400)
            val plainMarkup = slot<Map<String, Any>>()
            every { client.sendMessage("11", "**Pick**", null, capture(plainMarkup)) } returns sent
            val caseId = UUID.randomUUID()

            TelegramAdapter(client, bot).send(
                "11",
                OutboundMessage(
                    "**Pick**",
                    listOf(
                        MessagingButton("Case", MessagingAction.OpenCase(caseId)),
                        MessagingButton("Open", MessagingAction.OpenUrl("http://localhost:4200")),
                    ),
                ),
            )

            plainMarkup.captured shouldBe mapOf("inline_keyboard" to listOf(listOf(mapOf("text" to "Case", "callback_data" to "c:$caseId"))))
        }

        "a flood refusal is waited out, then the message goes" {
            val client = mockk<TelegramBotClient>()
            every { client.sendMessage(any(), any(), any(), any()) } throws refused(429, retryAfter = 0) andThen sent

            TelegramAdapter(client, bot).send("11", OutboundMessage("hi"))

            verify(exactly = 2) { client.sendMessage("11", "hi", "HTML", null) }
        }

        "a refusal that is not about the content is a delivery failure" {
            val client = mockk<TelegramBotClient>()
            every { client.sendMessage(any(), any(), any(), any()) } throws refused(403)

            shouldThrow<MessagingDeliveryException> { TelegramAdapter(client, bot).send("11", OutboundMessage("hi")) }
        }

        "a long message is sent in several parts, the buttons on the last one" {
            val client = mockk<TelegramBotClient>()
            val markups = mutableListOf<Map<String, Any>?>()
            every { client.sendMessage("11", any(), "HTML", captureNullable(markups)) } returns sent
            val paragraph = "word ".repeat(300).trim()

            TelegramAdapter(client, bot).send(
                "11",
                OutboundMessage(List(6) { paragraph }.joinToString("\n\n"), listOf(MessagingButton("Case", MessagingAction.OpenCase(UUID.randomUUID())))),
            )

            markups shouldHaveSize 3
            markups.dropLast(1).forEach { it shouldBe null }
            (markups.last() != null) shouldBe true
        }
    })
