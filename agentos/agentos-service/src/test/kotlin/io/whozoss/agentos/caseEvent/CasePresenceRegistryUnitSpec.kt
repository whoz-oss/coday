package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

class CasePresenceRegistryUnitSpec :
    StringSpec({
        "a case nobody has open has nobody present" {
            CasePresenceRegistry().usersOf(UUID.randomUUID()) shouldBe emptySet()
        }

        "a user with two tabs open is present once, and stays present until the last tab closes" {
            val registry = CasePresenceRegistry()
            val caseId = UUID.randomUUID()
            val alice = UUID.randomUUID()

            val firstTab = registry.join(caseId, alice)
            val secondTab = registry.join(caseId, alice)
            registry.usersOf(caseId) shouldBe setOf(alice)

            registry.leave(caseId, firstTab)
            registry.usersOf(caseId) shouldBe setOf(alice)

            registry.leave(caseId, secondTab)
            registry.usersOf(caseId) shouldBe emptySet()
        }

        "each case has its own people" {
            val registry = CasePresenceRegistry()
            val caseA = UUID.randomUUID()
            val caseB = UUID.randomUUID()
            val alice = UUID.randomUUID()
            val bob = UUID.randomUUID()

            registry.join(caseA, alice)
            registry.join(caseB, bob)

            registry.usersOf(caseA) shouldBe setOf(alice)
            registry.usersOf(caseB) shouldBe setOf(bob)
        }

        "leaving twice, or with an unknown connection, changes nothing" {
            val registry = CasePresenceRegistry()
            val caseId = UUID.randomUUID()
            val alice = UUID.randomUUID()
            val bob = UUID.randomUUID()
            registry.join(caseId, alice)
            val bobTab = registry.join(caseId, bob)

            registry.leave(caseId, bobTab)
            registry.leave(caseId, bobTab)
            registry.leave(caseId, UUID.randomUUID())

            registry.usersOf(caseId) shouldBe setOf(alice)
        }

        "announces the case whose presence changed, and no other" {
            val registry = CasePresenceRegistry()
            val watched = UUID.randomUUID()
            val other = UUID.randomUUID()

            coroutineScope {
                // Subscribed before anything joins: the flow replays nothing.
                val seen = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(2_000) { registry.changes(watched).first() } }
                registry.join(other, UUID.randomUUID())
                registry.join(watched, UUID.randomUUID())

                seen.await() shouldBe watched
            }
        }
    })
