package io.whozoss.agentos.user

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [UserService].
 *
 * Delegates all persistence operations to [UserRepository].
 */
@Service
class UserServiceImpl(
    private val userRepository: UserRepository,
) : UserService {
    override fun create(entity: User): User = userRepository.save(entity)

    override fun update(entity: User): User = userRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<User> = userRepository.findByIds(ids)

    override fun findByParent(parentId: String): List<User> = userRepository.findByParent(parentId)

    override fun findAll(): List<User> = userRepository.findByParent(UserRepository.USER_PARENT_KEY)

    override fun findByExternalId(externalId: String): User? = userRepository.findByExternalId(externalId)

    override fun delete(id: UUID): Boolean = userRepository.delete(id)

    override fun deleteByParent(parentId: String): Int = userRepository.deleteByParent(parentId)
}
