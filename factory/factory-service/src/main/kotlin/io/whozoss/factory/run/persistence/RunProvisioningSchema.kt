package io.whozoss.factory.run.persistence

import org.springframework.beans.factory.InitializingBean
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Versioned inserts plus a unique key prevent concurrent launches overwriting attribution.
 *
 * Goes through [Neo4jClient] (not a raw driver session) so the constraint is created in the
 * configured `spring.data.neo4j.database`.
 */
@Component
class RunProvisioningSchema(private val neo4jClient: Neo4jClient) : InitializingBean {
    override fun afterPropertiesSet() {
        neo4jClient
            .query("CREATE CONSTRAINT factory_run_provisioning_intent_id IF NOT EXISTS FOR (i:FactoryRunProvisioningIntent) REQUIRE i.id IS UNIQUE")
            .run()
    }
}
