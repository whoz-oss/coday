package io.whozoss.agentos.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RetryUtilsUnitSpec : StringSpec({

    // -------------------------------------------------------------------------
    // Success cases
    // -------------------------------------------------------------------------

    "succeeds on first attempt without retry" {
        var callCount = 0

        val result = retryWithFallback<String, String>(
            maxAttempts = 3,
            execute = { _ ->
                callCount++
                AttemptSuccess("ok")
            },
            fallback = { _ -> "fallback" },
        )

        result shouldBe "ok"
        callCount shouldBe 1
    }

    "succeeds on second attempt after one failure" {
        var callCount = 0

        val result = retryWithFallback<String, String>(
            maxAttempts = 3,
            execute = { _ ->
                callCount++
                if (callCount < 2) AttemptFailure("fail") else AttemptSuccess("ok")
            },
            fallback = { _ -> "fallback" },
        )

        result shouldBe "ok"
        callCount shouldBe 2
    }

    "succeeds on last attempt" {
        var callCount = 0

        val result = retryWithFallback<String, String>(
            maxAttempts = 3,
            execute = { _ ->
                callCount++
                if (callCount < 3) AttemptFailure("fail") else AttemptSuccess("ok")
            },
            fallback = { _ -> "fallback" },
        )

        result shouldBe "ok"
        callCount shouldBe 3
    }

    // -------------------------------------------------------------------------
    // Fallback cases
    // -------------------------------------------------------------------------

    "calls fallback after all attempts exhausted" {
        var callCount = 0

        val result = retryWithFallback<String, String>(
            maxAttempts = 3,
            execute = { _ ->
                callCount++
                AttemptFailure("fail")
            },
            fallback = { _ -> "fallback" },
        )

        result shouldBe "fallback"
        callCount shouldBe 3
    }

    "fallback receives last failure value" {
        var receivedFailure: String? = null

        retryWithFallback<String, String>(
            maxAttempts = 2,
            execute = { previous ->
                val value = if (previous == null) "first-fail" else "second-fail"
                AttemptFailure(value)
            },
            fallback = { lastFailure ->
                receivedFailure = lastFailure
                "fallback"
            },
        )

        receivedFailure shouldBe "second-fail"
    }

    // -------------------------------------------------------------------------
    // Previous failure propagation
    // -------------------------------------------------------------------------

    "execute receives null on first call" {
        var firstCallArg: String? = "sentinel"

        retryWithFallback<String, String>(
            maxAttempts = 1,
            execute = { previous ->
                firstCallArg = previous
                AttemptSuccess("ok")
            },
            fallback = { _ -> "fallback" },
        )

        firstCallArg shouldBe null
    }

    "execute receives previous failure value on retry" {
        val receivedArgs = mutableListOf<String?>()

        retryWithFallback<String, String>(
            maxAttempts = 3,
            execute = { previous ->
                receivedArgs.add(previous)
                AttemptFailure("fail-${receivedArgs.size}")
            },
            fallback = { _ -> "fallback" },
        )

        receivedArgs[0] shouldBe null
        receivedArgs[1] shouldBe "fail-1"
        receivedArgs[2] shouldBe "fail-2"
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    "maxAttempts = 1 calls execute exactly once then fallback" {
        var callCount = 0

        val result = retryWithFallback<String, String>(
            maxAttempts = 1,
            execute = { _ ->
                callCount++
                AttemptFailure("fail")
            },
            fallback = { _ -> "fallback" },
        )

        result shouldBe "fallback"
        callCount shouldBe 1
    }

    "maxAttempts = 0 throws IllegalArgumentException" {
        shouldThrow<IllegalArgumentException> {
            retryWithFallback<String, String>(
                maxAttempts = 0,
                execute = { _ -> AttemptSuccess("ok") },
                fallback = { _ -> "fallback" },
            )
        }
    }

    "exceptions from execute propagate immediately" {
        shouldThrow<RuntimeException> {
            retryWithFallback<String, String>(
                maxAttempts = 3,
                execute = { _ -> throw RuntimeException("boom") },
                fallback = { _ -> "fallback" },
            )
        }
    }
})
