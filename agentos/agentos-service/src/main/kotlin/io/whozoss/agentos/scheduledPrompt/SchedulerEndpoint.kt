package io.whozoss.agentos.scheduledPrompt

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Actuator endpoint for operational control of the scheduler.
 *
 * Exposed both as an HTTP endpoint and as a JMX MBean by Spring Boot Actuator:
 * - **HTTP**: `GET /management/scheduler` (read), `POST /management/scheduler/{phase}` (write)
 * - **JMX**: MBean `org.springframework.boot:type=Endpoint,name=Scheduler`
 *
 * Spring Boot Admin discovers this endpoint automatically via Actuator discovery
 * and can invoke it via Jolokia (HTTP→JMX bridge).
 *
 * ### Operations
 *
 * **Read** — returns the current paused/active state of each scheduler phase.
 *
 * **Write** — pause or resume a phase:
 * - `phase`: `"claim"`, `"consume"`, or `"all"`
 * - `action`: `"pause"` or `"resume"`
 *
 * The `claim` phase is managed by [SchedulerScanner]: pausing it causes the
 * `@Scheduled` tick to return immediately without claiming new runs.
 *
 * The `consume` phase is managed by [ScheduledPromptExecutor]: pausing it causes the
 * producer loop to skip `claimBatch` calls and delay instead. Workers continue
 * to drain items already in the channel, then block on receive until resumed.
 */
@Component
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
@Endpoint(id = "scheduler")
class SchedulerEndpoint(
    private val schedulerScanner: SchedulerScanner,
    private val executor: ScheduledPromptExecutor,
) {
    /**
     * Read current scheduler status.
     *
     * - HTTP:  `GET /management/scheduler`
     * - JMX:   `Endpoint.Scheduler → status()`
     */
    @ReadOperation
    fun status(): Map<String, Any> =
        mapOf(
            "claimPaused" to schedulerScanner.isClaimPaused(),
            "consumePaused" to executor.isConsumePaused(),
        )

    /**
     * Pause or resume a scheduler phase.
     *
     * - HTTP:  `POST /management/scheduler/{claimOrConsumeOrAllPhase}` with body `{"pauseOrResumeAction": "pause"}` or `{"pauseOrResumeAction": "resume"}`
     * - JMX:   `Endpoint.Scheduler → control(claimOrConsumeOrAllPhase, pauseOrResumeAction)`
     *
     * @param claimOrConsumeOrAllPhase  `"claim"`, `"consume"`, or `"all"`
     * @param pauseOrResumeAction `"pause"` or `"resume"`
     * @return updated status after applying the action
     */
    @WriteOperation
    fun control(
        @Selector claimOrConsumeOrAllPhase: String,
        pauseOrResumeAction: String,
    ): Map<String, Any> {
        val phases =
            when (claimOrConsumeOrAllPhase) {
                "all" -> listOf("claim", "consume")
                "claim" -> listOf("claim")
                "consume" -> listOf("consume")
                else -> throw IllegalArgumentException(
                    "Invalid phase '$claimOrConsumeOrAllPhase' (expected: claim, consume, all)",
                )
            }
        val apply: (String) -> Unit =
            when (pauseOrResumeAction) {
                "pause" -> { p ->
                    when (p) {
                        "claim" -> schedulerScanner.pauseClaim()
                        else -> executor.pauseConsume()
                    }
                }
                "resume" -> { p ->
                    when (p) {
                        "claim" -> schedulerScanner.resumeClaim()
                        else -> executor.resumeConsume()
                    }
                }
                else -> throw IllegalArgumentException(
                    "Invalid action '$pauseOrResumeAction' (expected: pause, resume)",
                )
            }
        phases.forEach(apply)
        return status()
    }
}
