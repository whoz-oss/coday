package io.whozoss.agentos.messaging.slack

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.messaging.FakeMessagingAdapter
import io.whozoss.agentos.messaging.MessagingAction
import io.whozoss.agentos.messaging.MessagingButton
import io.whozoss.agentos.messaging.MessagingDeliveryException
import io.whozoss.agentos.messaging.OutboundMessage
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.util.UUID

class SlackAdapterUnitSpec :
    StringSpec({

        val bot =
            FakeMessagingAdapter
                .botRef(accountId = "T1.UBOT", username = "helper", platform = MessagingPlatform.SLACK)
                .copy(url = "https://slack.com/app_redirect?app=A1&team=T1")

        fun adapter(web: SlackWebClient) = SlackAdapter(web, bot, "xoxb-token")

        "a message goes as a markdown block, its buttons as actions, a link button as a URL" {
            val web = mockk<SlackWebClient>()
            val blocks = slot<List<Map<String, Any?>>>()
            every { web.postMessage("xoxb-token", "D1", "**Pick**", capture(blocks)) } returns "1.000001"
            val questionId = UUID.randomUUID()

            adapter(web).send(
                "D1",
                OutboundMessage(
                    "**Pick**",
                    listOf(
                        MessagingButton("Yes", MessagingAction.AnswerQuestion(questionId, 0)),
                        MessagingButton("Docs", MessagingAction.OpenUrl("https://example.com")),
                    ),
                ),
            )

            blocks.captured[0] shouldBe mapOf("type" to "markdown", "text" to "**Pick**")
            val elements = blocks.captured[1]["elements"] as List<*>
            (elements[0] as Map<*, *>)["value"] shouldBe "q:$questionId:0"
            (elements[1] as Map<*, *>)["url"] shouldBe "https://example.com"
        }

        "the buttons of a message are removed by putting its text back alone" {
            val web = mockk<SlackWebClient>(relaxed = true)
            every { web.postMessage(any(), any(), any(), any()) } returns "1.000001"
            val adapter = adapter(web)
            adapter.send("D1", OutboundMessage("Which one?", listOf(MessagingButton("A", MessagingAction.OpenCase(UUID.randomUUID())))))

            adapter.removeButtons("D1", "1.000001")

            verify { web.updateMessage("xoxb-token", "D1", "1.000001", "Which one?", listOf(mapOf("type" to "markdown", "text" to "Which one?"))) }
        }

        "a refused click is told to the user alone; a successful one shows by itself" {
            val web = mockk<SlackWebClient>(relaxed = true)
            val adapter = adapter(web)

            adapter.acknowledgeClick("D1:U7", "This question was already answered.")
            adapter.acknowledgeClick("D1:U7", "✓ B")

            verify(exactly = 1) { web.postEphemeral("xoxb-token", "D1", "U7", any()) }
        }

        "a message Slack refuses as blocks goes again as plain text" {
            val web = mockk<SlackWebClient>()
            every { web.postMessage(any(), any(), any(), match { it != null }) } throws SlackApiException("chat.postMessage", "invalid_blocks", null)
            every { web.postMessage("xoxb-token", "D1", "hi", null) } returns "1.000001"

            adapter(web).send("D1", OutboundMessage("hi"))

            verify { web.postMessage("xoxb-token", "D1", "hi", null) }
        }

        "a rate limit is waited out; another refusal is a delivery failure" {
            val web = mockk<SlackWebClient>()
            every { web.postMessage(any(), "D1", any(), any()) } throws SlackApiException("chat.postMessage", "ratelimited", 0) andThen "1.000001"
            every { web.postMessage(any(), "D2", any(), any()) } throws SlackApiException("chat.postMessage", "channel_not_found", null)

            adapter(web).send("D1", OutboundMessage("hi"))
            shouldThrow<MessagingDeliveryException> { adapter(web).send("D2", OutboundMessage("hi")) }

            verify(exactly = 2) { web.postMessage(any(), "D1", any(), any()) }
        }

        "a long message is sent in parts, the buttons on the last one" {
            val web = mockk<SlackWebClient>()
            val sent = mutableListOf<List<Map<String, Any?>>?>()
            every { web.postMessage(any(), "D1", any(), captureNullable(sent)) } returns "1.000001"
            val paragraph = "word ".repeat(300).trim()

            adapter(web).send(
                "D1",
                OutboundMessage(List(6) { paragraph }.joinToString("\n\n"), listOf(MessagingButton("Case", MessagingAction.OpenCase(UUID.randomUUID())))),
            )

            sent shouldHaveSize 3
            sent.dropLast(1).forEach { it?.size shouldBe 1 }
            sent.last()?.size shouldBe 2
        }

        "a link opens the app's chat, and the token goes in a message, since Slack's links cannot carry it" {
            val adapter = adapter(mockk())

            adapter.linkUrl("tok") shouldBe "https://slack.com/app_redirect?app=A1&team=T1"
            adapter.linkCode("tok") shouldBe "link tok"
            adapter.commandPrefix shouldBe "!"
        }
    })
