package io.whozoss.agentos.user

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import org.slf4j.LoggerFactory
import java.util.UUID

class UserNodeUnitSpec :
    StringSpec({
        "promptCacheTtl round-trips through the node as its enum name" {
            val user = User(externalId = "alice@example.com", promptCacheTtl = PromptCacheTtl.EXTENDED)

            val node = UserNode.fromDomain(user)

            node.promptCacheTtl shouldBe "EXTENDED"
            node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.EXTENDED
        }

        "a node stored before promptCacheTtl existed reads as STANDARD" {
            val node = UserNode(id = UUID.randomUUID().toString(), externalId = "bob@example.com", email = "bob@example.com")

            node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.STANDARD
        }

        "the former stored names read as their intent" {
            fun node(stored: String) =
                UserNode(
                    id = UUID.randomUUID().toString(),
                    externalId = "dan@example.com",
                    email = "dan@example.com",
                    promptCacheTtl = stored,
                )

            node("FIVE_MINUTES").toDomain().promptCacheTtl shouldBe PromptCacheTtl.STANDARD
            node("ONE_HOUR").toDomain().promptCacheTtl shouldBe PromptCacheTtl.EXTENDED
            UserNode.fromDomain(node("ONE_HOUR").toDomain()).promptCacheTtl shouldBe "EXTENDED"
        }

        "an unknown stored promptCacheTtl reads as STANDARD instead of making the user unreadable" {
            val node =
                UserNode(
                    id = UUID.randomUUID().toString(),
                    externalId = "carol@example.com",
                    email = "carol@example.com",
                    promptCacheTtl = "ONE_DAY",
                )

            val logger = LoggerFactory.getLogger(UserNode::class.java) as Logger
            val logs = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(logs)
            try {
                node.toDomain().promptCacheTtl shouldBe PromptCacheTtl.STANDARD
            } finally {
                logger.detachAppender(logs)
            }

            val warning = logs.list.single { it.level == Level.WARN }
            warning.formattedMessage shouldContain "unknown promptCacheTtl 'ONE_DAY'"
            warning.formattedMessage shouldContain node.id
        }
    })
