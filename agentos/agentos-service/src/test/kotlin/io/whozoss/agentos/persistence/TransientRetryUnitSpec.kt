package io.whozoss.agentos.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessResourceException

class TransientRetryUnitSpec :
    StringSpec({
        timeout = 5000

        "returns the result without retrying when the block succeeds" {
            var calls = 0

            val result = retryOnTransientFailure(backoffMillis = 0) { calls++; "ok" }

            result shouldBe "ok"
            calls shouldBe 1
        }

        "runs the block again after a transient failure" {
            var calls = 0
            val retried = mutableListOf<Int>()

            val result =
                retryOnTransientFailure(backoffMillis = 0, onRetry = { attempt, _ -> retried += attempt }) {
                    calls++
                    if (calls < 3) throw TransientDataAccessResourceException("deadlock")
                    "ok"
                }

            result shouldBe "ok"
            calls shouldBe 3
            retried shouldBe listOf(1, 2)
        }

        "rethrows the transient failure once the attempts are exhausted" {
            var calls = 0

            shouldThrow<TransientDataAccessResourceException> {
                retryOnTransientFailure(maxAttempts = 3, backoffMillis = 0) {
                    calls++
                    throw TransientDataAccessResourceException("deadlock")
                }
            }

            calls shouldBe 3
        }

        "does not retry a failure that is not transient" {
            var calls = 0

            shouldThrow<DataIntegrityViolationException> {
                retryOnTransientFailure(backoffMillis = 0) {
                    calls++
                    throw DataIntegrityViolationException("constraint")
                }
            }

            calls shouldBe 1
        }
    })
