package io.whozoss.factory.run.persistence

import org.neo4j.driver.Driver
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/** Versioned inserts plus a unique key prevent concurrent launches overwriting attribution. */
@Component
class RunProvisioningSchema(private val driver: Driver) : InitializingBean {
    override fun afterPropertiesSet() {
        driver.session().use { session ->
            session.run("CREATE CONSTRAINT factory_run_provisioning_intent_id IF NOT EXISTS FOR (i:FactoryRunProvisioningIntent) REQUIRE i.id IS UNIQUE").consume()
        }
    }
}
