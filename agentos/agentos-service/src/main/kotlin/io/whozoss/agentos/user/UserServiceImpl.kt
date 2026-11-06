package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import mu.KLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Default implementation of [UserService].
 *
 * Delegates all persistence operations to [UserRepository].
 * Holds a reference to [SecurityService] solely for [getCurrentUser] — the one
 * operation that needs to know the caller's identity.
 *
 * [create] publishes a [UserCreatedEvent] once the user is saved. Every creation path goes through it
 * (first access, bulk import, the bootstrap admin), which is what lets
 * [PersonalNamespaceProvisioner] react to all of them from one place. Listeners bound to the
 * transaction run after it commits.
 */
@Service
class UserServiceImpl(
    private val userRepository: UserRepository,
    private val securityService: SecurityService,
    private val eventPublisher: ApplicationEventPublisher,
) : UserService {
    @Transactional
    override fun create(entity: User): User =
        userRepository.save(entity).also { eventPublisher.publishEvent(UserCreatedEvent(it.id, it.externalId)) }

    @Transactional
    override fun update(entity: User): User = userRepository.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<User> = userRepository.findByIds(ids, withRemoved)

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
                        isAdmin = isFirstUser, // First user = super-admin
                    ),
                )
            }
        }

    override fun createByExternalIds(externalIds: Set<String>): List<User> {
        logger.info { "[UserService] Auto-creating users for ${externalIds.size}" }
        return externalIds.map { externalId ->
            create(
                User(
                    metadata = EntityMetadata(),
                    externalId = externalId,
                    email = extractEmailFromExternalId(externalId),
                    isAdmin = false,
                ),
            )
        }
    }

    override fun getCurrentUser(): User = authenticatedUser() ?: resolveOrCreateByExternalId(securityService.resolveCurrentIdentity())

    /**
     * The user authenticated on this thread, read again so that a change made since shows: the one
     * [io.whozoss.agentos.security.declarative.AgentOsAuthenticationFilter] resolved for the request, or
     * the one a messaging chat acts for. Null when nobody is.
     */
    private fun authenticatedUser(): User? =
        (SecurityContextHolder.getContext().authentication as? AgentOsAuthentication)?.let { authentication ->
            val id = (authentication.principal as User).id
            userRepository.findById(id) ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "The authenticated user $id no longer exists")
        }

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
