package io.whozoss.agentos.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Unit tests for [OAuthPendingRegistry].
 */
class OAuthPendingRegistryUnitSpec : StringSpec({

    fun registry() = OAuthPendingRegistry()

    val alice = UUID.randomUUID()
    val bob = UUID.randomUUID()

    // -------------------------------------------------------------------------
    // register
    // -------------------------------------------------------------------------

    "register creates a future that can be resolved" {
        val reg = registry()
        val future = reg.register("state-abc", alice)

        future.isDone shouldBe false
    }

    "register throws on duplicate state" {
        val reg = registry()
        reg.register("state-dup", alice)

        shouldThrow<IllegalStateException> {
            reg.register("state-dup", alice)
        }
    }

    // -------------------------------------------------------------------------
    // resolve
    // -------------------------------------------------------------------------

    "resolve completes the future with the code" {
        val reg = registry()
        val future = reg.register("state-1", alice)

        val resolved = reg.resolve("state-1", "auth-code-xyz", alice)

        resolved shouldBe true
        future.isDone shouldBe true
        future.get() shouldBe "auth-code-xyz"
    }

    "resolve returns false for unknown state" {
        val reg = registry()

        val resolved = reg.resolve("nonexistent-state", "some-code", alice)

        resolved shouldBe false
    }

    "resolve removes the entry (second resolve returns false)" {
        val reg = registry()
        reg.register("state-once", alice)
        reg.resolve("state-once", "code-1", alice)

        val secondResolve = reg.resolve("state-once", "code-2", alice)

        secondResolve shouldBe false
    }

    "resolve returns false when caller is a different user" {
        val reg = registry()
        reg.register("state-bob", bob)

        val resolved = reg.resolve("state-bob", "alice-code", alice)

        resolved shouldBe false
    }

    "resolve by wrong user leaves the entry intact for the legitimate user" {
        val reg = registry()
        val future = reg.register("state-bob", bob)

        // Alice's attempt is rejected
        val rejectedByAlice = reg.resolve("state-bob", "alice-code", alice)
        rejectedByAlice shouldBe false
        future.isDone shouldBe false

        // Bob can still resolve his own flow
        val resolvedByBob = reg.resolve("state-bob", "bob-code", bob)
        resolvedByBob shouldBe true
        future.get() shouldBe "bob-code"
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    "cancel completes the future exceptionally" {
        val reg = registry()
        val future = reg.register("state-cancel", alice)

        reg.cancel("state-cancel")

        future.isDone shouldBe true
        future.isCompletedExceptionally shouldBe true
        // CompletableFuture.get() rethrows CancellationException directly (not wrapped
        // in ExecutionException) when the completing exception is itself a CancellationException.
        shouldThrow<CancellationException> { future.get() }
    }

    "cancel removes the entry from pending" {
        val reg = registry()
        reg.register("state-rm", alice)
        reg.cancel("state-rm")

        // After cancel the state is gone — a new register must succeed
        val newFuture = reg.register("state-rm", alice)
        newFuture.isDone shouldBe false
    }
})
