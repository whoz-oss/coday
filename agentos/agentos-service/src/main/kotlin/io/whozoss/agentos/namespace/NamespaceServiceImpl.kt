package io.whozoss.agentos.namespace

import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.userGroup.UserGroupRepository
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Delegates all persistence operations to [NamespaceRepository].
 *
 * Composes [PermissionService] only for [findIdsVisibleTo], a thin typed query
 * helper used by [NamespaceController.listAll]. CRUD operations remain pure
 * repository delegates.
 *
 * [delete] cascades soft-delete to children that this branch introduces — currently only
 * [UserGroup] (cf. cross-cutting review H-4). Pre-existing namespace children
 * (IntegrationConfig, AiProvider, AiModel) are NOT cascaded here: that gap predates this
 * branch and is tracked as separate tech-debt to keep the scope of this PR focused.
 *
 * The cascade is wired via [UserGroupRepository] (not the service) to avoid the
 * circular bean dependency with `UserGroupServiceImpl`, which already depends on
 * `NamespaceService` for `findByExternalId`.
 */
@Service
class NamespaceServiceImpl(
    private val namespaceRepository: NamespaceRepository,
    private val permissionService: PermissionService,
    private val userGroupRepository: UserGroupRepository,
    private val agentConfigRepository: AgentConfigRepository,
) : NamespaceService {
    @Transactional
    override fun create(entity: Namespace): Namespace = try {
        namespaceRepository.save(entity)
    } catch (e: DataIntegrityViolationException) {
        throw ConflictException("A namespace with externalId '${entity.externalId}' already exists", e)
    }

    @Transactional
    override fun update(entity: Namespace): Namespace = try {
        namespaceRepository.save(entity)
    } catch (e: DataIntegrityViolationException) {
        throw ConflictException("A namespace with externalId '${entity.externalId}' already exists", e)
    }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<Namespace> = namespaceRepository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: String): List<Namespace> = namespaceRepository.findByParent(parentId)

    override fun findAll(): List<Namespace> = namespaceRepository.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)

    override fun findByExternalId(externalId: String): Namespace? = namespaceRepository.findByExternalId(externalId)

    override fun findByExternalIds(externalIds: Collection<String>): List<Namespace> = namespaceRepository.findByExternalIds(externalIds)

    @Transactional
    override fun delete(id: UUID): Boolean {
        // Cascade soft-delete the children introduced by this branch BEFORE the namespace
        // itself, so a transient failure on the parent leaves the children in a known
        // "removed" state rather than orphaned-but-active. Pre-existing children
        // (IntegrationConfig, AiProvider, AiModel) are NOT cascaded — see class KDoc.
        runCatching { userGroupRepository.deleteByParent(id) }
            .onSuccess { count ->
                if (count > 0) {
                    logger.info { "[NamespaceService] Cascade soft-deleted $count UserGroup(s) under namespace $id" }
                }
            }
            .onFailure { e ->
                logger.warn(e) { "[NamespaceService] UserGroup cascade failed for namespace $id — proceeding with namespace soft-delete anyway" }
            }
        return namespaceRepository.delete(id)
    }

    @Transactional
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

    @Transactional
    override fun deployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>) {
        agentConfigIds.takeIf { it.isNotEmpty() }?.let { ids ->
            findById(namespaceId)
                ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")
            validateAgentsInNamespace(ids, namespaceId)
            namespaceRepository.deployAgents(namespaceId, ids)
        }
    }

    @Transactional
    override fun undeployAgents(namespaceId: UUID, agentConfigIds: Collection<UUID>) {
        agentConfigIds.takeIf { it.isNotEmpty() }?.let { ids ->
            findById(namespaceId)
                ?: throw ResourceNotFoundException("Namespace not found: $namespaceId")
            namespaceRepository.undeployAgents(namespaceId, ids)
        }
    }

    private fun validateAgentsInNamespace(agentConfigIds: Collection<UUID>, namespaceId: UUID) {
        agentConfigIds
            .takeIf { it.isNotEmpty() }
            ?.let { ids ->
                val found = agentConfigRepository.findByIds(ids)
                val validIds = found.filter { it.namespaceId == namespaceId }.map { it.id }.toSet()
                val invalidIds = ids.toSet() - validIds
                if (invalidIds.isNotEmpty()) {
                    throw UnprocessableEntityException("Agent configs not found in namespace $namespaceId: $invalidIds")
                }
            }
    }

    companion object : KLogging()
}
