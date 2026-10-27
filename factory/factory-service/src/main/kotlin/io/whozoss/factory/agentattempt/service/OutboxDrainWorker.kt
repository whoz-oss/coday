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

/**
 * Background drain worker of the transactional outbox.
 *
 * Every [drainIntervalMs] it selects the organizations that have pending
 * `outbox_events`, drains them through [OutboxDrainService], and — when a
 * `result_submitted` event is dispatched — advances the destination session DAG
 * through [SessionRunService.runSession].
 *
 * Decoupling contract: the continuation is collected *inside* the drain
 * transaction but executed *after* it commits. A worker result submission is a
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
    fun drain() {
        val organizations = runCatching { drainService.pendingOrganizations() }
            .onFailure { logger.warn(it) { "Outbox worker could not list pending organizations" } }
            .getOrElse { return }

        val continuations = ArrayList<Continuation>()
        for (organizationId in organizations) {
            runCatching {
                drainService.drainPending(organizationId, limit = DRAIN_LIMIT) { event ->
                    parseContinuation(organizationId, event)?.let(continuations::add)
                }
            }.onFailure { logger.warn(it) { "Outbox drain failed for organization $organizationId" } }
        }

        for (continuation in continuations) {
            runCatching {
                sessionRunService.runSession(
                    continuation.scope,
                    continuation.namespaceId,
                    continuation.workflowId,
                    continuation.repoRoot,
                    continuation.ticket,
                )
            }.onFailure {
                logger.warn(it) {
                    "Sequencer continuation failed for workflow ${continuation.workflowId} " +
                        "in scope ${continuation.scope}"
                }
            }
        }
    }

    /** Maps a durable outbox event to its DAG continuation, or null for other event types. */
    private fun parseContinuation(organizationId: String, event: OutboxEvent): Continuation? = when (event.eventType) {
        RESULT_SUBMITTED -> parseResultSubmitted(organizationId, event)
        SESSION_RUN_REQUESTED -> parseSessionRunRequested(organizationId, event)
        else -> null
    }

    /** Maps a `result_submitted` event to its DAG continuation, or null for other event types. */
    private fun parseResultSubmitted(organizationId: String, event: OutboxEvent): Continuation? {
        if (event.eventType != RESULT_SUBMITTED) return null
        val payload = parsePayload(event) ?: return null
        val namespaceId = payload.path("namespaceId").asText().takeIf { it.isNotBlank() } ?: return null
        val workflowId = payload.path("workflowId").asText().takeIf { it.isNotBlank() } ?: return null
        val workstreamId = event.workstreamId.takeIf { it.isNotBlank() } ?: return null
        return Continuation(
            scope = TenantScope(organizationId, workstreamId),
            namespaceId = namespaceId,
            workflowId = workflowId,
            repoRoot = resolveRepoRoot(namespaceId),
        )
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
        val repoRoot = payload.path("repoRoot").asText().takeIf { it.isNotBlank() }
            ?: return null
        val ticket = payload.path("ticket").asText().takeIf { it.isNotBlank() }
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
     * resolved from the AgentOS namespace `configPath`; an AgentOS outage degrades
     * to the process working directory rather than dropping the continuation.
     */
    private fun resolveRepoRoot(namespaceId: String): Path =
        runCatching { agentOsProxyClient.resolveRepoRoot(namespaceId, null) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: Path.of(".")

    private companion object {
        const val RESULT_SUBMITTED = "result_submitted"
        const val SESSION_RUN_REQUESTED = SessionRunSubmissionService.SESSION_RUN_REQUESTED
        const val DRAIN_LIMIT = 50
    }
}
