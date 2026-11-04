package io.whozoss.agentos.messaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.UUID

class MessagingActionCodecUnitSpec :
    StringSpec({

        "every action that comes back to AgentOS survives the round trip" {
            listOf(
                MessagingAction.AnswerQuestion(UUID.randomUUID(), 3),
                MessagingAction.OpenCase(UUID.randomUUID()),
            ).forEach { action ->
                MessagingActionCodec.decode(MessagingActionCodec.encode(action)) shouldBe action
            }
        }

        "the longest payload fits the 64 bytes Telegram allows" {
            val payload = MessagingActionCodec.encode(MessagingAction.AnswerQuestion(UUID.randomUUID(), 99)).orEmpty()

            payload.toByteArray().size shouldBeLessThanOrEqual 64
        }

        "a link has no payload: the platform opens it itself" {
            MessagingActionCodec.encode(MessagingAction.OpenUrl("https://example.com")) shouldBe null
        }

        "a payload that is not one of ours decodes to nothing" {
            listOf(null, "", "q", "q:not-a-uuid:1", "c:${UUID.randomUUID()}:extra", "x:${UUID.randomUUID()}", "q:${UUID.randomUUID()}:one")
                .forEach { payload -> MessagingActionCodec.decode(payload) shouldBe null }
        }
    })
