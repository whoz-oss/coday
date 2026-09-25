package io.whozoss.agentos.exchange

import io.whozoss.agentos.caseFlow.Case
import java.nio.file.Path
import java.util.UUID

/** Shared directory resolution for REST and agent tools, independent of its backing environment. */
interface ExchangeRootResolver {
    fun resolve(case: Case): ResolvedExchangeRoot

    fun resolve(caseId: UUID): ResolvedExchangeRoot

    fun resolveNamespaceRoot(namespaceId: UUID): Path

    /** Coordinate a mutation with preparation/cleanup, and resolve availability again inside it. */
    fun <T> withCaseMutation(caseId: UUID, action: (ResolvedExchangeRoot) -> T): T
}
