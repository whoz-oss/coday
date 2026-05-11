package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import mu.KLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    @Transactional
    override fun create(entity: User): User = userRepository.save(entity)

    @Transactional
    override fun update(entity: User): User = userRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<User> = userRepository.findByIds(ids)

    override fun findByParent(parentId: String): List<User> = userRepository.findByParent(parentId)

    override fun findAll(): List<User> = userRepository.findByParent(UserRepository.USER_PARENT_KEY)

    override fun count(): Long = userRepository.count()

    override fun findByExternalId(externalId: String): User? = userRepository.findByExternalId(externalId)

    override fun findByExternalIds(externalIds: Set<String>): List<User> = userRepository.findByExternalIds(externalIds)

    override fun resolveOrCreateByExternalId(externalId: String): User =
        findByExternalId(externalId) ?: bootstrapLock.withLock {
            // Double-check after acquiring lock to prevent TOCTOU race condition
            findByExternalId(externalId) ?: run {
                // Check if this is the first user in the system
                val isFirstUser = userRepository.count() == 0L

                logger.info { "[UserService] Auto-creating user for externalId='$externalId', isAdmin=$isFirstUser" }

                create(
                    User(
                        metadata = EntityMetadata(),
                        externalId = externalId,
                        email = extractEmailFromExternalId(externalId),
                        isAdmin = isFirstUser  // First user = super-admin
                    )
                )
            }
        }

    override fun getCurrentUser(): User =
        resolveOrCreateByExternalId(securityService.resolveCurrentIdentity())

    @Transactional
    override fun delete(id: UUID): Boolean = userRepository.delete(id)

    @Transactional
    override fun deleteByParent(parentId: String): Int = userRepository.deleteByParent(parentId)

    /**
     * Extracts the email from the externalId if it is a valid email address.
     * Otherwise, returns an empty string.
     *
     * @param externalId The external identifier that may be an email
     * @return The email if valid, otherwise an empty string
     */
    private fun extractEmailFromExternalId(externalId: String): String {
        // Simple check: if the externalId contains @ it's likely an email
        return if (externalId.contains("@")) {
            externalId
        } else {
            ""
        }
    }

    companion object : KLogging() {
        /** Lock to prevent TOCTOU race on first-user bootstrap. Only contended during the very first request. */
        private val bootstrapLock = ReentrantLock()
    }
}
