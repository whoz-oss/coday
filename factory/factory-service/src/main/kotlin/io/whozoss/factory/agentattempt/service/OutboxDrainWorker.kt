package io.whozoss.factory.agentattempt.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.Clock
import io.whozoss.factory.workspace.TransientWorkspaceAdoptionException

/**
 * Background drain worker of the transactional outbox.
 *
 * At each configured drain interval it selects the organizations that have pending
 * `outbox_events`, drains them through [OutboxDrainService], and — when a
 * `result_submitted` event is dispatched — advances the destination session DAG
 * through [SessionRunService.runSession].
 *
 * Decoupling contract: a deterministic continuation event is persisted *inside*
 * the source drain transaction and executed *after* it commits. A crash before
 * acknowledgement replays only the sequencer's durable claims, never a reset
 * of an uncertain RUNNING agent. Only transient workspace adoption (including
 * legacy result-context resolution) is deferred with bounded durable backoff
 * (90 minutes FROM FIRST transient failure, not normal enqueue/downtime).
 * Standalone adoption is a single readiness probe; parent Run polling is unchanged.
 * The existing fixed-delay scheduler still executes full sequencer/agent turns: this
 * patch does not introduce an executor or make agent execution non-blocking.
 * A worker result submission is a
 * short HTTP transaction that only enqueues an event; the (potentially long)
 * sequencer continuation — which may itself run an agent turn — never holds the
 * outbox row lock or the submitting request open.
 *
 * The worker is only registered when `factory.outbox.drain-enabled` is `true`,
 * so the shared integration-test context (which disables it) is never disturbed
 * by background drains.
 */
