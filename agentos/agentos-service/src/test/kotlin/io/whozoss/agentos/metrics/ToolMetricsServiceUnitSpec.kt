package io.whozoss.agentos.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.UUID

class ToolMetricsServiceUnitSpec :
    StringSpec({

        fun registry() = SimpleMeterRegistry()

        // -------------------------------------------------------------------------
        // startTimer / stopTimer
        // -------------------------------------------------------------------------

        "stopTimer records timer with success tag" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)
            val namespaceId = UUID.randomUUID()

            val sample = service.startTimer()
            service.stopTimerAndSendMetrics(
                sample = sample,
                toolName = "FILES__read",
                agentName = "my-agent",
                namespaceId = namespaceId,
                success = true,
            )

            val timer =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_TOOL_NAME, "FILES__read")
                    .tag(ToolMetricsService.TAG_INTEGRATION_TYPE, "FILES")
                    .tag(ToolMetricsService.TAG_AGENT_NAME, "my-agent")
                    .tag(ToolMetricsService.TAG_NAMESPACE_ID, namespaceId.toString())
                    .tag(ToolMetricsService.TAG_STATUS, ToolMetricsService.STATUS_SUCCESS)
                    .timer()

            timer.shouldNotBeNull()
            timer.count() shouldBe 1L
        }

        "stopTimer records timer with failure tag" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)
            val namespaceId = UUID.randomUUID()

            val sample = service.startTimer()
            service.stopTimerAndSendMetrics(
                sample = sample,
                toolName = "JIRA__getIssue",
                agentName = "jira-agent",
                namespaceId = namespaceId,
                success = false,
            )

            val timer =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_STATUS, ToolMetricsService.STATUS_FAILURE)
                    .timer()

            timer?.count() shouldBe 1L
        }

        "stopTimer returns elapsed milliseconds (non-negative)" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            val sample = service.startTimer()
            val elapsedMs =
                service.stopTimerAndSendMetrics(
                    sample = sample,
                    toolName = "FILES__read",
                    agentName = "agent",
                    namespaceId = UUID.randomUUID(),
                    success = true,
                )

            elapsedMs shouldBeGreaterThanOrEqualTo 0L
        }

        "stopTimer with null sample returns 0" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            val elapsedMs =
                service.stopTimerAndSendMetrics(
                    sample = null,
                    toolName = "FILES__read",
                    agentName = "agent",
                    namespaceId = UUID.randomUUID(),
                    success = true,
                )

            elapsedMs shouldBe 0L
        }

        "stopTimer uses 'unknown' integration type for tools without __ separator" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            val sample = service.startTimer()
            service.stopTimerAndSendMetrics(
                sample = sample,
                toolName = "redirect",
                agentName = "agent",
                namespaceId = UUID.randomUUID(),
                success = true,
            )

            val timer =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_INTEGRATION_TYPE, ToolMetricsService.INTEGRATION_TYPE_UNKNOWN)
                    .timer()

            timer?.count() shouldBe 1L
        }

        "stopTimer accumulates multiple calls" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)
            val namespaceId = UUID.randomUUID()

            repeat(3) {
                service.stopTimerAndSendMetrics(
                    service.startTimer(),
                    "FILES__read",
                    "agent",
                    namespaceId,
                    success = true,
                )
            }

            val timer =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_TOOL_NAME, "FILES__read")
                    .tag(ToolMetricsService.TAG_STATUS, ToolMetricsService.STATUS_SUCCESS)
                    .timer()

            timer?.count() shouldBe 3L
        }

        // -------------------------------------------------------------------------
        // recordParameterGenerationFailure
        // -------------------------------------------------------------------------

        "recordParameterGenerationFailure increments the dedicated counter" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)
            val namespaceId = UUID.randomUUID()

            service.recordParameterGenerationFailure(
                toolName = "FILES__read",
                agentName = "agent",
                namespaceId = namespaceId,
            )

            val counter =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_PARAM_FAILURES)
                    .tag(ToolMetricsService.TAG_TOOL_NAME, "FILES__read")
                    .tag(ToolMetricsService.TAG_AGENT_NAME, "agent")
                    .tag(ToolMetricsService.TAG_NAMESPACE_ID, namespaceId.toString())
                    .counter()

            counter?.count() shouldBe 1.0
        }

        // -------------------------------------------------------------------------
        // recordConfirmation
        // -------------------------------------------------------------------------

        "recordConfirmation increments counter with APPLIED outcome" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            service.recordConfirmation(
                toolName = "FILES__remove",
                agentName = "agent",
                namespaceId = UUID.randomUUID(),
                outcome = "applied",
            )

            val counter =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CONFIRMATION_TOTAL)
                    .tag(ToolMetricsService.TAG_OUTCOME, "applied")
                    .counter()

            counter?.count() shouldBe 1.0
        }

        "recordConfirmation increments counter with REJECTED outcome" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            service.recordConfirmation(
                toolName = "FILES__remove",
                agentName = "agent",
                namespaceId = UUID.randomUUID(),
                outcome = "rejected",
            )

            val counter =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CONFIRMATION_TOTAL)
                    .tag(ToolMetricsService.TAG_OUTCOME, "rejected")
                    .counter()

            counter?.count() shouldBe 1.0
        }

        "recordConfirmation increments counter with ABORTED outcome" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)

            service.recordConfirmation(
                toolName = "FILES__remove",
                agentName = "agent",
                namespaceId = UUID.randomUUID(),
                outcome = "aborted",
            )

            val counter =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CONFIRMATION_TOTAL)
                    .tag(ToolMetricsService.TAG_OUTCOME, "aborted")
                    .counter()

            counter?.count() shouldBe 1.0
        }

        "different namespaces produce distinct timer series" {
            val meterRegistry = registry()
            val service = ToolMetricsService(meterRegistry)
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()

            service.stopTimerAndSendMetrics(service.startTimer(), "FILES__read", "agent", ns1, success = true)
            service.stopTimerAndSendMetrics(service.startTimer(), "FILES__read", "agent", ns2, success = true)
            service.stopTimerAndSendMetrics(service.startTimer(), "FILES__read", "agent", ns1, success = true)

            val timerNs1 =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_NAMESPACE_ID, ns1.toString())
                    .timer()
            val timerNs2 =
                meterRegistry
                    .find(ToolMetricsService.METRIC_TOOL_CALLS_DURATION)
                    .tag(ToolMetricsService.TAG_NAMESPACE_ID, ns2.toString())
                    .timer()

            timerNs1?.count() shouldBe 2L
            timerNs2?.count() shouldBe 1L
        }
    })
