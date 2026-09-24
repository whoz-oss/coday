package io.whozoss.agentos.sdk.spi

import org.pf4j.ExtensionPoint
import java.util.UUID

/**
 * Generic SPI extension point that supplies optional external execution context data
 * to a case run or session.
 *
 * The returned map is merged into the per-message `sessionContext` before the message
 * is persisted, giving external providers a way to enrich a case with opaque
 * application-level context (e.g. metadata, environment or session variables) without
 * the core runtime knowing where that data comes from.
 *
 * ### Safe default
 *
 * [provideExecutionContext] defaults to an empty map, so the contract is a pure no-op
 * when an implementation has no data to contribute, and existing behavior is preserved
 * when no provider is registered.
 *
 * ### Exception handling
 *
 * Unexpected exceptions thrown by a provider are caught and logged by the caller; the
 * failing provider simply contributes no data.
 */
interface ExternalExecutionContextProvider : ExtensionPoint {
    /**
     * Provide external execution context entries for the given case run.
     *
     * @param caseId the case the context is being resolved for.
     * @param namespaceId the namespace the case belongs to.
     * @param userId the internal user id driving the run, or null when unresolved.
     * @return context entries to merge into the message session context; empty when the
     *   provider has nothing to contribute.
     */
    fun provideExecutionContext(
        caseId: UUID,
        namespaceId: UUID,
        userId: UUID? = null,
    ): Map<String, Any?> = emptyMap()
}
