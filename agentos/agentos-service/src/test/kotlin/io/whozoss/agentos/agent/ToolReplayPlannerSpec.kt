package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.ToolRequestEvent
import io.whozoss.agentos.sdk.caseEvent.ToolResponseEvent
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import java.util.UUID

class ToolReplayPlannerSpec : StringSpec({

    val ns = UUID.randomUUID()
    val case = UUID.randomUUID()

    fun request(id: String, toolName: String = "TOOL__x", args: String = "arg") =
        ToolRequestEvent(namespaceId = ns, caseId = case, toolRequestId = id, toolName = toolName, args = args)

    fun response(
        id: String,
        toolName: String = "TOOL__x",
        output: String = "ok",
        images: List<MessageContent.Image> = emptyList(),
    ) = ToolResponseEvent(
        namespaceId = ns,
        caseId = case,
        toolRequestId = id,
        toolName = toolName,
        output = MessageContent.Text(output),
        success = true,
        images = images,
    )

    fun image() = MessageContent.Image(content = "aGVsbG8=", mimeType = "image/jpeg", width = 10, height = 10)

    // -------------------------------------------------------------------------
    // Newest-first ordering
    // -------------------------------------------------------------------------

    "newest-first: last exchange is always detailed when budget allows" {
        val events = listOf(
            request("r1"), response("r1", output = "A".repeat(100)),
            request("r2"), response("r2", output = "B".repeat(100)),
        )
        // budget only fits r2 (200 chars per pair, budget = 120)
        val plan = ToolReplayPlanner(maxDetailedChars = 120, maxAttachedImages = 20, imageCharCost = 6_000).plan(events)

        plan.isDetailed("r2") shouldBe true
        plan.isDetailed("r1") shouldBe false
    }

    "newest-first: both exchanges detailed when budget is large enough" {
        val events = listOf(
            request("r1"), response("r1", output = "ok"),
            request("r2"), response("r2", output = "ok"),
        )
        val plan = ToolReplayPlanner(maxDetailedChars = 10_000, maxAttachedImages = 20, imageCharCost = 6_000).plan(events)

        plan.isDetailed("r1") shouldBe true
        plan.isDetailed("r2") shouldBe true
    }

    // -------------------------------------------------------------------------
    // maxAttachedImages cap
    // -------------------------------------------------------------------------

    "images: newest responses keep media up to the cap" {
        // 3 responses of 5 images each, cap = 10 → r3 and r2 get media, r1 does not
        val events = listOf(
            request("r1"), response("r1", images = List(5) { image() }),
            request("r2"), response("r2", images = List(5) { image() }),
            request("r3"), response("r3", images = List(5) { image() }),
        )
        val plan = ToolReplayPlanner(maxDetailedChars = Int.MAX_VALUE, maxAttachedImages = 10, imageCharCost = 0).plan(events)

        plan.hasAttachedMedia("r3") shouldBe true
        plan.hasAttachedMedia("r2") shouldBe true
        plan.hasAttachedMedia("r1") shouldBe false
    }

    "images: response without images never gets media attached" {
        val events = listOf(request("r1"), response("r1"))
        val plan = ToolReplayPlanner(maxDetailedChars = Int.MAX_VALUE, maxAttachedImages = 20, imageCharCost = 0).plan(events)

        plan.hasAttachedMedia("r1") shouldBe false
    }

    // -------------------------------------------------------------------------
    // maxDetailedChars budget with break
    // -------------------------------------------------------------------------

    "budget break stops processing older exchanges once limit is reached" {
        // Each pair costs ~6 chars (3 args + 3 output), budget = 7 → only r3 fits
        val events = listOf(
            request("r1", args = "aaa"), response("r1", output = "bbb"),
            request("r2", args = "aaa"), response("r2", output = "bbb"),
            request("r3", args = "aaa"), response("r3", output = "bbb"),
        )
        val plan = ToolReplayPlanner(maxDetailedChars = 7, maxAttachedImages = 20, imageCharCost = 6_000).plan(events)

        plan.isDetailed("r3") shouldBe true
        plan.isDetailed("r2") shouldBe false
        plan.isDetailed("r1") shouldBe false
    }

    // -------------------------------------------------------------------------
    // Image cost is not charged when media is evicted
    // -------------------------------------------------------------------------

    "evicted images are not charged against the char budget" {
        // 3 responses of 10 images each, cap = 20 → r3 and r2 get media, r1 does not
        // r1 must NOT be charged imageCharCost * 10 — it must remain detailed (only text cost)
        val imageCharCost = 6_000
        val textCostPerPair = 3 // args="a" (1) + output="ok" (2)
        val budget = 20 * imageCharCost + 3 * textCostPerPair + 10 // enough for all text, 20 images

        val events = listOf(
            request("r1", args = "a"), response("r1", output = "ok", images = List(10) { image() }),
            request("r2", args = "a"), response("r2", output = "ok", images = List(10) { image() }),
            request("r3", args = "a"), response("r3", output = "ok", images = List(10) { image() }),
        )
        val plan = ToolReplayPlanner(
            maxDetailedChars = budget,
            maxAttachedImages = 20,
            imageCharCost = imageCharCost,
        ).plan(events)

        // All three are detailed (r1 text cost only, r2 and r3 text + image cost)
        plan.isDetailed("r1") shouldBe true
        plan.isDetailed("r2") shouldBe true
        plan.isDetailed("r3") shouldBe true
        // Only r2 and r3 have media (r1 evicted by cap)
        plan.hasAttachedMedia("r3") shouldBe true
        plan.hasAttachedMedia("r2") shouldBe true
        plan.hasAttachedMedia("r1") shouldBe false
    }

    // -------------------------------------------------------------------------
    // Int.MAX_VALUE disables compression (AgentSimple behaviour)
    // -------------------------------------------------------------------------

    "Int.MAX_VALUE maxDetailedChars: all exchanges are detailed regardless of content size" {
        val events =
            (1..10).flatMap { i ->
                listOf(
                    request("r$i", args = "A".repeat(50_000)),
                    response("r$i", output = "B".repeat(50_000)),
                )
            }
        val plan = ToolReplayPlanner(maxDetailedChars = Int.MAX_VALUE, maxAttachedImages = 20, imageCharCost = 0).plan(events)

        (1..10).forEach { i ->
            plan.isDetailed("r$i") shouldBe true
        }
    }

    "budget accumulation uses Long: break triggers correctly when cumulated cost exceeds Int.MAX_VALUE" {
        // imageCharCost = 600_000_000 so each pair with 1 image costs ~600M chars.
        // r4+r3+r2 = ~1.8B < Int.MAX_VALUE (2_147_483_647) → all detailed.
        // r1 would push total to ~2.4B > Int.MAX_VALUE → break, r1 NOT detailed.
        //
        // With a naive Int accumulator: after r2 charsCollected = ~1.8B (fits in Int);
        // adding r1's cost overflows to a negative value; `negative > Int.MAX_VALUE` is
        // false → no break → r1 would be wrongly marked detailed.
        val bigImageCost = 600_000_000
        val events =
            (1..4).flatMap { i ->
                listOf(
                    request("r$i", args = "a"),
                    response("r$i", output = "ok", images = List(1) { image() }),
                )
            }
        val plan = ToolReplayPlanner(
            maxDetailedChars = Int.MAX_VALUE,
            maxAttachedImages = 20,
            imageCharCost = bigImageCost,
        ).plan(events)

        plan.isDetailed("r4") shouldBe true  // newest, always fits
        plan.isDetailed("r3") shouldBe true  // cumulated ~1.2B
        plan.isDetailed("r2") shouldBe true  // cumulated ~1.8B < Int.MAX_VALUE
        plan.isDetailed("r1") shouldBe false // would push to ~2.4B > Int.MAX_VALUE → break
    }

    // -------------------------------------------------------------------------
    // Non-tool events are ignored
    // -------------------------------------------------------------------------

    "non-tool events in the list are ignored" {
        val userMsg = MessageEvent(
            namespaceId = ns,
            caseId = case,
            actor = Actor("u1", "User", ActorRole.USER),
            content = listOf(MessageContent.Text("hello")),
        )
        val events = listOf(userMsg, request("r1"), response("r1"))
        val plan = ToolReplayPlanner(maxDetailedChars = Int.MAX_VALUE, maxAttachedImages = 20, imageCharCost = 0).plan(events)

        plan.isDetailed("r1") shouldBe true
    }

    // -------------------------------------------------------------------------
    // Orphaned requests (no matching response)
    // -------------------------------------------------------------------------

    "orphaned request with no response is still considered for the plan" {
        val events = listOf(request("orphan"))
        // No response — cost is only args length = 3
        val plan = ToolReplayPlanner(maxDetailedChars = 10, maxAttachedImages = 20, imageCharCost = 0).plan(events)

        plan.isDetailed("orphan") shouldBe true
        plan.hasAttachedMedia("orphan") shouldBe false
    }
})
