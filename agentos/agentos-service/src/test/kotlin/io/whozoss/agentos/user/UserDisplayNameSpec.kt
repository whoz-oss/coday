package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UserDisplayNameSpec :
    StringSpec({
        "the display name is the full name when the user has one" {
            User(externalId = "ada", email = "ada@example.com", firstname = "Ada", lastname = "Lovelace")
                .displayName() shouldBe "Ada Lovelace"
        }

        "the display name is the email when the user has no name" {
            User(externalId = "ada", email = "ada@example.com").displayName() shouldBe "ada@example.com"
        }

        "the display name is the id when the user has neither a name nor an email" {
            val user = User(externalId = "ada")

            user.displayName() shouldBe user.id.toString()
        }
    })
