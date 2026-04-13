package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [UserService].
 *
 * Delegates all persistence operations to [UserRepository].
 * Holds a reference to [SecurityService] solely for [getCurrentUser] — the one
 * request-scoped operation that needs to know the caller's identity.
 */
@Service
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val securityService: SecurityService,
) : UserService {
    override fun create(entity: User): User = userRepository.save(entity)

    override fun update(entity: User): User = userRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<User> = userRepository.findByIds(ids)

    override fun findByParent(parentId: String): List<User> = userRepository.findByParent(parentId)

    override fun findAll(): List<User> = userRepository.findByParent(UserRepository.USER_PARENT_KEY)

    override fun findByExternalId(externalId: String): User? = userRepository.findByExternalId(externalId)

    override fun resolveOrCreateByExternalId(externalId: String): User =
        findByExternalId(externalId) ?: run {
            logger.info { "[UserService] Auto-creating user for externalId='$externalId'" }
            create(
                User(
                    metadata = EntityMetadata(),
                    externalId = externalId,
                )
            )
        }

    override fun getCurrentUser(): User =
        resolveOrCreateByExternalId(securityService.resolveCurrentIdentity())

    override fun delete(id: UUID): Boolean = userRepository.delete(id)

    override fun deleteByParent(parentId: String): Int = userRepository.deleteByParent(parentId)

    companion object : KLogging()
}
