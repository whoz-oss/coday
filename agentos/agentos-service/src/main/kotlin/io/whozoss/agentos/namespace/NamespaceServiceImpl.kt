package io.whozoss.agentos.namespace

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to NamespaceRepository.
 */
@Service
class NamespaceServiceImpl(
    private val namespaceRepository: NamespaceRepository,
) : NamespaceService {
    override fun create(entity: Namespace): Namespace = namespaceRepository.save(entity)

    override fun update(entity: Namespace): Namespace = namespaceRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<Namespace> = namespaceRepository.findByIds(ids)

    override fun findByParent(parentId: String): List<Namespace> = namespaceRepository.findByParent(parentId)

    override fun findAll(): List<Namespace> = namespaceRepository.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)

    override fun delete(id: UUID): Boolean = namespaceRepository.delete(id)

    override fun deleteByParent(parentId: String): Int = namespaceRepository.deleteByParent(parentId)
}
