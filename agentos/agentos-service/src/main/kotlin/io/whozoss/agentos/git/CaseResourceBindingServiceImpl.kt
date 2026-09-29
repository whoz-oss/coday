package io.whozoss.agentos.git

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default [CaseResourceBindingService], delegating persistence to [CaseResourceBindingRepository].
 */
@Service
class CaseResourceBindingServiceImpl(
    private val repository: CaseResourceBindingRepository,
) : CaseResourceBindingService {
    override fun create(entity: CaseResourceBinding): CaseResourceBinding = repository.save(entity)

    override fun update(entity: CaseResourceBinding): CaseResourceBinding = repository.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseResourceBinding> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<CaseResourceBinding> = repository.findByParent(parentId)

    override fun findByRootCaseId(rootCaseId: UUID): CaseResourceBinding? = repository.findByRootCaseId(rootCaseId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)
}
