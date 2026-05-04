package io.whozoss.agentos.namespace

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to [NamespaceRepository].
 *
 * Composes [PermissionService] only for [findIdsVisibleTo], a thin typed query
 * helper used by [NamespaceController.listAll]. CRUD operations remain pure
 * repository delegates.
 */
@Service
class NamespaceServiceImpl(
    private val namespaceRepository: NamespaceRepository,
    private val permissionService: PermissionService,
) : NamespaceService {
    override fun create(entity: Namespace): Namespace = namespaceRepository.save(entity)

    override fun update(entity: Namespace): Namespace = namespaceRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<Namespace> = namespaceRepository.findByIds(ids)

    override fun findByParent(parentId: String): List<Namespace> = namespaceRepository.findByParent(parentId)

    override fun findAll(): List<Namespace> = namespaceRepository.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)

    override fun findByExternalId(externalId: String): Namespace? = namespaceRepository.findByExternalId(externalId)

    override fun delete(id: UUID): Boolean = namespaceRepository.delete(id)

    override fun deleteByParent(parentId: String): Int = namespaceRepository.deleteByParent(parentId)

    override fun findIdsVisibleTo(userId: String, action: Action): List<UUID> =
        permissionService
            .listEntitiesForUser(userId, EntityType.NAMESPACE, action)
            .mapNotNull { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
                    ?: run {
                        logger.warn { "[NamespaceService] Dropping malformed id from permission listing: '$raw'" }
                        null
                    }
            }

    companion object : KLogging()
}
