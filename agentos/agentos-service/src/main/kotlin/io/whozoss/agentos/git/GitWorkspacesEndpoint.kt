package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component

/**
 * Actuator endpoint to pause and resume the Git background work on a live instance.
 *
 * Same model as the prompt scheduler's endpoint, exposed both ways by Spring Boot Actuator:
 * - **HTTP**: `GET /management/gitworkspaces` (read), `POST /management/gitworkspaces/{phase}`
 *   with body `{"action": "pause"}` or `{"action": "resume"}` (write)
 * - **JMX**: MBean `org.springframework.boot:type=Endpoint,name=Gitworkspaces`
 *
 * Phases: `provisioning` (the [CaseWorkspaceWorker] sweep), `monitor` (the [GitWorkspaceMonitor]
 * status polling) or `all`. See [GitWorkspacesControl] for when a pause takes effect.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.worker", name = ["enabled"], havingValue = "true")
@Endpoint(id = "gitworkspaces")
class GitWorkspacesEndpoint(
    private val control: GitWorkspacesControl,
    private val worker: CaseWorkspaceWorker,
) {
    /**
     * - HTTP: `GET /management/gitworkspaces`
     * - JMX: `Endpoint.Gitworkspaces → status()`
     */
    @ReadOperation
    fun status(): Map<String, Any?> {
        val activity = worker.activity()
        return mapOf(
            "provisioningPaused" to control.isProvisioningPaused(),
            "monitorPaused" to control.isMonitorPaused(),
            "sweeping" to activity.sweeping,
            "currentItem" to activity.currentItem,
            "currentSince" to activity.currentSince?.toString(),
        )
    }

    /**
     * - HTTP: `POST /management/gitworkspaces/{phase}` with body `{"action": "pause"}` or `{"action": "resume"}`
     * - JMX: `Endpoint.Gitworkspaces → control(phase, action)`
     *
     * @param phase `provisioning`, `monitor` or `all`
     * @param action `pause` or `resume`
     * @return the status after applying the action
     */
    @WriteOperation
    fun control(
        @Selector phase: String,
        action: String,
    ): Map<String, Any?> {
        val phases =
            when (phase) {
                PHASE_ALL -> listOf(PHASE_PROVISIONING, PHASE_MONITOR)
                PHASE_PROVISIONING, PHASE_MONITOR -> listOf(phase)
                else -> throw invalid("Invalid phase '$phase' (expected: $PHASE_PROVISIONING, $PHASE_MONITOR, $PHASE_ALL)")
            }
        val pause =
            when (action) {
                ACTION_PAUSE -> true
                ACTION_RESUME -> false
                else -> throw invalid("Invalid action '$action' (expected: $ACTION_PAUSE, $ACTION_RESUME)")
            }
        phases.forEach {
            when {
                it == PHASE_PROVISIONING && pause -> control.pauseProvisioning()
                it == PHASE_PROVISIONING -> control.resumeProvisioning()
                pause -> control.pauseMonitor()
                else -> control.resumeMonitor()
            }
        }
        return status()
    }

    /** Actuator answers it with 400 over HTTP, rather than the 500 of an unhandled exception. */
    private fun invalid(message: String) = InvalidEndpointRequestException(message, message)

    private companion object {
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val PHASE_PROVISIONING = "provisioning"
        const val PHASE_MONITOR = "monitor"
        const val PHASE_ALL = "all"
    }
}
