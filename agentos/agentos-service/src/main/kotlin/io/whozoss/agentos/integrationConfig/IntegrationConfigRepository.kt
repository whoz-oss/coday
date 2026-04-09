package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [IntegrationConfig] persistence.
 *
 * Integration configs are scoped to a namespace — [parentId] is always a [UUID] (the namespaceId).
 * Storage layout (filesystem): `<dataDir>/integration-configs/<namespaceId>/<configId>.json`
 */
interface IntegrationConfigRepository : EntityRepository<IntegrationConfig, UUID>
