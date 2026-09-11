package io.whozoss.agentos.caseFlow

import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseReadService].
 *
 * Always registered: every supported persistence mode (`embedded-neo4j`, `neo4j`) runs on a
 * Neo4j engine, so a Driver bean is always provisioned.
 */
@Service
class CaseReadServiceImpl(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
    private val clock: Clock,
) : CaseReadService {
    companion object : KLogging()

    override fun markRead(
        userId: String,
        caseId: UUID,
    ) {
        val now = Instant.now(clock)
        caseNodeNeo4jRepository.markRead(
            userId = userId,
            caseId = caseId.toString(),
            readAt = now,
        )
        logger.debug { "markRead: user=$userId case=$caseId at=$now" }
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
