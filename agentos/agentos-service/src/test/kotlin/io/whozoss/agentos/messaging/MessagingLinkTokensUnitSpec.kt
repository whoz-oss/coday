package io.whozoss.agentos.messaging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class MessagingLinkTokensUnitSpec :
    StringSpec({

        /** A clock the test moves. */
        class TestClock(
            var now: Instant = Instant.parse("2026-11-03T10:00:00Z"),
        ) : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant = now
        }

        val ttlMinutes = 10L

        fun tokens(clock: Clock) = MessagingLinkTokens(MessagingProperties(linkTokenTtlMinutes = ttlMinutes), clock)

        val bot = UUID.randomUUID()

        "a token gives back the user who asked for it, once" {
            val clock = TestClock()
            val tokens = tokens(clock)
            val userId = UUID.randomUUID()

            val issued = tokens.issue(userId, bot)

            issued.expiresAt shouldBe clock.now.plus(Duration.ofMinutes(ttlMinutes))
            tokens.consume(issued.token, bot) shouldBe userId
            tokens.consume(issued.token, bot) shouldBe null
        }

        "a token fits the start parameter of Telegram: 64 characters of A-Z a-z 0-9 _ -" {
            val token = tokens(TestClock()).issue(UUID.randomUUID(), bot).token

            token shouldMatch Regex("[A-Za-z0-9_-]{1,64}")
        }

        "an expired token gives nothing" {
            val clock = TestClock()
            val tokens = tokens(clock)
            val issued = tokens.issue(UUID.randomUUID(), bot)

            clock.now = issued.expiresAt

            tokens.consume(issued.token, bot) shouldBe null
        }

        "a new token replaces the one the user still had" {
            val tokens = tokens(TestClock())
            val userId = UUID.randomUUID()
            val first = tokens.issue(userId, bot)

            val second = tokens.issue(userId, bot)

            tokens.consume(first.token, bot) shouldBe null
            tokens.consume(second.token, bot) shouldBe userId
        }

        "a token works only with the bot it was issued for, and is used up by a refused try" {
            val tokens = tokens(TestClock())
            val issued = tokens.issue(UUID.randomUUID(), bot)

            tokens.consume(issued.token, UUID.randomUUID()) shouldBe null
            tokens.consume(issued.token, bot) shouldBe null
        }

        "an unknown token gives nothing" {
            tokens(TestClock()).consume("made-up", bot) shouldBe null
        }
    })
