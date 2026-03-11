package io.whozoss.agentos.caseEvent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import java.time.Instant
import java.util.UUID

class InsertChronologicallySpec : StringSpec() {
    val projectId = UUID.randomUUID()
    val caseId = UUID.randomUUID()

    fun event(epochMilli: Long): MessageEvent =
        MessageEvent(
            projectId = projectId,
            caseId = caseId,
            timestamp = Instant.ofEpochMilli(epochMilli),
            actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
            content = listOf(MessageContent.Text("msg")),
        )

    init {
        "insert into empty list places event at index 0" {
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>()
            val e = event(1000)
            list.insertChronologically(e)
            list shouldHaveSize 1
            list[0] shouldBe e
        }

        "insert before all existing events places event at head" {
            val e2 = event(2000)
            val e3 = event(3000)
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>(e2, e3)
            val e1 = event(1000)
            list.insertChronologically(e1)
            list shouldHaveSize 3
            list[0] shouldBe e1
            list[1] shouldBe e2
            list[2] shouldBe e3
        }

        "insert after all existing events places event at tail" {
            val e1 = event(1000)
            val e2 = event(2000)
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>(e1, e2)
            val e3 = event(3000)
            list.insertChronologically(e3)
            list shouldHaveSize 3
            list[0] shouldBe e1
            list[1] shouldBe e2
            list[2] shouldBe e3
        }

        "insert in the middle preserves sorted order" {
            val e1 = event(1000)
            val e3 = event(3000)
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>(e1, e3)
            val e2 = event(2000)
            list.insertChronologically(e2)
            list shouldHaveSize 3
            list[0] shouldBe e1
            list[1] shouldBe e2
            list[2] shouldBe e3
        }

        "equal timestamps preserve insertion order (stable)" {
            val t = Instant.ofEpochMilli(1000)
            val e1 =
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("first")),
                )
            val e2 =
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("second")),
                )
            val e3 =
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    timestamp = t,
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("third")),
                )
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>()
            list.insertChronologically(e1)
            list.insertChronologically(e2)
            list.insertChronologically(e3)
            list[0] shouldBe e1
            list[1] shouldBe e2
            list[2] shouldBe e3
        }

        "new event with same timestamp as existing goes after it" {
            val existing = event(2000)
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>(event(1000), existing, event(3000))
            val late =
                MessageEvent(
                    projectId = projectId,
                    caseId = caseId,
                    timestamp = Instant.ofEpochMilli(2000),
                    actor = Actor(id = "u", displayName = "U", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("late")),
                )
            list.insertChronologically(late)
            list shouldHaveSize 4
            // existing (t=2000) at index 1, late (t=2000) at index 2
            list[1] shouldBe existing
            list[2] shouldBe late
        }

        "large sorted sequence stays sorted after many insertions" {
            val list = mutableListOf<io.whozoss.agentos.sdk.caseEvent.CaseEvent>()
            val shuffled = (1..200).map { event(it.toLong() * 10) }.shuffled()
            shuffled.forEach { list.insertChronologically(it) }
            list shouldHaveSize 200
            for (i in 0 until list.lastIndex) {
                (list[i].timestamp <= list[i + 1].timestamp) shouldBe true
            }
        }
    }
}
