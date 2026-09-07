package io.whozoss.agentos.caseFlow

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * No-op implementation of [CaseReadService] used in tests and in-memory profiles.
 *
 * When no Neo4j engine is available (profile `test` with in-memory persistence),
 * [CaseReadServiceImpl] is not registered (its `@ConditionalOnExpression` does not
 * match). This bean fills the gap so that [CaseServiceImpl] and [CaseController]
 * can be instantiated without a Neo4j connection.
 *
 * The `@ConditionalOnMissingBean` ensures it is only registered when the real
 * [CaseReadServiceImpl] is absent.
 */
@Service
@Profile("test")
@ConditionalOnMissingBean(CaseReadService::class)
class NoOpCaseReadService : CaseReadService {
    override fun markRead(userId: String, caseId: UUID) = Unit

    override fun countUnread(userId: String, namespaceId: UUID): Long = 0L
}
