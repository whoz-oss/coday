package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import java.time.Instant
import java.util.UUID

class InsertChronologicallySpec : StringSpec() {
    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()

    fun event(epochMilli: Long): MessageEvent =
        MessageEvent(
            namespaceId = namespaceId,
            caseId = caseId,
            timestamp = Instant.ofEpochMilli(epochMilli),
            actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
            content = listOf(MessageContent.Text("msg")),
        )

    init {
        // -------------------------------------------------------------------------
        // Data-driven: insertion position
        // Each row: (description, initial timestamps, inserted timestamp, expected order)
        // -------------------------------------------------------------------------
        "insertChronologically places the new event at the correct position" {
            table(
                headers("scenario", "initialMillis", "insertedMilli", "expectedMillis"),
                row("empty list", emptyList<Long>(), 1000L, listOf(1000L)),
                row("insert at head", listOf(2000L, 3000L), 1000L, listOf(1000L, 2000L, 3000L)),
                row("insert at tail", listOf(1000L, 2000L), 3000L, listOf(1000L, 2000L, 3000L)),
                row("insert in the middle", listOf(1000L, 3000L), 2000L, listOf(1000L, 2000L, 3000L)),
                row("equal timestamp — stable", listOf(1000L, 2000L), 2000L, listOf(1000L, 2000L, 2000L)),
            ).forAll { _, initialMillis, insertedMilli, expectedMillis ->
                val list: MutableList<CaseEvent> = initialMillis.map { event(it) }.toMutableList()
                list.insertChronologically(event(insertedMilli))
                list shouldHaveSize expectedMillis.size
                expectedMillis.forEachIndexed { i, ms ->
                    list[i].timestamp shouldBe Instant.ofEpochMilli(ms)
                }
            }
        }

        // -------------------------------------------------------------------------
        // Stability: equal-timestamp event lands after the pre-existing one, specifically
        // -------------------------------------------------------------------------
        "new event with same timestamp as existing goes after it" {
            val e1 = event(1000)
            val existing = event(2000)
            val e3 = event(3000)
            val late =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    timestamp = Instant.ofEpochMilli(2000),
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("late")),
                )
            val list = mutableListOf<CaseEvent>(e1, existing, e3)
            list.insertChronologically(late)
            list shouldHaveSize 4
            list[0] shouldBe e1
            list[1] shouldBe existing
            list[2] shouldBe late
            list[3] shouldBe e3
        }

        // -------------------------------------------------------------------------
        // Insertion-order stability across three equal-timestamp events
        // -------------------------------------------------------------------------
        "equal timestamps preserve insertion order across three consecutive adds" {
            val t = Instant.ofEpochMilli(1000)
            val e1 =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("first")),
                )
            val e2 =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("second")),
                )
            val e3 =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("third")),
                )
            val list = mutableListOf<CaseEvent>()
            list.insertChronologically(e1)
            list.insertChronologically(e2)
            list.insertChronologically(e3)
            list[0] shouldBe e1
            list[1] shouldBe e2
            list[2] shouldBe e3
        }

        // -------------------------------------------------------------------------
        // Correctness at scale
        // -------------------------------------------------------------------------
        "large shuffled sequence stays fully sorted after 200 insertions" {
            val list = mutableListOf<CaseEvent>()
            (1..200).map { event(it.toLong() * 10) }.shuffled().forEach { list.insertChronologically(it) }
            list shouldHaveSize 200
            for (i in 0 until list.lastIndex) {
                (list[i].timestamp <= list[i + 1].timestamp) shouldBe true
            }
        }
    }
}
