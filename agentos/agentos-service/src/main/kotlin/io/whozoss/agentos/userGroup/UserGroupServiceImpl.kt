package io.whozoss.agentos.userGroup

import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
    private val namespaceService: NamespaceService,
) : UserGroupService {
    override fun create(entity: UserGroup): UserGroup = userGroupRepository.save(entity)

    override fun update(entity: UserGroup): UserGroup = userGroupRepository.save(entity)

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

        create(
            UserGroup(
                namespaceId = namespace.id,
                name = request.name,
            ),
        )

        // TODO: Query user group with counters
        return userGroupRepository
            .findByNamespaceExternalId(request.namespaceExternalId)
            .first { it.name == request.name }
    }
}
