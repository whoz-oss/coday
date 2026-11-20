package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import java.util.UUID

class UserNodeUnitSpec :
    StringSpec({
        "promptCacheTtl round-trips through the node as its enum name" {
            val user = User(externalId = "alice@example.com", promptCacheTtl = PromptCacheTtl.ONE_HOUR)

            val node = UserNode.fromDomain(user)

            node.promptCacheTtl shouldBe "ONE_HOUR"
            node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.ONE_HOUR
        }

        "a node stored before promptCacheTtl existed reads as FIVE_MINUTES" {
            val node = UserNode(id = UUID.randomUUID().toString(), externalId = "bob@example.com", email = "bob@example.com")

            node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.FIVE_MINUTES
        }

        "an unknown stored promptCacheTtl reads as FIVE_MINUTES instead of making the user unreadable" {
            val node =
                UserNode(
                    id = UUID.randomUUID().toString(),
                    externalId = "carol@example.com",
                    email = "carol@example.com",
                    promptCacheTtl = "ONE_DAY",
                )

            node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.FIVE_MINUTES
        }
    })
