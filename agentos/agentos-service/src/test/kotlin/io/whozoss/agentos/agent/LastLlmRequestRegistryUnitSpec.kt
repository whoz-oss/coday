package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class LastLlmRequestRegistryUnitSpec :
    StringSpec({
        val start: Instant = Instant.parse("2026-01-01T10:00:00Z")

        /** A clock the test moves forward. */
        class MovingClock(
            var now: Instant,
        ) : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

        val provider = AiProvider(namespaceId = UUID.randomUUID(), name = "provider", apiType = AiApiType.OpenAI)
        val model = AiModel(aiProviderId = provider.id, apiModelName = "model")

        fun request(
            caseId: UUID = UUID.randomUUID(),
            capturedAt: Instant = start,
            finalText: String = "Done",
        ) = LastLlmRequest(
            caseId = caseId,
            messages = listOf(SystemMessage("system"), UserMessage("question")),
            toolDefinitions = emptyList(),
            model = model,
            provider = provider,
            finalText = finalText,
            agentName = "agent",
            agentConfigId = UUID.randomUUID(),
            coveredEventCount = 1,
            lastCoveredEventId = UUID.randomUUID(),
            capturedAt = capturedAt,
        )

        "returns what was put for a case, the latest one replacing the previous" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val caseId = UUID.randomUUID()
            val first = request(caseId, finalText = "first")
            val second = request(caseId, finalText = "second")

            registry.get(caseId).shouldBeNull()
            registry.put(first)
            registry.get(caseId) shouldBeSameInstanceAs first
            registry.put(second)
            registry.get(caseId) shouldBeSameInstanceAs second
            registry.get(UUID.randomUUID()).shouldBeNull()
        }

        "forgets a request once its time to live has passed" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val stored = request()
            registry.put(stored)

            clock.now = start.plus(LastLlmRequestRegistry.TTL)
            registry.get(stored.caseId).shouldNotBeNull()

            clock.now = start.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            registry.get(stored.caseId).shouldBeNull()
        }

        "remove forgets the request of a case" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            registry.put(stored)

            registry.remove(stored.caseId)

            registry.get(stored.caseId).shouldBeNull()
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

        "touch records the refresh, which keeps the request alive for a time to live after it" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val stored = request()
            registry.put(stored)
            val refreshedAt = start.plus(Duration.ofMinutes(59))

            registry.touch(stored.caseId, stored.capturedAt, refreshedAt)

            registry.get(stored.caseId)!!.lastRefreshAt shouldBe refreshedAt
            clock.now = refreshedAt.plus(LastLlmRequestRegistry.TTL)
            registry.get(stored.caseId).shouldNotBeNull()
            clock.now = refreshedAt.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            registry.get(stored.caseId).shouldBeNull()
        }

        "touch leaves alone a request that replaced the one refreshed, and a case without request" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            val newer = request(stored.caseId, capturedAt = start.plusSeconds(30))
            registry.put(stored)
            registry.put(newer)

            registry.touch(stored.caseId, stored.capturedAt, start.plusSeconds(60))
            registry.touch(UUID.randomUUID(), start, start.plusSeconds(60))

            registry.get(stored.caseId) shouldBeSameInstanceAs newer
            registry.all() shouldBe listOf(newer)
        }

        "all lists the live requests, the least recently captured or refreshed first" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val old = request(capturedAt = start.minus(LastLlmRequestRegistry.TTL).minusSeconds(1))
            val first = request()
            val second = request()
            listOf(old, first, second).forEach(registry::put)

            registry.all() shouldBe listOf(first, second)
            registry.touch(first.caseId, first.capturedAt, start.plusSeconds(10))
            registry.all().map { it.caseId } shouldBe listOf(second.caseId, first.caseId)
        }

        "keeps every request put concurrently, under its capacity" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val requests = List(400) { request() }

            coroutineScope {
                requests.map { stored -> async(Dispatchers.Default) { registry.put(stored) } }.awaitAll()
                requests.map { stored -> async(Dispatchers.Default) { registry.get(stored.caseId) } }.awaitAll()
            } shouldBe requests
        }
    })
