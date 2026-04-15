package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

class CallerContextSpec : StringSpec({

    "getCallerId and getCallerDisplayName return null before any update" {
        val ctx = CallerContext()

        ctx.getCallerId().shouldBeNull()
        ctx.getCallerDisplayName().shouldBeNull()
    }

    "setLastMessageSender updates getCallerId and getCallerDisplayName" {
        val ctx = CallerContext()

        ctx.setLastMessageSender("user-42", "Alice Dupont")

        ctx.getCallerId() shouldBe "user-42"
        ctx.getCallerDisplayName() shouldBe "Alice Dupont"
    }

    "successive calls to setLastMessageSender — last sender wins" {
        val ctx = CallerContext()

        ctx.setLastMessageSender("user-1", "First User")
        ctx.setLastMessageSender("user-2", "Second User")
        ctx.setLastMessageSender("user-3", "Third User")

        ctx.getCallerId() shouldBe "user-3"
        ctx.getCallerDisplayName() shouldBe "Third User"
    }

    "concurrent reads during a write do not crash" {
        val ctx = CallerContext()
        ctx.setLastMessageSender("initial-id", "Initial Name")

        coroutineScope {
            // Writer coroutine
            val writer = launch(Dispatchers.Default) {
                repeat(1000) { i ->
                    ctx.setLastMessageSender("user-$i", "User $i")
                }
            }

            // Reader coroutines
            val readers = (1..4).map {
                launch(Dispatchers.Default) {
                    repeat(1000) {
                        // Just read — we verify no crash, not specific values
                        ctx.getCallerId()
                        ctx.getCallerDisplayName()
                    }
                }
            }

            writer.join()
            readers.forEach { it.join() }
        }

        // After all writes, the last sender should be user-999
        ctx.getCallerId() shouldBe "user-999"
        ctx.getCallerDisplayName() shouldBe "User 999"
    }
})
