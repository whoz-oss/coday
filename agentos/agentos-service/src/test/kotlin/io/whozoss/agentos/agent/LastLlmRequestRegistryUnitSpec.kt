package io.whozoss.agentos.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.caseEvent.AgentFinishedEvent
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.content.Media
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeTypeUtils
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Records [request] as captured by a run that started at the current generation of its case. */
fun LastLlmRequestRegistry.putCurrent(request: LastLlmRequest): Boolean = put(request, generation(request.caseId))

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
        val options = LlmRequestOptions(AiApiType.OpenAI, null, "model", null, null, null, null, "")

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
            registry.putCurrent(request(caseId, finalText = "first"))
            clock.now = start.plusSeconds(10)
            registry.putCurrent(request(caseId, finalText = "second"))

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
            registry.putCurrent(stored)
            val capturedAt = registry.get(stored.caseId)!!.capturedAt

            clock.now = start.plusSeconds(60)
            registry.touch(stored.caseId, capturedAt)
            registry.get(stored.caseId)!!.lastUsedAt shouldBe start.plusSeconds(60)

            registry.putCurrent(request(stored.caseId))
            clock.now = start.plusSeconds(120)
            registry.touch(stored.caseId, capturedAt)
            registry.get(stored.caseId)!!.lastCacheTouchAt.shouldBeNull()
        }

        "forgets a request once its time to live has passed, on read or on the periodic purge" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val read = request()
            val purged = request()
            registry.putCurrent(read)
            registry.putCurrent(purged)

            clock.now = start.plus(LastLlmRequestRegistry.TTL)
            registry.get(read.caseId).shouldNotBeNull()

            clock.now = start.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            registry.get(read.caseId).shouldBeNull()
            registry.purgeExpired()
            registry.estimatedSize() shouldBe 0L
        }

        "a refresh of the cache (touch) keeps the request alive for a time to live after it, at the time it was sent" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val stored = request()
            registry.putCurrent(stored)
            val capturedAt = registry.get(stored.caseId)!!.capturedAt
            val sentAt = start.plus(java.time.Duration.ofMinutes(58))

            clock.now = sentAt.plusSeconds(3)
            registry.touch(stored.caseId, capturedAt, sentAt)

            registry.get(stored.caseId)!!.lastUsedAt shouldBe sentAt
            clock.now = sentAt.plus(LastLlmRequestRegistry.TTL)
            registry.get(stored.caseId).shouldNotBeNull()
            clock.now = sentAt.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            registry.get(stored.caseId).shouldBeNull()
        }

        "the cache of a request was last used when it was sent, not when its answer ended" {
            val clock = MovingClock(start.plusSeconds(90))
            val registry = LastLlmRequestRegistry(clock)
            registry.putCurrent(request().copy(requestSentAt = start))
            val stored = registry.all().single()

            stored.capturedAt shouldBe start.plusSeconds(90)
            stored.lastUsedAt shouldBe start
            // A send time after the capture cannot be: the capture time is used.
            registry.putCurrent(request(stored.caseId).copy(requestSentAt = start.plusSeconds(120)))
            registry.get(stored.caseId)!!.lastUsedAt shouldBe start.plusSeconds(90)
        }

        "a capture made through LastLlmRequestCapture records when its last request was sent, by the registry's clock" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val caseId = UUID.randomUUID()
            val model = AiModel(aiProviderId = UUID.randomUUID(), apiModelName = "model")
            val provider = AiProvider(namespaceId = UUID.randomUUID(), name = "provider", apiType = AiApiType.OpenAI)
            val capture =
                LastLlmRequestCapture(
                    registry,
                    UUID.randomUUID(),
                    null,
                    model,
                    provider,
                    "agent",
                    null,
                    registry.invalidate(caseId),
                    io.whozoss.agentos.sdk.api.user.PromptCacheTtl.EXTENDED,
                )
            capture.recorder.requestSent(org.springframework.ai.chat.prompt.Prompt(listOf(UserMessage("q"))))
            // The answer streams for a minute and a half.
            clock.now = start.plusSeconds(90)
            capture.onFinalRequest(
                FinalLlmRequest(caseId, listOf(UserMessage("q")), emptyList(), "a", 1, UUID.randomUUID(), capture.lastExchange()!!.sentAt),
            )

            val stored = registry.get(caseId)!!
            stored.requestSentAt shouldBe start
            stored.lastUsedAt shouldBe start
            stored.promptCacheTtl shouldBe io.whozoss.agentos.sdk.api.user.PromptCacheTtl.EXTENDED
        }

        "all lists the live requests only" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val old = request()
            registry.putCurrent(old)
            clock.now = start.plus(LastLlmRequestRegistry.TTL).plusSeconds(1)
            val live = request()
            registry.putCurrent(live)

            registry.all().map { it.caseId } shouldBe listOf(live.caseId)
        }

        "remove forgets the request of a case" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            registry.putCurrent(stored)

            registry.remove(stored.caseId)

            registry.get(stored.caseId).shouldBeNull()
            registry.estimatedSize() shouldBe 0L
        }

        "past its capacity, evicts the case captured the longest ago" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val requests = List(LastLlmRequestRegistry.MAX_ENTRIES) { request() }
            requests.forEach(registry::putCurrent)
            // The first case is captured again: it is no longer the oldest.
            registry.putCurrent(requests[0].copy(finalText = "again"))
            registry.putCurrent(request())
            registry.putCurrent(request())

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

            listOf(first, second, third).forEach(registry::putCurrent)

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
                    requests.map { stored -> async(Dispatchers.Default) { registry.putCurrent(stored) } }.awaitAll()
                    requests.map { stored -> async(Dispatchers.Default) { registry.get(stored.caseId)?.caseId } }.awaitAll()
                }

            read shouldBe requests.map { it.caseId }
        }

        "a capture of a run is dropped when the case moved on since the run started (a kill, a deletion, a new run)" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            val atRunStart = registry.invalidate(stored.caseId)

            registry.invalidate(stored.caseId)

            registry.put(stored, atRunStart) shouldBe false
            registry.get(stored.caseId).shouldBeNull()
        }

        "invalidate forgets the request of a case and moves its generation on, never back" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            registry.putCurrent(stored) shouldBe true
            val before = registry.get(stored.caseId)!!.generation

            val after = registry.invalidate(stored.caseId)

            registry.get(stored.caseId).shouldBeNull()
            (after > before) shouldBe true
            registry.generation(stored.caseId) shouldBe after
            registry.generation(UUID.randomUUID()) shouldBe 0L
        }

        "a capture made through LastLlmRequestCapture is dropped when the case is killed during the turn" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val caseId = UUID.randomUUID()
            val provider = AiProvider(namespaceId = UUID.randomUUID(), name = "p", apiType = AiApiType.OpenAI)
            val model = AiModel(aiProviderId = provider.id, apiModelName = "m")
            val capture =
                LastLlmRequestCapture(
                    registry,
                    provider.namespaceId!!,
                    null,
                    model,
                    provider,
                    "agent",
                    null,
                    generation = registry.invalidate(caseId),
                )
            val turnEnd = FinalLlmRequest(caseId, listOf(UserMessage("q")), emptyList(), "a", 2, UUID.randomUUID())

            registry.invalidate(caseId)
            capture.onFinalRequest(turnEnd)

            registry.get(caseId).shouldBeNull()
        }

        "forget (a deletion) drops the request and the generation of a case, and a capture still in flight" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            val atRunStart = registry.invalidate(stored.caseId)
            registry.put(stored, atRunStart) shouldBe true

            registry.forget(stored.caseId)

            registry.get(stored.caseId).shouldBeNull()
            registry.generation(stored.caseId) shouldBe 0L
            registry.put(stored, atRunStart) shouldBe false
        }

        "the size estimate counts media bytes and tool call arguments, not only text" {
            val withMedia =
                request().copy(
                    messages =
                        listOf(
                            UserMessage
                                .builder()
                                .text("look")
                                .media(Media(MimeTypeUtils.IMAGE_PNG, ByteArrayResource(ByteArray(10_000))))
                                .build(),
                            AssistantMessage
                                .builder()
                                .content("")
                                .toolCalls(listOf(AssistantMessage.ToolCall("id", "function", "tool", "x".repeat(5_000))))
                                .build(),
                        ),
                    finalText = "",
                )

            withMedia.estimatedSize() shouldBeGreaterThanOrEqual 15_000L
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

        "the stamp of a capture outlives it through expiry and eviction, refreshed by touch" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock, LlmRequestRegistryProperties(maxSize = 1))
            val stored = request().copy(prefixTokens = 1200)
            registry.putCurrent(stored)
            // Evicted at once by the 1-character budget, the stamp stays.
            registry.get(stored.caseId).shouldBeNull()
            registry.stamp(stored.caseId) shouldBe
                PromptCacheStamp(start, 1200, stored.modelId, AiApiType.OpenAI, null, "model", stored.promptCacheTtl)

            val kept = LastLlmRequestRegistry(clock)
            kept.putCurrent(stored)
            clock.now = start.plusSeconds(200)
            kept.touch(stored.caseId, kept.get(stored.caseId)!!.capturedAt)
            clock.now = start.plus(LastLlmRequestRegistry.TTL).plusSeconds(300)
            kept.purgeExpired()
            kept.get(stored.caseId).shouldBeNull()
            kept.stamp(stored.caseId)!!.lastUsedAt shouldBe start.plusSeconds(200)
        }

        "invalidate drops the stamp with the request: a turn that captures nothing shows no previous one" {
            val registry = LastLlmRequestRegistry(MovingClock(start))
            val stored = request()
            registry.putCurrent(stored)

            registry.invalidate(stored.caseId)

            registry.stamp(stored.caseId).shouldBeNull()
        }

        "a stamp is dropped by forget, a week after its last use, or past the most recent cases" {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val deleted = request()
            val old = request()
            registry.putCurrent(deleted)
            registry.putCurrent(old)
            registry.forget(deleted.caseId)
            registry.stamp(deleted.caseId).shouldBeNull()

            // Only the periodic purge drops an old stamp.
            clock.now = start.plus(LastLlmRequestRegistry.STAMP_TTL).plusSeconds(1)
            registry.putCurrent(request())
            registry.stamp(old.caseId).shouldNotBeNull()
            registry.purgeExpired()
            registry.stamp(old.caseId).shouldBeNull()

            val cases = (0..LastLlmRequestRegistry.MAX_STAMPS).map { request() }
            cases.forEach { registry.putCurrent(it) }
            registry.stamp(cases.first().caseId).shouldBeNull()
            registry.stamp(cases[1].caseId).shouldNotBeNull()
            registry.stamp(cases.last().caseId).shouldNotBeNull()
        }
    })
