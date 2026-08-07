package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ScheduledPromptUserRunNode] toDomain / fromDomain round-trip.
 *
 * Uniqueness is enforced by a composite Neo4j constraint on `(runId, userId)`;
 * there is no synthetic key field.
 */
class ScheduledPromptUserRunNodeUnitSpec : StringSpec({

    val runId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val now: Instant = Instant.parse("2025-01-15T10:00:00Z")

    // -------------------------------------------------------------------------
    // fromDomain / toDomain round-trip — PENDING
    // -------------------------------------------------------------------------

    "fromDomain then toDomain preserves all fields for a PENDING UserRun" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.PENDING,
        )

        val node = ScheduledPromptUserRunNode.fromDomain(domain)
        val roundTripped = node.toDomain()

        roundTripped.id shouldBe domain.id
        roundTripped.runId shouldBe runId
        roundTripped.userId shouldBe userId
        roundTripped.status shouldBe UserRunStatus.PENDING
        roundTripped.error.shouldBeNull()
        roundTripped.startedAt.shouldBeNull()
        roundTripped.finishedAt.shouldBeNull()
        roundTripped.leaseUntil.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // fromDomain / toDomain round-trip — RUNNING with lease
    // -------------------------------------------------------------------------

    "fromDomain then toDomain preserves all fields for a RUNNING UserRun" {
        val leaseUntil = now.plusSeconds(1800)
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.RUNNING,
            startedAt = now,
            leaseUntil = leaseUntil,
        )

        val roundTripped = ScheduledPromptUserRunNode.fromDomain(domain).toDomain()

        roundTripped.status shouldBe UserRunStatus.RUNNING
        roundTripped.startedAt shouldBe now
        roundTripped.leaseUntil shouldBe leaseUntil
        roundTripped.error.shouldBeNull()
        roundTripped.finishedAt.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // fromDomain / toDomain round-trip — DONE
    // -------------------------------------------------------------------------

    "fromDomain then toDomain preserves all fields for a DONE UserRun" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.DONE,
            startedAt = now,
            finishedAt = now.plusSeconds(120),
        )

        val roundTripped = ScheduledPromptUserRunNode.fromDomain(domain).toDomain()

        roundTripped.status shouldBe UserRunStatus.DONE
        roundTripped.finishedAt shouldBe now.plusSeconds(120)
        roundTripped.leaseUntil.shouldBeNull()
        roundTripped.error.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // fromDomain / toDomain round-trip — FAILED
    // -------------------------------------------------------------------------

    "fromDomain then toDomain preserves all fields for a FAILED UserRun" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.FAILED,
            error = "Permission grant failed",
            finishedAt = now,
        )

        val roundTripped = ScheduledPromptUserRunNode.fromDomain(domain).toDomain()

        roundTripped.status shouldBe UserRunStatus.FAILED
        roundTripped.error shouldBe "Permission grant failed"
        roundTripped.finishedAt shouldBe now
        roundTripped.leaseUntil.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Node field invariants
    // -------------------------------------------------------------------------

    "fromDomain sets removed to null for active UserRun" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.PENDING,
        )
        val node = ScheduledPromptUserRunNode.fromDomain(domain)
        node.removed.shouldBeNull()
    }

    "toDomain maps null removed to removed=false on EntityMetadata" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.PENDING,
        )
        val node = ScheduledPromptUserRunNode.fromDomain(domain)
        val back = node.toDomain()
        back.metadata.removed shouldBe false
    }

    // -------------------------------------------------------------------------
    // Node id is preserved across round-trip
    // -------------------------------------------------------------------------

    "fromDomain preserves the domain entity id as the node id string" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.PENDING,
        )
        val node = ScheduledPromptUserRunNode.fromDomain(domain)
        node.id shouldBe domain.id.toString()
    }

    "toDomain recovers the same UUID from the node id string" {
        val domain = ScheduledPromptUserRun(
            runId = runId,
            userId = userId,
            status = UserRunStatus.PENDING,
        )
        val back = ScheduledPromptUserRunNode.fromDomain(domain).toDomain()
        back.id shouldBe domain.id
    }

})
