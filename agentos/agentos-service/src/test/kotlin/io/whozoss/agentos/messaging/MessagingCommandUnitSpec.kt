package io.whozoss.agentos.messaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MessagingCommandUnitSpec :
    StringSpec({

        "a start link carries its token as the argument" {
            MessagingCommand.parse("/start abc_DEF-123") shouldBe MessagingCommand(MessagingCommand.START, "abc_DEF-123")
        }

        "the bot suffix Telegram adds in groups and the case of the name are ignored" {
            MessagingCommand.parse("/Cases@agentos_bot") shouldBe MessagingCommand(MessagingCommand.CASES, null)
        }

        "a command without argument has a null one, even with trailing spaces" {
            MessagingCommand.parse("  /new   ") shouldBe MessagingCommand(MessagingCommand.NEW, null)
        }

        "another slash word is not the gateway's: it goes to the case, where prompt commands live" {
            MessagingCommand.parse("/standup yesterday") shouldBe null
        }

        "a message that does not start with a slash is no command" {
            MessagingCommand.parse("please /new things") shouldBe null
            MessagingCommand.parse("@dev /cases") shouldBe null
        }
    })
