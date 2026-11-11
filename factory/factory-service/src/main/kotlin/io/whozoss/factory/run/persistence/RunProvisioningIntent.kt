package io.whozoss.factory.run.persistence

import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.repository.Neo4jRepository

/** Immutable launch attribution; versioned inserts refuse concurrent replacement. */
@Node("FactoryRunProvisioningIntent")
data class RunProvisioningIntent(
    @Id val id: String,
    @Version val version: Long? = null,
    val actorId: String,
    val commandHash: String,
    val rootCaseId: String,
)

interface RunProvisioningIntentRepository : Neo4jRepository<RunProvisioningIntent, String>
