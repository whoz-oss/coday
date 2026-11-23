package io.whozoss.factory.adapter.agentos

import io.whozoss.factory.agentattempt.domain.ResolvedModel
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jDurableAgentAttemptRepository
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Execution-bound metadata only: no configuration lookup, no arbitrary raw event persistence.
 *
 * Every access is tenant-scoped: attempt and case ids are derived from workflow/step ids, so the same pair
 * can exist in another tenant. An unscoped lookup would either read another tenant's attempt or, when two
 * match, silently drop this tenant's attribution.
 */
@Service
class AttemptModelObserver(private val attempts: SpringDataNeo4jDurableAgentAttemptRepository) {
    private fun attempt(scope: TenantScope, namespaceId: String?, caseId: String, attemptId: String) =
        attempts.findExecutionMetadata(scope.organizationId, scope.workstreamId, namespaceId, caseId, attemptId)
            .singleOrNull()

    /** Must land before dispatch. Failure aborts dispatch rather than losing retry attribution. */
    @org.springframework.transaction.annotation.Transactional
    fun captureBaseline(
        scope: TenantScope,
        namespaceId: String?,
        caseId: String,
        attemptId: String,
        baseline: HighWaterMark,
    ) {
        val attempt = attempt(scope, namespaceId, caseId, attemptId)
            ?: error("No unique durable attempt for model attribution")
        attempts.captureModelBaseline(attempt.id, baseline.timestamp, baseline.lastEventId)
    }

    fun baseline(scope: TenantScope, namespaceId: String?, caseId: String, attemptId: String): HighWaterMark? =
        attempt(scope, namespaceId, caseId, attemptId)
            ?.takeIf { it.modelBaselineCaptured }
            ?.let { HighWaterMark(it.modelBaselineTimestamp, it.modelBaselineEventId) }

    fun observe(
        scope: TenantScope,
        namespaceId: String?,
        caseId: String,
        attemptId: String,
        events: List<CaseEventView>,
    ) {
        if (events.none { it.type == "AgentRunningEvent" || it.type == "AgentFinishedEvent" }) return
        // Metadata outages must not change the execution verdict. Durable history permits a later retry.
        runCatching { observeDurable(scope, namespaceId, caseId, attemptId, events) }
    }

    private fun observeDurable(
        scope: TenantScope,
        namespaceId: String?,
        caseId: String,
        attemptId: String,
        events: List<CaseEventView>,
    ) {
        val attempt = attempt(scope, namespaceId, caseId, attemptId) ?: return
        if (attempt.resolvedProvider != null || !attempt.modelBaselineCaptured) return
        val end = attempt.completedAt
        val model = resolveAttemptModel(
            caseId, attempt.agentName,
            HighWaterMark(attempt.modelBaselineTimestamp, attempt.modelBaselineEventId),
            events.filter { event -> end == null || isNotAfter(event.timestamp, end) },
        ) ?: return
        attempts.captureResolvedModel(attempt.id, model.provider, model.model)
    }
}

/** Chronological `timestamp <= end`; an absent or unparseable timestamp cannot be attributed to the attempt. */
private fun isNotAfter(timestamp: String?, end: Instant): Boolean =
    timestamp?.let { runCatching { Instant.parse(it) <= end }.getOrDefault(false) } == true

/** First actual assigned-agent execution strictly after the durable pre-dispatch checkpoint. */
internal fun resolveAttemptModel(
    caseId: String,
    agentName: String,
    baseline: HighWaterMark,
    events: List<CaseEventView>,
): ResolvedModel? = events.asSequence()
    .filter { it.caseId == caseId && it.timestamp != null && !EventCheckpoint.covers(baseline, it) }
    .filter { it.type in setOf("AgentRunningEvent", "AgentFinishedEvent") && it.raw["agentName"] == agentName }
    // Chronological, not lexicographic: `...00Z` and `...00.5Z` do not sort correctly as text.
    .sortedWith { a, b ->
        EventCheckpoint.compareTimestamps(a.timestamp!!, b.timestamp!!).takeIf { it != 0 } ?: a.eventId.compareTo(b.eventId)
    }
    .mapNotNull { event ->
        // Identifiers only, not endpoints/credentials or arbitrary event payloads.
        val provider = (event.raw["llmProvider"] as? String)?.takeIf(::safeModelIdentifier)
        val model = (event.raw["llmModel"] as? String)?.takeIf(::safeModelIdentifier)
        if (provider != null && model != null) ResolvedModel(provider, model) else null
    }.firstOrNull()

private fun safeModelIdentifier(value: String): Boolean =
    value.length in 1..255 && Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*").matches(value) &&
        !value.contains("://") && !value.startsWith("sk-")
