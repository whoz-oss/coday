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
 * Phases: `provisioning` (the [CaseWorkspaceWorker] sweep) or `all`. See [GitWorkspacesControl]
 * for when a pause takes effect.
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
            "sweeping" to activity.sweeping,
            "currentItem" to activity.currentItem,
            "currentSince" to activity.currentSince?.toString(),
        )
    }

    /**
     * - HTTP: `POST /management/gitworkspaces/{phase}` with body `{"action": "pause"}` or `{"action": "resume"}`
     * - JMX: `Endpoint.Gitworkspaces → control(phase, action)`
     *
     * @param phase `provisioning` or `all`
     * @param action `pause` or `resume`
     * @return the status after applying the action
     */
    @WriteOperation
    fun control(
        @Selector phase: String,
        action: String,
    ): Map<String, Any?> {
        if (phase !in PHASES) throw invalid("Invalid phase '$phase' (expected: ${PHASES.joinToString()})")
        when (action) {
            ACTION_PAUSE -> control.pauseProvisioning()
            ACTION_RESUME -> control.resumeProvisioning()
            else -> throw invalid("Invalid action '$action' (expected: $ACTION_PAUSE, $ACTION_RESUME)")
        }
        return status()
    }

    /** Actuator answers it with 400 over HTTP, rather than the 500 of an unhandled exception. */
    private fun invalid(message: String) = InvalidEndpointRequestException(message, message)

    private companion object {
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        val PHASES = listOf("provisioning", "all")
    }
}
