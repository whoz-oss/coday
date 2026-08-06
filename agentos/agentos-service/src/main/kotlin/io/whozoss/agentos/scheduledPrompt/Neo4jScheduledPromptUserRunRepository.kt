package io.whozoss.agentos.scheduledPrompt

import mu.KLogging
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [ScheduledPromptUserRunRepository].
 *
 * All Cypher queries live in [ScheduledPromptUserRunNodeNeo4jRepository]; this class
 * handles Domain ↔ Node mapping and coordination (lease computation, optimistic lock
 * via SDN save).
 *
 * ### materialize
 *
 * Delegates to a single Cypher INSERT-SELECT `@Query` that traverses the deployment
 * graph (AgentConfig -[:DEPLOYED_TO]-> UserGroup <-[:MEMBER|ADMIN]- User) and MERGEs one
 * PENDING [ScheduledPromptUserRunNode] per distinct target user in a single transaction.
 *
 * ### claimBatch
 *
 * Reads up to `limit` claimable candidates via [ScheduledPromptUserRunNodeNeo4jRepository.findClaimable],
 * then saves each with SDN's optimistic lock. "Claimable" means PENDING, or RUNNING with an
 * expired lease (crash recovery). Concurrent instances that race on the same node will get an
 * [OptimisticLockingFailureException] and skip that entry.
 *
 * ### markTerminal
 *
 * Loads the node, mutates the relevant fields, and saves via SDN. The [ScheduledPromptUserRunNode.version]
 * field triggers SDN's optimistic-locking check; callers must handle
 * [OptimisticLockingFailureException] when two instances race.
 */
open class Neo4jScheduledPromptUserRunRepository(
    private val neo4jRepository: ScheduledPromptUserRunNodeNeo4jRepository,
) : ScheduledPromptUserRunRepository {

    override fun materialize(runId: UUID, agentConfigId: UUID, namespaceId: UUID): Int =
        neo4jRepository.materialize(
            runId = runId.toString(),
            agentConfigId = agentConfigId.toString(),
            namespaceId = namespaceId.toString(),
        ).also { count ->
            logger.info {
                "[Neo4jScheduledPromptUserRunRepository] materialize runId=$runId " +
                    "agentConfigId=$agentConfigId namespaceId=$namespaceId \u2192 $count UserRun(s) created"
            }
        }

    override fun claimBatch(leaseDuration: Duration, limit: Int): List<ScheduledPromptUserRun> {
        val now = Instant.now()
        val leaseUntil = now.plus(leaseDuration)
        val candidates = neo4jRepository.findClaimable(now, limit)
        return candidates.mapNotNull { node ->
            try {
                val claimed = node.copy(
                    status = UserRunStatus.RUNNING.name,
                    leaseUntil = leaseUntil,
                )
                neo4jRepository.save(claimed).toDomain()
            } catch (e: OptimisticLockingFailureException) {
                logger.debug {
                    "[Neo4jScheduledPromptUserRunRepository] Optimistic lock conflict on UserRun=${node.id}" +
                        " — skipped (another instance claimed it)"
                }
                null
            }
        }
    }

    @Transactional
    override fun markTerminal(
        id: UUID,
        status: UserRunStatus,
        now: Instant,
        error: String?,
    ): ScheduledPromptUserRun {
        val node = neo4jRepository.findById(id.toString())
            .orElseThrow { NoSuchElementException("ScheduledPromptUserRun not found: $id") }
        val updated = node.copy(
            status = status.name,
            finishedAt = now,
            error = error,
            leaseUntil = null,
        )
        return neo4jRepository.save(updated).toDomain()
    }

    override fun findByRunId(runId: UUID): List<ScheduledPromptUserRun> =
        neo4jRepository.findByRunId(runId.toString()).map { it.toDomain() }

    override fun countByRunIdAndStatus(runId: UUID, vararg statuses: UserRunStatus): Int =
        neo4jRepository.countByRunIdAndStatuses(
            runId.toString(),
            statuses.map { it.name },
        )

    override fun hasAnyActive(runId: UUID): Boolean =
        neo4jRepository.findOneActive(runId.toString()) != null

    override fun hasAnyFailed(runId: UUID): Boolean =
        neo4jRepository.findOneFailed(runId.toString()) != null

    companion object : KLogging()
}
