package io.whozoss.agentos.userGroup

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserGroupServiceImpl(
    private val userGroupRepository: UserGroupRepository,
) : UserGroupService {
    override fun create(entity: UserGroup): UserGroup = userGroupRepository.save(entity)
    override fun update(entity: UserGroup): UserGroup = userGroupRepository.save(entity)
    override fun findByIds(ids: Collection<UUID>): List<UserGroup> = userGroupRepository.findByIds(ids)
    override fun findByParent(parentId: UUID): List<UserGroup> = userGroupRepository.findByParent(parentId)
    override fun delete(id: UUID): Boolean = userGroupRepository.delete(id)
    override fun deleteByParent(parentId: UUID): Int = userGroupRepository.deleteByParent(parentId)
}
