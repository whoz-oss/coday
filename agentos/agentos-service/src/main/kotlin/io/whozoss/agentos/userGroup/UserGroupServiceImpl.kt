package io.whozoss.agentos.userGroup

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
    private val namespaceService: NamespaceService,
) : UserGroupService {
    override fun create(entity: UserGroup): UserGroup = try {
        userGroupRepository.save(entity)
    } catch (e: DataIntegrityViolationException) {
        throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
    }

    override fun update(entity: UserGroup): UserGroup = try {
        userGroupRepository.save(entity)
    } catch (e: DataIntegrityViolationException) {
        throw ConflictException("A user group with name '${entity.name}' already exists in this namespace", e)
    }

    override fun findByIds(ids: Collection<UUID>): List<UserGroup> = userGroupRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<UserGroup> = userGroupRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = userGroupRepository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = userGroupRepository.deleteByParent(parentId)

    override fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult> =
        userGroupRepository.findByNamespaceExternalId(externalId)

    override fun createFromRequest(request: UserGroupCreateRequest): UserGroupSearchResult {
        val namespace =
            namespaceService.findByExternalId(request.namespaceExternalId)
                ?: throw UnprocessableEntityException("Namespace not found for externalId: ${request.namespaceExternalId}")

        val created = create(
            UserGroup(
                namespaceId = namespace.id,
                name = request.name,
            ),
        )

        // Project the freshly-created entity into the search-result shape directly. Re-fetching
        // via `findByNamespaceExternalId` was racy (TOCTOU with concurrent soft-delete) and
        // unnecessary — agent/user counters are not populated in this story (TODO field tracked
        // separately) so the projection only carries identity + name, which we already have.
        return UserGroupSearchResult(
            userGroupId = created.metadata.id,
            namespaceId = namespace.id,
            namespaceExternalId = request.namespaceExternalId,
            name = created.name,
        )
    }
}
