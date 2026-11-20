package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.aiProvider.ReasoningEffort
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
import io.whozoss.agentos.sdk.api.user.PromptCacheTtl
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.usage.RunCostService
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

        val openAi = AiProvider(namespaceId = UUID.randomUUID(), name = "requesty", apiType = AiApiType.OpenAI)
        val model = AiModel(aiProviderId = openAi.id, apiModelName = "anthropic/claude-sonnet-4-5")

        fun idleCase() =
            Case(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = UUID.randomUUID(), status = CaseStatus.IDLE, keepAlive = true)

        class Fixture(
            ttl: PromptCacheTtl = PromptCacheTtl.FIVE_MINUTES,
            val case: Case = idleCase(),
            requestModel: AiModel = model,
            provider: AiProvider = openAi,
            runCost: RunCostDto? = null,
            maxIdle: Duration = Duration.ofHours(2),
        ) {
            val clock = MovingClock(start)
            val registry = LastLlmRequestRegistry(clock)
            var storedCase: Case? = case
            val pinged = CopyOnWriteArrayList<LastLlmRequest>()
            var failNext = 0
            val caseService =
                mockk<CaseService> {
                    every { findByIds(any(), false) } answers { listOfNotNull(storedCase).filter { it.id in firstArg<Collection<UUID>>() } }
                }
            val pinger =
                mockk<CaseKeepAlivePinger> {
                    every { ping(any(), any()) } answers {
                        if (failNext > 0) {
                            failNext--
                            throw IllegalStateException("provider down")
                        }
                        pinged.add(secondArg())
                    }
                }
            val runCostService =
                runCost?.let { dto -> mockk<RunCostService> { every { state(case.id) } returns dto } }
            val scheduler =
                CaseKeepAliveScheduler(
                    registry = registry,
                    caseService = caseService,
                    pinger = pinger,
                    properties = CaseKeepAliveProperties(maxIdle = maxIdle),
                    clock = clock,
                    usageConfig = UsageConfigProperties(enabled = true),
                    runCostService = runCostService,
                ).apply { executor = Executor { it.run() } }

            val request =
                LastLlmRequest(
                    caseId = case.id,
                    messages = listOf(SystemMessage("system"), UserMessage("question")),
                    toolDefinitions = emptyList(),
                    model = requestModel,
                    provider = provider,
                    finalText = "Done",
                    agentName = "planner",
                    agentConfigId = UUID.randomUUID(),
                    coveredEventCount = 2,
                    lastCoveredEventId = UUID.randomUUID(),
                    promptCacheTtl = ttl,
                    userId = UUID.randomUUID(),
                    capturedAt = start,
                ).also(registry::put)

            /** Moves the clock to [elapsed] after the capture of the request and runs a tick. */
            fun tickAt(elapsed: Duration): List<UUID> {
                clock.now = start.plus(elapsed)
                return scheduler.refreshDue()
            }
        }

        fun runCost(
            cost: Double = 0.0,
            threshold: Double? = null,
            paused: Boolean = false,
        ) = RunCostDto(UUID.randomUUID(), start, cost, 0, threshold, paused, active = false, liveTokens = 0)

        val fiveMinuteDue = CaseKeepAliveScheduler.FIVE_MINUTE_INTERVAL
        val oneHourDue = CaseKeepAliveScheduler.ONE_HOUR_INTERVAL

        "the refresh intervals are 4:40 for a 5-minute cache and 59:30 for a 1-hour one" {
            CaseKeepAliveScheduler.refreshInterval(PromptCacheTtl.FIVE_MINUTES) shouldBe Duration.ofSeconds(280)
            CaseKeepAliveScheduler.refreshInterval(PromptCacheTtl.ONE_HOUR) shouldBe Duration.ofSeconds(3570)
        }

        "a 5-minute cache is not pinged before 4:40 after the agent's request, and is at 4:40" {
            val fixture = Fixture()

            fixture.tickAt(fiveMinuteDue.minusSeconds(1)).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.pinged.single() shouldBe fixture.request
        }

        "a 1-hour cache is not pinged at 4:40 nor before 59:30, and is at 59:30" {
            val fixture = Fixture(ttl = PromptCacheTtl.ONE_HOUR)

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.tickAt(oneHourDue.minusSeconds(1)).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()

            fixture.tickAt(oneHourDue) shouldBe listOf(fixture.case.id)
        }

        "a successful ping touches the request, and the next one is due 4:40 after it" {
            val fixture = Fixture()

            fixture.tickAt(fiveMinuteDue)
            fixture.registry.get(fixture.case.id)!!.lastRefreshAt shouldBe start.plus(fiveMinuteDue)

            fixture.tickAt(fiveMinuteDue.plus(fiveMinuteDue).minusSeconds(1)).shouldBeEmpty()
            fixture.tickAt(fiveMinuteDue.plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
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

        "no ping once the maximum idle duration has passed since the agent's last request" {
            val fixture = Fixture(maxIdle = Duration.ofMinutes(9))

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            // Due again at 9:20, past the 9 minutes.
            fixture.tickAt(fiveMinuteDue.multipliedBy(2)).shouldBeEmpty()
            fixture.pinged.size shouldBe 1
        }

        "no ping when the keep-alive of the case is off" {
            val fixture = Fixture(case = idleCase().copy(keepAlive = false))

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()
        }

        "no ping while the case is not idle (a running turn refreshes the cache by itself)" {
            listOf(CaseStatus.RUNNING, CaseStatus.PENDING, CaseStatus.KILLED, CaseStatus.ERROR).forEach { status ->
                val fixture = Fixture(case = idleCase().copy(status = status))

                fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
                fixture.pinged.shouldBeEmpty()
            }
        }

        "no ping while the case is removed or missing" {
            val fixture = Fixture()
            fixture.storedCase = null

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()

            fixture.storedCase = fixture.case
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)) shouldBe listOf(fixture.case.id)
        }

        "no ping and no case read when no request is due or there is none" {
            val fixture = Fixture()
            fixture.tickAt(Duration.ofMinutes(1)).shouldBeEmpty()
            fixture.registry.remove(fixture.case.id)
            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()

            verify(exactly = 0) { fixture.caseService.findByIds(any(), any()) }
            fixture.pinged.shouldBeEmpty()
        }

        "no ping when the case is paused by the run cost guard or at its run cost threshold" {
            listOf(runCost(paused = true), runCost(cost = 2.0, threshold = 2.0), runCost(cost = 3.0, threshold = 2.0)).forEach { state ->
                val fixture = Fixture(runCost = state)

                fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
                fixture.pinged.shouldBeEmpty()
            }
        }

        "a ping under the run cost threshold is sent" {
            val fixture = Fixture(runCost = runCost(cost = 1.0, threshold = 2.0))

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
        }

        "no ping when the request was sent with a reasoning effort, but one with effort NONE is pinged" {
            val thinking = Fixture(requestModel = model.copy(reasoningEffort = ReasoningEffort.HIGH))
            thinking.tickAt(fiveMinuteDue).shouldBeEmpty()
            thinking.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()
            thinking.pinged.shouldBeEmpty()

            val none = Fixture(requestModel = model.copy(reasoningEffort = ReasoningEffort.NONE))
            none.tickAt(fiveMinuteDue) shouldBe listOf(none.case.id)
        }

        "no ping for a provider that is not OpenAI-compatible" {
            val anthropic = AiProvider(namespaceId = UUID.randomUUID(), name = "anthropic", apiType = AiApiType.Anthropic)
            val fixture = Fixture(provider = anthropic)

            fixture.tickAt(fiveMinuteDue).shouldBeEmpty()
            fixture.pinged.shouldBeEmpty()
        }

        "a failed ping does not touch the request and is retried at the next tick; a success resets the count" {
            val fixture = Fixture()
            fixture.failNext = 1

            fixture.tickAt(fiveMinuteDue)
            fixture.registry.get(fixture.case.id)!!.lastRefreshAt.shouldBeNull()
            fixture.pinged.shouldBeEmpty()

            fixture.tickAt(fiveMinuteDue.plusSeconds(20)) shouldBe listOf(fixture.case.id)
            fixture.pinged.size shouldBe 1

            // One more failure after a success is not the second in a row.
            fixture.failNext = 1
            val refreshed = fiveMinuteDue.plusSeconds(20)
            fixture.tickAt(refreshed.plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
            fixture.tickAt(refreshed.plus(fiveMinuteDue).plusSeconds(20)) shouldBe listOf(fixture.case.id)
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
            val next = fixture.request.copy(capturedAt = start.plus(Duration.ofMinutes(6)))
            fixture.registry.put(next)
            fixture.tickAt(Duration.ofMinutes(6).plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
            fixture.pinged.single() shouldBe next
        }

        "a case whose ping is still in flight is not pinged again" {
            val fixture = Fixture()
            val queued = CopyOnWriteArrayList<Runnable>()
            fixture.scheduler.executor = Executor { queued.add(it) }

            fixture.tickAt(fiveMinuteDue) shouldBe listOf(fixture.case.id)
            fixture.tickAt(fiveMinuteDue.plusSeconds(20)).shouldBeEmpty()

            queued.single().run()
            fixture.tickAt(fiveMinuteDue.plus(fiveMinuteDue)) shouldBe listOf(fixture.case.id)
        }

        "a ping that ends after a new turn replaced the request does not touch the new one" {
            val fixture = Fixture()
            val queued = CopyOnWriteArrayList<Runnable>()
            fixture.scheduler.executor = Executor { queued.add(it) }
            fixture.tickAt(fiveMinuteDue)

            val next = fixture.request.copy(capturedAt = start.plus(fiveMinuteDue).plusSeconds(5))
            fixture.registry.put(next)
            queued.single().run()

            fixture.registry.get(fixture.case.id)!!.lastRefreshAt.shouldBeNull()
        }
    })
