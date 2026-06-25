package io.whozoss.agentos.scheduledTask

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [CaseDefinition] persistence.
 *
 * Definitions are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface CaseDefinitionRepository : EntityRepository<CaseDefinition, UUID>
