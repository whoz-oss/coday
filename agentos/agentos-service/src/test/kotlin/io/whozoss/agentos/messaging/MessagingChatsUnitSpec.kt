package io.whozoss.agentos.messaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MessagingChatsUnitSpec :
    StringSpec({

        "a chat address names the chat, and its thread when there is one" {
            MessagingChats.address("1001", "42") shouldBe "1001/42"
            MessagingChats.address("-100123", null) shouldBe "-100123"
            MessagingChats.chatOf("1001/42") shouldBe "1001"
            MessagingChats.threadOf("1001/42") shouldBe "42"
            MessagingChats.chatOf("D0123ABC") shouldBe "D0123ABC"
            MessagingChats.threadOf("D0123ABC") shouldBe null
        }
    })
