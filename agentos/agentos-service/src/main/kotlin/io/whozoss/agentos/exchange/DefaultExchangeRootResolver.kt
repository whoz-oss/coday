package io.whozoss.agentos.exchange

import io.whozoss.agentos.caseFlow.Case
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Every case owns its date-sharded Exchange directory, with no database access. A provider that
 * shares a directory between cases replaces this resolver with a primary bean.
 */
@Component
class DefaultExchangeRootResolver(
    private val exchangeStorageService: ExchangeStorageService,
) : ExchangeRootResolver {
    override fun resolve(case: Case): ResolvedExchangeRoot = resolve(case.id, case.namespaceId, case.metadata.created)

    override fun resolve(
        caseId: UUID,
        namespaceId: UUID,
        caseCreatedAt: Instant,
    ): ResolvedExchangeRoot = ResolvedExchangeRoot(exchangeStorageService.caseRoot(namespaceId, caseId, caseCreatedAt), caseId)

    override fun <T> withCaseMutation(
        case: Case,
        action: (ResolvedExchangeRoot) -> T,
    ): T = action(resolve(case))
}