@Component
@ConditionalOnProperty(prefix = "factory.outbox", name = ["drain-enabled"], havingValue = "true")
class OutboxDrainWorker(
    private val drainService: OutboxDrainService,
    private val sessionRunService: SessionRunService,
    private val agentOsProxyClient: AgentOsProxyClient,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = KotlinLogging.logger {}

    /** A pending DAG continuation resolved from a `result_submitted` event. */
    private data class Continuation(
        val scope: TenantScope,
        val namespaceId: String,
        val workflowId: String,
        val repoRoot: Path,
        val ticket: String? = null,
    )

    @Scheduled(
        fixedDelayString = "\${factory.outbox.drain-interval-ms:5000}",
        initialDelayString = "\${factory.outbox.drain-initial-delay-ms:10000}",
    )
    @Synchronized
    fun drain() {
        if (Thread.currentThread().isInterrupted) return
        val organizations = runCatching { drainService.pendingOrganizations() }
            .onFailure { logger.warn(it) { "Outbox worker could not list pending organizations" } }
            .getOrElse { return }

        for (organizationId in organizations) {
            runCatching {
                drainService.drainPending(organizationId, limit = DRAIN_LIMIT) { event ->
                    continuationPayload(organizationId, event)?.let { payload ->
                        drainService.enqueueContinuation(organizationId, event, payload, clock.instant())
                    }
                }
            }.onFailure { logger.warn(it) { "Outbox drain failed for organization $organizationId" } }
        }

        for (organizationId in organizations) {
            val due = runCatching { drainService.dueContinuations(organizationId, clock.instant(), DRAIN_LIMIT) }
                .onFailure { logger.warn(it) { "Could not read durable continuations" } }.getOrElse { continue }
            for (candidate in due) {
                if (Thread.currentThread().isInterrupted) return
                val event = drainService.claimContinuation(organizationId, candidate.id, clock.instant()) ?: continue
                val token = requireNotNull(event.claimToken)
                try {
                    // The deadline bounds failed adoption probes, not replay of committed
                    // DAG results/parent notification after normal queue downtime.
                    val continuation = requireNotNull(parseSessionRunRequested(organizationId, event))
                    sessionRunService.runSession(
                        continuation.scope, continuation.namespaceId, continuation.workflowId,
                        continuation.repoRoot, continuation.ticket,
                    )
                    drainService.completeContinuation(organizationId, event.id, clock.instant(), token)
                } catch (error: Exception) {
                    // Interruptions (including HTTP wrappers) are shutdown, not adoption failures.
                    if (Thread.currentThread().isInterrupted || generateSequence<Throwable>(error) { it.cause }
                            .any { it is InterruptedException }) {
                        // Clear the flag while the driver performs the short fenced release.
                        // A failed release still falls back to crash lease recovery.
                        Thread.interrupted()
                        try {
                            drainService.releaseContinuation(organizationId, event.id, token)
                        } finally {
                            Thread.currentThread().interrupt()
                        }
                        return
                    }
                    drainService.deferContinuation(organizationId, event.id, clock.instant(),
                        error is TransientWorkspaceAdoptionException, token)
                    logger.warn(error) { "Continuation ${event.id}: ${if (error is TransientWorkspaceAdoptionException)
                        "workspace adoption deferred (bounded durable backoff)" else "failed without automatic retry"}" }
                }
            }
        }
    }

    /** DB/JSON only: handoff cannot depend on AgentOS availability. */
    private fun continuationPayload(organizationId: String, event: OutboxEvent): String? {
        if (event.eventType !in setOf(RESULT_SUBMITTED, SESSION_RUN_REQUESTED)) return null
        val payload = parsePayload(event) ?: return null
        val namespaceId = payload.path("namespaceId").asText().takeIf { it.isNotBlank() } ?: return null
        val workflowId = payload.path("workflowId").asText().takeIf { it.isNotBlank() } ?: return null
        if (event.workstreamId.isBlank()) return null
        if (event.eventType == SESSION_RUN_REQUESTED) return event.payload
        return drainService.submissionContext(organizationId, event.workstreamId, namespaceId, workflowId)
            ?: objectMapper.writeValueAsString(mapOf("namespaceId" to namespaceId, "workflowId" to workflowId,
                "repoRootSource" to "agentos-namespace"))
    }

    /**
     * Maps a `session_run_requested` event (durable HTTP `/run` submission) to its
     * continuation. The repo root and ticket travel in the payload, so the drained
     * run resumes exactly the submission the caller made.
     */
    private fun parseSessionRunRequested(organizationId: String, event: OutboxEvent): Continuation? {
        val payload = parsePayload(event) ?: return null
        val namespaceId = payload.path("namespaceId").asText().takeIf { it.isNotBlank() } ?: return null
        val workflowId = payload.path("workflowId").asText().takeIf { it.isNotBlank() } ?: return null
        val workstreamId = event.workstreamId.takeIf { it.isNotBlank() } ?: return null
        val repoRoot = payload.path("repoRoot").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            ?: if (payload.path("repoRootSource").asText() == "agentos-namespace") resolveRepoRoot(namespaceId).toString()
            else return null
        val ticket = payload.path("ticket").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
        return Continuation(
            scope = TenantScope(organizationId, workstreamId),
            namespaceId = namespaceId,
            workflowId = workflowId,
            repoRoot = Path.of(repoRoot),
            ticket = ticket,
        )
    }

    private fun parsePayload(event: OutboxEvent) = runCatching { objectMapper.readTree(event.payload) }
        .onFailure { logger.warn(it) { "Outbox event ${event.id} carries an invalid payload" } }
        .getOrNull()

    /**
     * The session repo root is used to resolve target-repo `factory/verification.json`
     * manifests for any `code` step that becomes ready after the agent step. It is
     * resolved outside the handoff transaction only for legacy results with no persisted
     * submission context. Recognized transport failures receive bounded durable backoff.
     */
    private fun resolveRepoRoot(namespaceId: String): Path {
        try {
            val root = agentOsProxyClient.resolveRepoRoot(namespaceId, null)?.takeIf { it.isNotBlank() }
                ?: throw io.whozoss.factory.workspace.WorkspaceAdoptionUnavailableException("Namespace repoRoot unavailable")
            return Path.of(root)
        } catch (error: Exception) {
            if (error is io.whozoss.factory.workspace.WorkspaceAdoptionUnavailableException ||
                io.whozoss.factory.workspace.isTemporaryWorkspaceTransportFailure(error)) {
                throw TransientWorkspaceAdoptionException(error)
            }
            throw error
        }
    }

    private companion object {
        const val RESULT_SUBMITTED = "result_submitted"
        const val SESSION_RUN_REQUESTED = SessionRunSubmissionService.SESSION_RUN_REQUESTED
        const val DRAIN_LIMIT = 50
    }
}
