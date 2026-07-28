package io.whozoss.agentos.scheduledPrompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Unit tests for [ScheduledPromptNode.computeTripleKey].
 *
 * Verifies that the tripleKey is computed by slugifying the name,
 * so that names differing only by case or spacing produce the same key
 * and would be detected as conflicts by the UNIQUE constraint.
 */
class ScheduledPromptNodeUnitSpec : StringSpec({

    val nsId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")

    // -------------------------------------------------------------------------
    // Platform scope (null, null)
    // -------------------------------------------------------------------------

    "computeTripleKey with free-form name slugifies to lowercase-hyphenated key" {
        ScheduledPromptNode.computeTripleKey(null, null, "Daily Standup") shouldBe "_:_:daily-standup"
    }

    "computeTripleKey with same name different casing produces identical key" {
        val key1 = ScheduledPromptNode.computeTripleKey(null, null, "Daily Standup")
        val key2 = ScheduledPromptNode.computeTripleKey(null, null, "daily standup")
        key1 shouldBe key2
    }

    "computeTripleKey with diacritics normalizes correctly" {
        ScheduledPromptNode.computeTripleKey(null, null, "café") shouldBe "_:_:cafe"
    }

    "computeTripleKey with already-valid slug is unchanged" {
        ScheduledPromptNode.computeTripleKey(null, null, "daily-standup") shouldBe "_:_:daily-standup"
    }

    // -------------------------------------------------------------------------
    // Namespace scope
    // -------------------------------------------------------------------------

    "computeTripleKey with namespace scope encodes namespaceId" {
        val key = ScheduledPromptNode.computeTripleKey(nsId, null, "Weekly Sync")
        key shouldBe "${nsId}:_:weekly-sync"
    }

    // -------------------------------------------------------------------------
    // User × namespace scope
    // -------------------------------------------------------------------------

    "computeTripleKey with user and namespace scope encodes both ids" {
        val key = ScheduledPromptNode.computeTripleKey(nsId, userId, "Réunion hebdo")
        key shouldBe "${nsId}:${userId}:reunion-hebdo"
    }

    // -------------------------------------------------------------------------
    // Collision detection
    // -------------------------------------------------------------------------

    "computeTripleKey 'Daily Standup' and 'daily standup' collide in same scope" {
        ScheduledPromptNode.computeTripleKey(nsId, null, "Daily Standup") shouldBe
            ScheduledPromptNode.computeTripleKey(nsId, null, "daily standup")
    }

    "computeTripleKey 'Daily Standup' and 'Weekly Sync' do not collide" {
        val k1 = ScheduledPromptNode.computeTripleKey(nsId, null, "Daily Standup")
        val k2 = ScheduledPromptNode.computeTripleKey(nsId, null, "Weekly Sync")
        (k1 == k2) shouldBe false
    }
})
