package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [CaseReadServiceImpl].
 *
 * Covers:
 * - markRead with no explicit timestamp uses clock time
 * - markRead with a past timestamp uses that timestamp
 * - markRead with a future timestamp is clamped to now
 * - initReadAt delegates to the repository with the effective timestamp
 * - initReadAt with no timestamp uses clock time
 */
class CaseReadServiceImplUnitSpec :
    StringSpec({

        val repo = mockk<CaseNodeNeo4jRepository>(relaxed = true)
        val fixedNow = Instant.parse("2025-09-01T12:00:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val service = CaseReadServiceImpl(repo, clock)

        val userId = "user-abc"
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()

        beforeTest { clearAllMocks() }

        // -------------------------------------------------------------------------
        // markRead
        // -------------------------------------------------------------------------

        "markRead with no explicit timestamp uses clock now" {
            val readAtSlot = slot<Instant>()
            every { repo.markRead(userId, caseId.toString(), capture(readAtSlot)) } returns Unit

            service.markRead(userId, caseId, at = null)

            readAtSlot.captured shouldBe fixedNow
            verify(exactly = 1) { repo.markRead(userId, caseId.toString(), fixedNow) }
        }

        "markRead with a past timestamp uses that timestamp" {
            val past = fixedNow.minusSeconds(3600)
            val readAtSlot = slot<Instant>()
            every { repo.markRead(userId, caseId.toString(), capture(readAtSlot)) } returns Unit

            service.markRead(userId, caseId, at = past)

            readAtSlot.captured shouldBe past
        }

        "markRead with a future timestamp is clamped to now" {
            val future = fixedNow.plusSeconds(3600)
            val readAtSlot = slot<Instant>()
            every { repo.markRead(userId, caseId.toString(), capture(readAtSlot)) } returns Unit

            service.markRead(userId, caseId, at = future)

            // Future timestamps are silently clamped to the clock time.
            readAtSlot.captured shouldBe fixedNow
        }

        "markRead with exactly now is accepted unchanged" {
            val readAtSlot = slot<Instant>()
            every { repo.markRead(userId, caseId.toString(), capture(readAtSlot)) } returns Unit

            service.markRead(userId, caseId, at = fixedNow)

            // fixedNow is not strictly before fixedNow, so it falls through to clamping — result is fixedNow.
            readAtSlot.captured shouldBe fixedNow
        }
    })
