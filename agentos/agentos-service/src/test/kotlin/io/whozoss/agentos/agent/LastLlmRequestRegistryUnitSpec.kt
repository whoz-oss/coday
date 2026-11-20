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

        "keeps every request put concurrently, under its capacity" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val requests = List(400) { request() }

            coroutineScope {
                requests.map { stored -> async(Dispatchers.Default) { registry.put(stored) } }.awaitAll()
                requests.map { stored -> async(Dispatchers.Default) { registry.get(stored.caseId) } }.awaitAll()
            } shouldBe requests
        }
    })
