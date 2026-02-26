package io.whozoss.agentos.namespace

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * In-memory implementation of NamespaceService.
 *
 * Delegates all persistence operations to NamespaceRepository.
 */
@Service
class NamespaceServiceImpl(
    private val namespaceRepository: NamespaceRepository,
) : NamespaceService {

    override fun save(entity: NamespaceModel): NamespaceModel = namespaceRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<NamespaceModel> = namespaceRepository.findByIds(ids)

    override fun findByParent(parentId: Unit): List<NamespaceModel> = namespaceRepository.findByParent(parentId)

    override fun findAll(): List<NamespaceModel> = namespaceRepository.findByParent(Unit)

    override fun delete(id: UUID): Boolean = namespaceRepository.delete(id)

    override fun deleteByParent(parentId: Unit): Int = namespaceRepository.deleteByParent(parentId)
}
