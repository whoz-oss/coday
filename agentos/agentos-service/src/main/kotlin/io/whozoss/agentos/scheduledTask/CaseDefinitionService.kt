package io.whozoss.agentos.scheduledTask

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [CaseDefinition] entities.
 *
 * Definitions are scoped under a namespace — the [ParentIdentifier] is the namespace UUID.
 *
 * Only CRUD operations are provided in this step. No triggering, no scheduling engine.
 */
interface CaseDefinitionService : EntityService<CaseDefinition, UUID> {
    /**
     * Toggle the [CaseDefinition.enabled] flag.
     *
     * @param id Definition identifier
     * @param enabled New enabled state
     * @return The updated definition
     * @throws io.whozoss.agentos.exception.ResourceNotFoundException if [id] does not exist
     */
    fun setEnabled(id: UUID, enabled: Boolean): CaseDefinition
}
