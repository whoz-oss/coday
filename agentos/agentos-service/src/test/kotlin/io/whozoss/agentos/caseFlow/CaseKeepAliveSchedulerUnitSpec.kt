package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.chat.AnthropicProperties
import io.whozoss.agentos.chat.ChatModelFactory
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.chat.RequestyPromptCacheProperties
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.usage.LlmUsage
import io.whozoss.agentos.usage.RunCostService.NonPausingRegistration
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** When [CaseKeepAliveScheduler] pings, and when it does not: the ping itself is [CaseKeepAliveWireSpec]'s. */
class CaseKeepAliveSchedulerUnitSpec :
    StringSpec({
        val start: Instant = Instant.parse("2026-01-01T10:00:00Z")

        class MovingClock(
            var now: Instant,
        ) : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?): Clock = this

            override fun instant(): Instant = now
        }

        fun options(
            apiType: AiApiType = AiApiType.OpenAI,
            apiModelName: String = "anthropic/claude-sonnet-4-5",
            reasoningEffort: ReasoningEffort? = null,
        ) = LlmRequestOptions(apiType, "https://router.requesty.ai/v1", apiModelName, null, null, reasoningEffort, null, "digest")

        fun idleCase() =
            Case(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = UUID.randomUUID(), status = CaseStatus.IDLE, keepAlive = true)

        /** A prompt of 2000 tokens, 1900 read from the cache. */
        val cacheHit = LlmUsage(inputTokens = 100, cacheReadTokens = 1900, outputTokens = 1, totalTokens = 2001)

        class Fixture(
            ttl: PromptCacheTtl = PromptCacheTtl.FIVE_MINUTES,
            val case: Case = idleCase(),
            requestOptions: LlmRequestOptions = options(),
            maxIdle: Duration = Duration.ofHours(2),
            usageEnabled: Boolean = true,
            /** How long the agent's final request streamed before the turn ended and the request was captured. */
            streamDuration: Duration = Duration.ZERO,
        ) {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            val keepAliveIds = mockk<CaseKeepAliveIds> { every { current() } answers { if (case.keepAlive) setOf(case.id) else emptySet() } }
            var storedCase: Case? = case
            val pinged = CopyOnWriteArrayList<LastLlmRequest>()
            var failNext = 0
            var nextResult: CaseKeepAlivePinger.Result = CaseKeepAlivePinger.Result.Sent(cacheHit)
            val caseService =
                mockk<CaseService> {
                    every { findByIds(any(), false) } answers { listOfNotNull(storedCase).filter { it.id in firstArg<Collection<UUID>>() } }
                    every { findById(case.id, false) } answers { storedCase }
                }
            val pinger =
                mockk<CaseKeepAlivePinger> {
                    every { ping(any(), any()) } answers {
                        if (failNext > 0) {
                            failNext--
                            throw IllegalStateException("provider down")
                        }
                        nextResult.also { if (it is CaseKeepAlivePinger.Result.Sent) pinged.add(secondArg()) }
                    }
                }
            val scheduler =
                CaseKeepAliveScheduler(
                    registry = registry,
                    caseService = caseService,
                    pinger = pinger,
                    keepAliveCaseIds = keepAliveIds,
                    chatModelFactory =
                        ChatModelFactory(
                            ObservationRegistry.NOOP,
                            AnthropicProperties(promptCachingEnabled = true),
                            UsageConfigProperties(enabled = true),
                            RequestyPromptCacheProperties(),
                        ) { true },
                    properties = CaseKeepAliveProperties(maxIdle = maxIdle),
                    clock = clock,
                    usageConfig = UsageConfigProperties(enabled = usageEnabled),
                ).apply { executor = Executor { it.run() } }

            /** The agent's last request: sent at [start], captured once its answer streamed. */
            var request: LastLlmRequest = capture(ttl, requestOptions, streamDuration)

            fun capture(
                ttl: PromptCacheTtl = PromptCacheTtl.FIVE_MINUTES,
                requestOptions: LlmRequestOptions = options(),
                streamDuration: Duration = Duration.ZERO,
                sentAt: Instant = start,
            ): LastLlmRequest {
                val generation = registry.invalidate(case.id)
                clock.now = sentAt.plus(streamDuration)
                registry.put(
                    LastLlmRequest(
                        caseId = case.id,
                        namespaceId = case.namespaceId,
                        userId = UUID.randomUUID(),
                        messages = listOf(SystemMessage("system"), UserMessage("question")),
                        toolDefinitions = emptyList(),
                        modelId = UUID.randomUUID(),
                        providerId = UUID.randomUUID(),
                        requestOptions = requestOptions,
                        finalText = "Done",
                        agentName = "planner",
                        agentConfigId = UUID.randomUUID(),
                        coveredEventCount = 2,
                        lastCoveredEventId = UUID.randomUUID(),
                        promptCacheTtl = ttl,
                        requestSentAt = sentAt,
                    ),
                    generation,
                )
                return registry.get(case.id)!!.also { request = it }
            }

            /** Moves the clock to [elapsed] after the agent's request was sent and runs a tick. */
            fun tickAt(elapsed: Duration): List<UUID> {
                clock.now = start.plus(elapsed)
                return scheduler.refreshDue()
            }
        }

        val fiveMinuteDue = CaseKeepAliveScheduler.FIVE_MINUTE_INTERVAL
        val oneHourDue = CaseKeepAliveScheduler.ONE_HOUR_INTERVAL

        "the refresh intervals leave room for a late tick and the provider's latency: 4:20 and 58:00" {
            fiveMinuteDue shouldBe Duration.ofSeconds(260)
            oneHourDue shouldBe Duration.ofMinutes(58)
        }

        "a 5-minute cache is not pinged before 4:20 after the agent's request, and is at 4:20" {
            val fixture = Fixture()

            fixture.tickAt(fiveMinuteDue.minusSeconds(1)).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.pinged.single() shouldBe fixture.request
        }

        "the timer starts when the agent's final request was sent, not when its answer finished streaming" {
            val fixture = Fixture(streamDuration = Duration.ofSeconds(90))
            fixture.request.capturedAt shouldBe start.plusSeconds(90)

            // 4:20 after the request was sent, though only 2:50 after its capture.
            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
        }

        "a 1-hour cache (Claude via Requesty) is not pinged at 4:20 nor before 58:00, and is at 58:00" {
            val fixture = Fixture(ttl = PromptCacheTtl.ONE_HOUR)

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.tickAt(oneHourDue.minusSeconds(1)).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()

            fixture.tickAt(oneHourDue) shouldBe listOf(fixture.case.id)
        }

        "a 1-hour choice the request did not get (not a Claude model) keeps the 5-minute interval" {
            val fixture = Fixture(ttl = PromptCacheTtl.ONE_HOUR, requestOptions = options(apiModelName = "openai/gpt-4.1"))

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
        }

        "a successful ping touches the request at its send time, and the next one is due 4:20 after it" {
            val fixture = Fixture()

            fixture.tickAt(fiveMinuteDue)
            fixture.registry.get(fixture.case.id)!!.lastCacheTouchAt shouldBe start.plus(fiveMinuteDue)

            fixture.tickAt(fiveMinuteDue.multipliedBy(2).minusSeconds(1)).shouldBeEmpty()
            fixture.tickAt(fiveMinuteDue.multipliedBy(2)) shouldBe listOf(fixture.case.id)
            fixture.pinged.size shouldBe 2
        }

        "pings keep a 1-hour request in the registry past its own lifetime, up to the maximum idle duration" {
            val fixture = Fixture(ttl = PromptCacheTtl.ONE_HOUR)

            fixture.tickAt(oneHourDue) shouldBe listOf(fixture.case.id)
            // 65 minutes after the capture the request would have expired without the ping.
            fixture.tickAt(Duration.ofMinutes(100)).shouldBeEmpty()
            fixture.registry.get(fixture.case.id).shouldNotBeNull()
            fixture.tickAt(oneHourDue.multipliedBy(2)) shouldBe listOf(fixture.case.id)
            // Past the maximum idle duration (2 h after the capture), no more ping.
            fixture.tickAt(oneHourDue.multipliedBy(3)).shouldBeEmpty()
            fixture.pinged.size shouldBe 2
        }

        "no ping once the maximum idle duration has passed since the agent's request was captured" {
            val fixture = Fixture(maxIdle = Duration.ofMinutes(8))

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            // Due again at 8:40, past the 8 minutes.
            fixture.tickAt(fiveMinuteDue.multipliedBy(2)).shouldBeEmpty()
            fixture.pinged.size shouldBe 1
        }

        "no case is read while no case has keep-alive on, even with a due request" {
            val fixture = Fixture(case = idleCase().copy(keepAlive = false))

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()

            verify(exactly = 0) { fixture.caseService.findByIds(any(), any()) }
            verify(exactly = 0) { fixture.caseService.findById(any(), any()) }
            fixture.pinged.shouldBeEmpty()
        }

        "no ping, and no case read, when usage tracking is off" {
            val fixture = Fixture(usageEnabled = false)

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()

            verify(exactly = 0) { fixture.keepAliveIds.current() }
            verify(exactly = 0) { fixture.caseService.findByIds(any(), any()) }
        }

        "no ping when the keep-alive was turned off since the ids were read" {
            val fixture = Fixture()
            fixture.storedCase = fixture.case.copy(keepAlive = false)

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()
        }

        "a case that is not idle is not pinged, and is only checked again at the next interval" {
            listOf(CaseStatus.RUNNING, CaseStatus.PENDING, CaseStatus.KILLED, CaseStatus.ERROR).forEach { status ->
                val fixture = Fixture()
                fixture.storedCase = fixture.case.copy(status = status)

                fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
                fixture.storedCase = fixture.case
                fixture.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()
                fixture.tickAt(fiveMinuteDue.multipliedBy(2)) shouldBe listOf(fixture.case.id)
                verify(exactly = 2) { fixture.caseService.findByIds(any(), any()) }
            }
        }

        "no ping while the case is removed or missing" {
            val fixture = Fixture()
            fixture.storedCase = null

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()
        }

        "a ping refused by the cost guard is checked again at the next interval only" {
            val fixture = Fixture()
            fixture.nextResult = CaseKeepAlivePinger.Result.CostRefused(NonPausingRegistration.Reason.OVER_THRESHOLD)

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()
            fixture.tickAt(fiveMinuteDue.plusSeconds(40)).shouldBeEmpty()
            verify(exactly = 1) { fixture.pinger.ping(any(), any()) }
            fixture.registry.get(fixture.case.id)!!.lastCacheTouchAt.shouldBeNull()

            fixture.nextResult = CaseKeepAlivePinger.Result.Sent(cacheHit)
            fixture.tickAt(fiveMinuteDue.multipliedBy(2)) shouldBe listOf(fixture.case.id)
            fixture.pinged.size shouldBe 1
        }

        "no ping when the request was sent with a reasoning effort, but one with effort NONE is pinged" {
            val thinking = Fixture(requestOptions = options(reasoningEffort = ReasoningEffort.HIGH))
            thinking.tickAt(fiveMinuteDue).shouldBeEmpty()
            thinking.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()
            verify(exactly = 0) { thinking.pinger.ping(any(), any()) }

            val none = Fixture(requestOptions = options(reasoningEffort = ReasoningEffort.NONE))
            none.tickAt(fiveMinuteDue) shouldBe listOf(none.case.id)
        }

        "no ping for a provider that is not OpenAI-compatible" {
            val fixture = Fixture(requestOptions = options(apiType = AiApiType.Anthropic))

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            verify(exactly = 0) { fixture.pinger.ping(any(), any()) }
        }

        "a model or provider that changed since the agent's request stops the pings until the next turn" {
            val fixture = Fixture()
            fixture.nextResult = CaseKeepAlivePinger.Result.OptionsChanged

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()
            verify(exactly = 1) { fixture.pinger.ping(any(), any()) }
        }

        "a failed ping does not touch the request and is retried at the next tick; a success resets the count" {
            val fixture = Fixture()
            fixture.failNext = 1

            fixture.tickAt(fiveMinuteDue)
            fixture.registry.get(fixture.case.id)!!.lastCacheTouchAt.shouldBeNull()
            fixture.pinged.shouldBeEmpty()

            val retried = fiveMinuteDue.plusSeconds(20)
            fixture.tickAt(retried) shouldBe listOf(fixture.case.id)
            fixture.pinged.size shouldBe 1

            // One more failure after a success is not the second in a row.
            fixture.failNext = 1
            fixture.tickAt(retried.plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
            fixture.tickAt(retried.plus(fiveMinuteDue).plusSeconds(20)) shouldBe listOf(fixture.case.id)
            fixture.pinged.size shouldBe 2
        }

        "two failures in a row stop the pings until the next agent turn" {
            val fixture = Fixture()
            fixture.failNext = 2

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(40)).shouldBeEmpty()
            verify(exactly = 2) { fixture.pinger.ping(any(), any()) }

            // A new turn replaces the request: its cache is kept alive again.
            val next = fixture.capture(sentAt = start.plus(Duration.ofMinutes(6)))
            fixture.tickAt(Duration.ofMinutes(6).plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
            fixture.pinged.single() shouldBe next
        }

        "a ping that read nothing from the cache of a long prompt is a failure; a short prompt is not" {
            val fixture = Fixture()
            fixture.nextResult = CaseKeepAlivePinger.Result.Sent(LlmUsage(inputTokens = 2000, outputTokens = 1, totalTokens = 2001))

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.registry.get(fixture.case.id)!!.lastCacheTouchAt.shouldBeNull()
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(40)).shouldBeEmpty()

            CaseKeepAliveScheduler.isCacheMiss(LlmUsage(inputTokens = 500, outputTokens = 1, totalTokens = 501)) shouldBe false
            CaseKeepAliveScheduler.isCacheMiss(cacheHit) shouldBe false
        }

        "a case whose ping is still in flight is not pinged again" {
            val fixture = Fixture()
            val queued = CopyOnWriteArrayList<Runnable>()
            fixture.scheduler.executor = Executor { queued.add(it) }

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()

            queued.single().run()
            fixture.scheduler.executor = Executor { it.run() }
            fixture.tickAt(fiveMinuteDue.multipliedBy(2).plusSeconds(20)) shouldBe listOf(fixture.case.id)
        }

        "the worker checks again before sending: a run started since the tick means no ping" {
            val fixture = Fixture()
            val queued = CopyOnWriteArrayList<Runnable>()
            fixture.scheduler.executor = Executor { queued.add(it) }
            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)

            // A run starts: the registry outdates the request.
            fixture.registry.invalidate(fixture.case.id)
            queued.single().run()

            verify(exactly = 0) { fixture.pinger.ping(any(), any()) }
        }

        "the worker checks again before sending: a case no longer idle or with keep-alive off is not pinged" {
            listOf<(Case) -> Case>({ it.copy(status = CaseStatus.RUNNING) }, { it.copy(keepAlive = false) }).forEach { change ->
                val fixture = Fixture()
                val queued = CopyOnWriteArrayList<Runnable>()
                fixture.scheduler.executor = Executor { queued.add(it) }
                fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)

                fixture.storedCase = change(fixture.case)
                queued.single().run()

                verify(exactly = 0) { fixture.pinger.ping(any(), any()) }
            }
        }

        "a ping that ends after a new turn replaced the request does not touch the new one" {
            val fixture = Fixture()
            fixture.tickAt(Duration.ZERO)
            every { fixture.pinger.ping(any(), any()) } answers {
                fixture.capture(sentAt = start.plus(fiveMinuteDue).plusSeconds(5))
                CaseKeepAlivePinger.Result.Sent(cacheHit)
            }

            fixture.tickAt(fiveMinuteDue)

            fixture.registry.get(fixture.case.id)!!.lastCacheTouchAt.shouldBeNull()
        }
    })
