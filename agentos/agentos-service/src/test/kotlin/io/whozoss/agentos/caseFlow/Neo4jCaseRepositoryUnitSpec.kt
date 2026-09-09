package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [Neo4jCaseRepository].
 *
 * Focuses on the [Neo4jCaseRepository.save] clock-stamping contract: [modified] is
 * always overwritten by [Instant.now(clock)] at persistence time, regardless of
 * whatever value the caller put in [EntityMetadata.modified].
 *
 * Uses a MockK [CaseNodeNeo4jRepository] and a fixed/ticking [Clock] so that
 * there is no Neo4j engine involved and the timestamps are fully deterministic.
 *
 * Persistence-contract tests (round-trip, soft-delete, parent-isolation, etc.) live
 * in [AbstractCasePersistenceSpec] and run against a real Neo4j engine.
 */
class Neo4jCaseRepositoryUnitSpec :
    StringSpec({

        val neo4jRepo = mockk<CaseNodeNeo4jRepository>(relaxed = true)
        val linkService = mockk<Neo4jChildLinkService>(relaxed = true)
        val fixedNow = Instant.parse("2025-10-01T10:00:00Z")
        val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
        val repo =
            Neo4jCaseRepository(caseNodeNeo4jRepository = neo4jRepo, childLinkService = linkService, clock = clock)

        val namespaceId = UUID.randomUUID()

        fun case(modified: Instant = Instant.EPOCH) =
            Case(
                metadata = EntityMetadata(modified = modified),
                namespaceId = namespaceId,
                status = CaseStatus.PENDING,
            )

        beforeTest {
            clearAllMocks()
            // By default, return the node that was passed in so toDomain() can run.
            every { neo4jRepo.save(any()) } answers { firstArg() }
        }

        // -------------------------------------------------------------------------
        // modified stamping
        // -------------------------------------------------------------------------

        "save stamps modified with the clock time, ignoring the value in EntityMetadata" {
            val callerTimestamp = Instant.parse("2020-06-15T08:00:00Z")
            val entity = case(modified = callerTimestamp)
            val captured = slot<CaseNode>()
            every { neo4jRepo.save(capture(captured)) } answers { captured.captured }

            repo.save(entity)

            // The caller's modified (2020) must be discarded — the clock's value wins.
            captured.captured.modified shouldBe fixedNow
        }

        "save stamps modified even when the caller passes Instant.EPOCH" {
            val entity = case(modified = Instant.EPOCH)
            val captured = slot<CaseNode>()
            every { neo4jRepo.save(capture(captured)) } answers { captured.captured }

            repo.save(entity)

            captured.captured.modified shouldBe fixedNow
        }

        "save stamps modified even when the caller passes a future timestamp" {
            val futureTimestamp = Instant.parse("2099-01-01T00:00:00Z")
            val entity = case(modified = futureTimestamp)
            val captured = slot<CaseNode>()
            every { neo4jRepo.save(capture(captured)) } answers { captured.captured }

            repo.save(entity)

            // A future caller-supplied modified must also be replaced by the clock.
            captured.captured.modified shouldBe fixedNow
        }

        "successive saves with a ticking clock produce strictly increasing modified values" {
            val t1 = Instant.parse("2025-10-01T10:00:00Z")
            val t2 = t1.plusSeconds(30)
            var calls = 0
            val tickingClock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId?): Clock = this

                    override fun instant(): Instant = if (calls++ == 0) t1 else t2
                }
            val tickingRepo =
                Neo4jCaseRepository(
                    caseNodeNeo4jRepository = neo4jRepo,
                    childLinkService = linkService,
                    clock = tickingClock,
                )
            val entity = case()
            val capturedModifieds = mutableListOf<Instant>()
            every { neo4jRepo.save(any()) } answers {
                firstArg<CaseNode>().also { capturedModifieds += it.modified }
            }

            tickingRepo.save(entity)
            tickingRepo.save(entity)

            capturedModifieds[1] shouldBeGreaterThan capturedModifieds[0]
        }
    })
