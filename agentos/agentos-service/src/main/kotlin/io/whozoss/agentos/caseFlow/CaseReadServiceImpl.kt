package io.whozoss.agentos.caseFlow

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseReadService].
 *
 * Active only when a Neo4j engine is configured (same condition as
 * [io.whozoss.agentos.config.Neo4jPersistenceConfiguration]).
 */
@Service
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class CaseReadServiceImpl(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
    private val clock: Clock,
) : CaseReadService {
    companion object : KLogging()

    override fun markRead(userId: String, caseId: UUID) {
        val now = Instant.now(clock)
        caseNodeNeo4jRepository.markRead(
            userId = userId,
            caseId = caseId.toString(),
            readAt = now,
        )
        logger.debug { "markRead: user=$userId case=$caseId at=$now" }
    }

    override fun countUnread(userId: String, namespaceId: UUID): Long =
        caseNodeNeo4jRepository.countUnread(
            userId = userId,
            namespaceId = namespaceId.toString(),
        )
}
