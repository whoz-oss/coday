package io.whozoss.agentos.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CancellationException

/**
 * Unit tests for [OAuthPendingRegistry].
 */
class OAuthPendingRegistryUnitSpec : StringSpec({

    fun registry() = OAuthPendingRegistry()

    // -------------------------------------------------------------------------
    // register
    // -------------------------------------------------------------------------

    "register creates a future that can be resolved" {
        val reg = registry()
        val future = reg.register("state-abc")

        future.isDone shouldBe false
    }

    "register throws on duplicate state" {
        val reg = registry()
        reg.register("state-dup")

        shouldThrow<IllegalStateException> {
            reg.register("state-dup")
        }
    }

    // -------------------------------------------------------------------------
    // resolve
    // -------------------------------------------------------------------------

    "resolve completes the future with the code" {
        val reg = registry()
        val future = reg.register("state-1")

        val resolved = reg.resolve("state-1", "auth-code-xyz")

        resolved shouldBe true
        future.isDone shouldBe true
        future.get() shouldBe "auth-code-xyz"
    }

    "resolve returns false for unknown state" {
        val reg = registry()

        val resolved = reg.resolve("nonexistent-state", "some-code")

        resolved shouldBe false
    }

    "resolve removes the entry (second resolve returns false)" {
        val reg = registry()
        reg.register("state-once")
        reg.resolve("state-once", "code-1")

        val secondResolve = reg.resolve("state-once", "code-2")

        secondResolve shouldBe false
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    "cancel completes the future exceptionally" {
        val reg = registry()
        val future = reg.register("state-cancel")

        reg.cancel("state-cancel")

        future.isDone shouldBe true
        future.isCompletedExceptionally shouldBe true
        // CompletableFuture.get() rethrows CancellationException directly (not wrapped
        // in ExecutionException) when the completing exception is itself a CancellationException.
        shouldThrow<CancellationException> { future.get() }
    }

    "cancel removes the entry from pending" {
        val reg = registry()
        reg.register("state-rm")
        reg.cancel("state-rm")

        // After cancel the state is gone — a new register must succeed
        val newFuture = reg.register("state-rm")
        newFuture.isDone shouldBe false
    }
})
