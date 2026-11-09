package io.whozoss.factory.worktree.persistence

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancy
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyConflict
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyLegacyData
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancySchemaUnavailable
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStaleRelease
import io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Repository
class Neo4jWorktreeExecutionOccupancyRepository(
    private val client: Neo4jClient,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    fun acquire(
        scope: TenantScope,
        resourceIdentity: String,
        ownerKind: String,
        ownerId: String,
    ): WorktreeExecutionOccupancy = acquireInternal(scope, resourceIdentity, ownerKind, ownerId, transaction)

    /** Acquisition variant that must commit with the caller's attempt bind. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun acquireJoiningTransaction(
        scope: TenantScope,
        resourceIdentity: String,
        ownerKind: String,
        ownerId: String,
    ): WorktreeExecutionOccupancy = acquireInternal(scope, resourceIdentity, ownerKind, ownerId, null)

    private fun acquireInternal(
        scope: TenantScope,
        resourceIdentity: String,
        ownerKind: String,
        ownerId: String,
        template: TransactionTemplate?,
    ): WorktreeExecutionOccupancy {
        requireUniqueConstraint()
        requireNoActiveLegacyOccupancy()
        val id = occupancyId(resourceIdentity)
        return executeRetryingDeadlock {
            val operation = {
                lockSeed(id, resourceIdentity)
                val current = readRequired(id)
                when {
                    current.status == WorktreeExecutionOccupancyStatus.RELEASED -> {
                        client.query(
                            "MATCH (o:WorktreeExecutionOccupancy {id: \$id}) " +
                                "SET o.status='active', o.ownerOrganizationId=\$org, " +
                                "o.ownerWorkstreamId=\$ws, o.ownerKind=\$kind, " +
                                "o.ownerId=\$owner, o.generation=o.generation+1 " +
                                "RETURN o.id AS id",
                        ).bindAll(
                            mapOf(
                                "id" to id,
                                "org" to scope.organizationId,
                                "ws" to scope.workstreamId,
                                "kind" to ownerKind,
                                "owner" to ownerId,
                            ),
                        ).fetch().one().orElseThrow()
                        readRequired(id)
                    }
                    current.status == WorktreeExecutionOccupancyStatus.ACTIVE &&
                        current.ownerScope == scope && current.ownerKind == ownerKind && current.ownerId == ownerId -> current
                    else -> throw WorktreeExecutionOccupancyConflict("Worktree resource is already occupied")
                }
            }
            template?.execute { operation() } ?: operation()
        }
    }

    /** Direct fenced hand-off. It is illegal to invoke this outside the answer transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun transfer(
        scope: TenantScope,
        resourceIdentity: String,
        predecessorOwnerKind: String,
        predecessorOwnerId: String,
        predecessorGeneration: Long,
        successorOwnerKind: String,
        successorOwnerId: String,
    ): WorktreeExecutionOccupancy {
        requireUniqueConstraint()
        requireNoActiveLegacyOccupancy()
        val id = occupancyId(resourceIdentity)
        lockExisting(id)
        val current = readRequired(id)
        if (current.status != WorktreeExecutionOccupancyStatus.ACTIVE ||
            current.ownerScope != scope ||
            current.ownerKind != predecessorOwnerKind ||
            current.ownerId != predecessorOwnerId ||
            current.generation != predecessorGeneration
        ) {
            throw WorktreeExecutionOccupancyStaleRelease("Stale worktree occupancy transfer")
        }
        client.query(
            "MATCH (o:WorktreeExecutionOccupancy {id: \$id}) " +
                "WHERE o.status='active' AND o.ownerOrganizationId=\$org " +
                "AND o.ownerWorkstreamId=\$ws AND o.ownerKind=\$predecessorKind " +
                "AND o.ownerId=\$predecessorOwner AND o.generation=\$predecessorGeneration " +
                "SET o.ownerKind=\$successorKind, o.ownerId=\$successorOwner, " +
                "o.generation=o.generation+1 RETURN o.id AS id",
        ).bindAll(
            mapOf(
                "id" to id,
                "org" to scope.organizationId,
                "ws" to scope.workstreamId,
                "predecessorKind" to predecessorOwnerKind,
                "predecessorOwner" to predecessorOwnerId,
                "predecessorGeneration" to predecessorGeneration,
                "successorKind" to successorOwnerKind,
                "successorOwner" to successorOwnerId,
            ),
        ).fetch().one().orElseThrow { WorktreeExecutionOccupancyStaleRelease("Stale worktree occupancy transfer") }
        return readRequired(id)
    }

    fun release(
        scope: TenantScope,
        resourceIdentity: String,
        ownerKind: String,
        ownerId: String,
        generation: Long,
    ): WorktreeExecutionOccupancy {
        requireUniqueConstraint()
        requireNoActiveLegacyOccupancy()
        val id = occupancyId(resourceIdentity)
        return executeRetryingDeadlock {
            transaction.execute {
                lockExisting(id)
                val current = readRequired(id)
                if (current.status != WorktreeExecutionOccupancyStatus.ACTIVE ||
                    current.ownerScope != scope || current.ownerKind != ownerKind ||
                    current.ownerId != ownerId || current.generation != generation
                ) {
                    throw WorktreeExecutionOccupancyStaleRelease("Stale worktree occupancy release")
                }
                client.query(
                    "MATCH (o:WorktreeExecutionOccupancy {id: \$id}) " +
                        "SET o.status='released' RETURN o.id AS id",
                ).bind(id).to("id").fetch().one().orElseThrow()
                readRequired(id)
            }!!
        }
    }

    fun find(resourceIdentity: String): WorktreeExecutionOccupancy? = readById(occupancyId(resourceIdentity))

    private fun requireNoActiveLegacyOccupancy() {
        val count = client.query(
            "MATCH (o:WorktreeExecutionOccupancy) " +
                "WHERE o.status='active' AND coalesce(o.formatVersion,0) <> \$version " +
                "RETURN count(o) AS count",
        ).bind(WorktreeExecutionOccupancy.FORMAT_VERSION).to("version")
            .fetchAs(Long::class.java).mappedBy { _, row -> row["count"].asLong() }.one().orElse(0)
        if (count > 0) {
            throw WorktreeExecutionOccupancyLegacyData("Active legacy worktree occupancy requires explicit migration")
        }
    }

    private fun requireUniqueConstraint() {
        val count = client.query(
            "SHOW CONSTRAINTS YIELD name,type,labelsOrTypes,properties " +
                "WHERE name=\$name AND type='UNIQUENESS' " +
                "AND labelsOrTypes=['WorktreeExecutionOccupancy'] AND properties=['id'] " +
                "RETURN count(*) AS count",
        ).bind(UNIQUE_CONSTRAINT).to("name")
            .fetchAs(Long::class.java).mappedBy { _, row -> row["count"].asLong() }.one().orElse(0)
        if (count != 1L) {
            throw WorktreeExecutionOccupancySchemaUnavailable(
                "Required worktree occupancy uniqueness constraint is unavailable",
            )
        }
    }

    private fun lockSeed(id: String, resourceIdentity: String) {
        client.query(
            "MERGE (o:WorktreeExecutionOccupancy {id:\$id}) " +
                "ON CREATE SET o.resourceIdentity=\$resourceIdentity,o.formatVersion=\$version," +
                "o.status='released',o.generation=0,o.technicalLock=0 " +
                "SET o.technicalLock=coalesce(o.technicalLock,0)+1 " +
                "RETURN o.technicalLock AS technicalLock",
        ).bindAll(
            mapOf(
                "id" to id,
                "resourceIdentity" to resourceIdentity,
                "version" to WorktreeExecutionOccupancy.FORMAT_VERSION,
            ),
        ).fetch().one().orElseThrow()
        val row = client.query(
            "MATCH (o:WorktreeExecutionOccupancy {id:\$id}) " +
                "RETURN o.formatVersion AS version,o.resourceIdentity AS resourceIdentity",
        ).bind(id).to("id").fetch().one().orElseThrow()
        if ((row["version"] as Number).toInt() != WorktreeExecutionOccupancy.FORMAT_VERSION ||
            row["resourceIdentity"] != resourceIdentity
        ) {
            throw WorktreeExecutionOccupancyLegacyData("Incompatible worktree occupancy format")
        }
    }

    private fun lockExisting(id: String) {
        client.query(
            "MATCH (o:WorktreeExecutionOccupancy {id:\$id}) " +
                "SET o.technicalLock=coalesce(o.technicalLock,0)+1 " +
                "RETURN o.technicalLock AS technicalLock",
        ).bind(id).to("id").fetch().one().orElseThrow {
            WorktreeExecutionOccupancyStaleRelease("No worktree occupancy exists")
        }
    }

    private fun readRequired(id: String): WorktreeExecutionOccupancy =
        readById(id) ?: error("Occupancy disappeared after lock")

    private fun readById(id: String): WorktreeExecutionOccupancy? = client.query(
        "MATCH (o:WorktreeExecutionOccupancy {id:\$id}) " +
            "RETURN o.resourceIdentity AS resourceIdentity,o.ownerOrganizationId AS org," +
            "o.ownerWorkstreamId AS ws,o.ownerKind AS kind,o.ownerId AS owner," +
            "o.generation AS generation,o.status AS status,o.formatVersion AS version",
    ).bind(id).to("id").fetchAs(WorktreeExecutionOccupancy::class.java).mappedBy { _, row ->
        WorktreeExecutionOccupancy(
            resourceIdentity = row["resourceIdentity"].asString(),
            ownerScope = TenantScope(row["org"].asString(""), row["ws"].asString("")),
            ownerKind = row["kind"].takeUnless { it.isNull }?.asString(),
            ownerId = row["owner"].takeUnless { it.isNull }?.asString(),
            generation = row["generation"].asLong(),
            status = if (row["status"].asString() == "active") {
                WorktreeExecutionOccupancyStatus.ACTIVE
            } else {
                WorktreeExecutionOccupancyStatus.RELEASED
            },
            formatVersion = row["version"].asInt(),
        )
    }.one().orElse(null)

    internal fun <T> executeRetryingDeadlock(block: () -> T): T {
        repeat(MAX_DEADLOCK_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (!isNeo4jDeadlock(error) || attempt == MAX_DEADLOCK_ATTEMPTS - 1) throw error
            }
        }
        error("unreachable")
    }

    internal fun isNeo4jDeadlock(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }
            .any { it.message?.contains(NEO4J_DEADLOCK_CODE) == true }

    companion object {
        const val UNIQUE_CONSTRAINT = "worktree_execution_occupancy_id_unique"
        internal const val MAX_DEADLOCK_ATTEMPTS = 3
        private const val NEO4J_DEADLOCK_CODE = "Neo.TransientError.Transaction.DeadlockDetected"

        fun occupancyId(resourceIdentity: String): String = "v2:${resourceIdentity.length}:$resourceIdentity"
    }
}
