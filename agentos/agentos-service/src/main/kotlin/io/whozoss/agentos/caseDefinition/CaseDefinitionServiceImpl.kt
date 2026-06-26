package io.whozoss.agentos.caseDefinition

import io.whozoss.agentos.exception.ResourceNotFoundException
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to [CaseDefinitionRepository].
 *
 * No scheduling logic is present in this step — purely declarative CRUD.
 */
@Service
class CaseDefinitionServiceImpl(
    private val caseDefinitionRepository: CaseDefinitionRepository,
) : CaseDefinitionService {

    override fun create(entity: CaseDefinition): CaseDefinition =
        caseDefinitionRepository.save(entity)
            .also { logger.info { "[CaseDefinition] Created '${it.name}' (${it.id}) ns=${it.namespaceId}" } }

    override fun update(entity: CaseDefinition): CaseDefinition =
        caseDefinitionRepository.save(entity)
            .also { logger.info { "[CaseDefinition] Updated '${it.name}' (${it.id})" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<CaseDefinition> =
        caseDefinitionRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<CaseDefinition> =
        caseDefinitionRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean =
        caseDefinitionRepository.delete(id)
            .also { if (it) logger.info { "[CaseDefinition] Soft-deleted $id" } }

    override fun deleteByParent(parentId: UUID): Int =
        caseDefinitionRepository.deleteByParent(parentId)

    override fun setEnabled(id: UUID, enabled: Boolean): CaseDefinition {
        val existing = caseDefinitionRepository.findById(id)
            ?: throw ResourceNotFoundException("CaseDefinition not found: $id")
        return caseDefinitionRepository.save(existing.copy(enabled = enabled))
            .also { logger.info { "[CaseDefinition] Set enabled=$enabled on $id" } }
    }

    companion object : KLogging()
}
