package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue

class BackoffUnitSpec : StringSpec({

    "attempt 1 returns baseMs" {
        exponentialBackoffMs(attempt = 1, baseMs = 2_000L, maxMs = 60_000L) shouldBe 2_000L
    }

    "attempt 2 doubles the delay" {
        exponentialBackoffMs(attempt = 2, baseMs = 2_000L, maxMs = 60_000L) shouldBe 4_000L
    }

    "attempt 3 doubles again" {
        exponentialBackoffMs(attempt = 3, baseMs = 2_000L, maxMs = 60_000L) shouldBe 8_000L
    }

    "delay is capped at maxMs" {
        exponentialBackoffMs(attempt = 100, baseMs = 2_000L, maxMs = 60_000L) shouldBe 60_000L
    }

    "attempt <= 0 is treated as attempt 1" {
        exponentialBackoffMs(attempt = 0, baseMs = 2_000L, maxMs = 60_000L) shouldBe 2_000L
        exponentialBackoffMs(attempt = -5, baseMs = 2_000L, maxMs = 60_000L) shouldBe 2_000L
    }

    "result never exceeds maxMs for any attempt" {
        (1..1000).forEach { attempt ->
            (exponentialBackoffMs(attempt, baseMs = 2_000L, maxMs = 60_000L) <= 60_000L).shouldBeTrue()
        }
    }
})
