package io.whozoss.agentos.exchange

import io.whozoss.agentos.caseFlow.Case
import java.time.Instant
import java.util.UUID

/**
 * Where a case's Exchange files live, for the REST API and the agent tools alike.
 *
 * Without an optional environment, [DefaultExchangeRootResolver] returns each case's own
 * date-sharded directory, exactly as before this contract existed. An environment that shares a
 * directory between cases (a Git workspace, for a case family) provides its own implementation.
 */
interface ExchangeRootResolver {
    fun resolve(case: Case): ResolvedExchangeRoot

    /** For callers that hold the case's identity but not the entity, such as an agent run. */
    fun resolve(
        caseId: UUID,
        namespaceId: UUID,
        caseCreatedAt: Instant,
    ): ResolvedExchangeRoot

    /**
     * Run a file mutation on [case]'s Exchange. An implementation whose directory can be
     * prepared or removed in the background coordinates with that work and resolves again inside.
     */
    fun <T> withCaseMutation(
        case: Case,
        action: (ResolvedExchangeRoot) -> T,
    ): T
}
