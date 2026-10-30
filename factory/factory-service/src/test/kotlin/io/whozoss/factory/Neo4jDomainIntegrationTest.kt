package io.whozoss.factory

import io.whozoss.factory.delivery.port.DeliveryEvidenceStore
import io.whozoss.factory.delivery.port.DeliveryTargetRegistry
import io.whozoss.factory.persistence.TenantScope
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * Shared base class for the integration tests of the aggregates migrated to
 * embedded Neo4j in Phase 2 (delivery, lease, worker, work unit, environment).
 *
 * It extends [Neo4jIntegrationTest] (in-process harness, no Docker) and adds the
 * tenant scope plus the process-local delivery evidence/target stores the
 * delivery tests exercise. The graph itself is cleared by
 * [Neo4jIntegrationTest.clearGraph]; this base only resets the in-memory stores,
 * which are not Neo4j-backed.
 */
abstract class Neo4jDomainIntegrationTest : Neo4jIntegrationTest() {

    @Autowired
    protected lateinit var deliveryEvidenceStore: DeliveryEvidenceStore

    @Autowired
    protected lateinit var deliveryTargetRegistry: DeliveryTargetRegistry

    protected val scope: TenantScope
        get() = TenantScope(ORGANIZATION_ID, WORKSTREAM_ID)

    @BeforeEach
    fun resetProcessLocalStores() {
        deliveryEvidenceStore.clear()
        deliveryTargetRegistry.clear()
    }

    companion object {
        const val ORGANIZATION_ID = "org-local-dev"
        const val WORKSTREAM_ID = "ws-default"
    }
}
