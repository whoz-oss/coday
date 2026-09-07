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

    override fun markRead(
        userId: String,
        caseId: UUID,
        at: Instant?,
    ) {
        // Clamp future timestamps to now so clients cannot set readAt ahead of current time.
        val now = Instant.now(clock)
        val effective = if (at != null && at.isBefore(now)) at else now
        caseNodeNeo4jRepository.markRead(
            userId = userId,
            caseId = caseId.toString(),
            readAt = effective,
        )
        logger.debug { "markRead: user=$userId case=$caseId at=$effective" }
    }

    override fun countUnread(
        userId: String,
        namespaceId: UUID,
    ): Long =
        caseNodeNeo4jRepository.countUnread(
            userId = userId,
            namespaceId = namespaceId.toString(),
        )
}
