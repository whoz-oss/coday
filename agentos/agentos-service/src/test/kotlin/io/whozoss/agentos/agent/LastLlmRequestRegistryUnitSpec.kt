package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A clock the test moves forward. */
class MovingClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

class LastLlmRequestRegistryUnitSpec :
    StringSpec({
        val start: Instant = Instant.parse("2026-01-01T10:00:00Z")
        val options = LlmRequestOptions(AiApiType.OpenAI, null, emptyMap(), "model", null, null, null)

        fun request(
            caseId: UUID = UUID.randomUUID(),
            finalText: String = "Done",
            question: String = "question",
        ) = LastLlmRequest(
            caseId = caseId,
            namespaceId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            messages = listOf(SystemMessage("system"), UserMessage(question)),
            toolDefinitions = emptyList(),
            modelId = UUID.randomUUID(),
            providerId = UUID.randomUUID(),
            requestOptions = options,
            finalText = finalText,
            agentName = "agent",
            agentConfigId = UUID.randomUUID(),
            coveredEventCount = 1,
            lastCoveredEventId = UUID.randomUUID(),
        )

        "returns what was put for a case, captured now, the latest one replacing the previous" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val caseId = UUID.randomUUID()

            registry.get(caseId).shouldBeNull()
            registry.put(request(caseId, finalText = "first"))
            clock.now = start.plusSeconds(10)
            registry.put(request(caseId, finalText = "second"))

            val stored = registry.get(caseId)!!
            stored.finalText shouldBe "second"
            stored.capturedAt shouldBe start.plusSeconds(10)
            stored.lastUsedAt shouldBe start.plusSeconds(10)
            registry.get(UUID.randomUUID()).shouldBeNull()
        }

        "touch records a use of the request it was captured as, and ignores a request replaced since" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val stored = request()
            registry.put(stored)
            val capturedAt = registry.get(stored.caseId)!!.capturedAt

            clock.now = start.plusSeconds(60)
            registry.touch(stored.caseId, capturedAt)
            registry.get(stored.caseId)!!.lastUsedAt shouldBe start.plusSeconds(60)

            registry.put(request(stored.caseId))
            clock.now = start.plusSeconds(120)
            registry.touch(stored.caseId, capturedAt)
            registry.get(stored.caseId)!!.lastCacheTouchAt.shouldBeNull()
        }

        "forgets a request once its time to live has passed, on read or on the periodic purge" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val read = request()
            val purged = request()
            registry.put(read)
            registry.put(purged)

            clock.now = start.plus(LastLlmRequestRegistry.TTL)
            registry.get(read.caseId).shouldNotBeNull()

            clock.now = start.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            registry.get(read.caseId).shouldBeNull()
            registry.purgeExpired()
            registry.estimatedSize() shouldBe 0L
        }

        "remove forgets the request of a case" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            registry.put(stored)

            registry.remove(stored.caseId)

            registry.get(stored.caseId).shouldBeNull()
            registry.estimatedSize() shouldBe 0L
        }

        "past its capacity, evicts the case captured the longest ago" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val requests = List(LastLlmRequestRegistry.MAX_ENTRIES) { request() }
            requests.forEach(registry::put)
            // The first case is captured again: it is no longer the oldest.
            registry.put(requests[0].copy(finalText = "again"))
            registry.put(request())
            registry.put(request())

            registry.get(requests[0].caseId)!!.finalText shouldBe "again"
            registry.get(requests[1].caseId).shouldBeNull()
            registry.get(requests[2].caseId).shouldBeNull()
            registry.get(requests[3].caseId).shouldNotBeNull()
        }

        "past its memory budget, evicts the oldest captures" {
            val big = "x".repeat(1_000)
            val oneSize = request(question = big).estimatedSize()
            val registry = LastLlmRequestRegistry(MovingClock(start), LlmRequestRegistryProperties(maxSize = oneSize * 2))
            val first = request(question = big)
            val second = request(question = big)
            val third = request(question = big)

            listOf(first, second, third).forEach(registry::put)

            registry.get(first.caseId).shouldBeNull()
            registry.get(second.caseId).shouldNotBeNull()
            registry.get(third.caseId).shouldNotBeNull()
            registry.estimatedSize() shouldBeLessThanOrEqual oneSize * 2
        }

        "keeps every request put concurrently, under its capacity" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val requests = List(400) { request() }

            val read =
                coroutineScope {
                    requests.map { stored -> async(Dispatchers.Default) { registry.put(stored) } }.awaitAll()
                    requests.map { stored -> async(Dispatchers.Default) { registry.get(stored.caseId)?.caseId } }.awaitAll()
                }

            read shouldBe requests.map { it.caseId }
        }

        "is current only while the conversation still ends on its answer" {
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            fun message(
                text: String,
                role: ActorRole,
            ): CaseEvent =
                MessageEvent(
                    namespaceId = namespaceId,
                    caseId = caseId,
                    actor = Actor(role.name, role.name, role),
                    content = listOf(MessageContent.Text(text)),
                )
            val question = message("Status?", ActorRole.USER)
            val answer = message("All green.", ActorRole.AGENT)
            val finished = AgentFinishedEvent(namespaceId = namespaceId, caseId = caseId, agentId = UUID.randomUUID(), agentName = "a")
            val warning = WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "late warning")
            val stored = request(caseId).copy(coveredEventCount = 2, lastCoveredEventId = answer.id)

            stored.isCurrentFor(listOf(question, answer, finished, warning)) shouldBe true
            // Another agent spoke since (a redirect, without any user message).
            stored.isCurrentFor(listOf(question, answer, finished, message("Redirected.", ActorRole.AGENT))) shouldBe false
            stored.isCurrentFor(listOf(question, answer, message("And now?", ActorRole.USER))) shouldBe false
            // An event of the conversation is gone.
            stored.isCurrentFor(listOf(answer)) shouldBe false
        }
    })
