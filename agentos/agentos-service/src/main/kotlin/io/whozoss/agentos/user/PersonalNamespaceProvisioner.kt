package io.whozoss.agentos.user

import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.codayAgent.CodayAgentSeeder
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives every user a private namespace they administer, with the platform agent `Coday` deployed on it
 * and set as its default agent, so a user who connects can start working at once.
 *
 * ## What it guarantees for a user
 *
 * 1. a namespace with `externalId = "user:<externalId>"` (a **contract**, other components look the
 *    namespace up by it) and a name that is the local part of the email, or the raw external id;
 * 2. an ADMIN permission edge from the user to it;
 * 3. when the platform agent [CodayAgentSeeder.AGENT_NAME] exists: it is deployed there, and becomes
 *    the namespace's `defaultAgentName` if the namespace has none.
 *
 * Every step is idempotent and only ever adds: a namespace the user renamed, or whose default agent they
 * changed, is left as it is. The whole operation is [ensure].
 *
 * ## When it runs
 *
 * - **User creation**: [onUserCreated] listens to [UserCreatedEvent], published by
 *   [UserServiceImpl.create] for all three creation paths. The listener is a [TransactionalEventListener]
 *   with `fallbackExecution`, so it runs after the creating transaction commits when there is one (the
 *   user is then visible to the Cypher that grants the permission) and immediately when there is none.
 * - **Login**: [ensureOnce] is called by the authentication filter on every request. It is a set lookup
 *   once a user has been provisioned in this process, so a failed provisioning heals itself on the
 *   user's next request without costing the healthy ones anything.
 * - **Coday seeded later**: the seeder and the bootstrap admin have no guaranteed relative order, so a
 *   user can exist before `Coday` does. [CodayAgentSeeder] calls [ensureForAllUsers] after it creates the
 *   agent; in the other order the creation event finds the agent already there.
 *
 * ## Transactions
 *
 * The work runs in its own `REQUIRES_NEW` transaction. In the after-commit phase the transaction that
 * created the user is still bound to the thread but already committed, and a write that joined it would
 * fail at the driver; a fresh transaction suspends it.
 *
 * ## Failure
 *
 * Provisioning must never break user creation or a request: every failure is logged and swallowed, and
 * the user is simply not cached as provisioned, so the next login retries.
 *
 * Gated by `agentos.personal-namespace.enabled` ([PersonalNamespaceProperties]); disabled, every entry
 * point is a no-op.
 */
@Component
class PersonalNamespaceProvisioner(
    private val properties: PersonalNamespaceProperties,
    private val namespaceService: NamespaceService,
    private val permissionService: PermissionService,
    private val agentConfigService: AgentConfigService,
    private val userService: UserService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Users provisioned by this process: the cheap guard of [ensureOnce]. */
    private val provisioned = ConcurrentHashMap.newKeySet<UUID>()

    @TransactionalEventListener(fallbackExecution = true)
    fun onUserCreated(event: UserCreatedEvent) {
        ensure(event.userId, event.externalId)
    }

    /** [ensure] unless this process already provisioned [user]. */
    fun ensureOnce(user: User) {
        if (properties.enabled && user.id !in provisioned) ensure(user.id, user.externalId)
    }

    /** [ensure] for every existing user, bypassing the cache: used once `Coday` has just been created. */
    fun ensureForAllUsers() {
        if (properties.enabled) userService.findAll().forEach { ensure(it.id, it.externalId) }
    }

    /**
     * Provisions the personal namespace of one user. Never throws.
     *
     * @return true when the namespace exists and the user administers it.
     */
    fun ensure(
        userId: UUID,
        externalId: String,
    ): Boolean =
        when {
            !properties.enabled -> false
            else ->
                runCatching { transaction.execute { provision(userId, externalId) } == true }
                    .onSuccess { if (it) provisioned.add(userId) }
                    .onFailure { logger.warn(it) { "[PersonalNamespace] Provisioning failed for '$externalId', retried on its next request" } }
                    .getOrDefault(false)
        }

    private fun provision(
        userId: UUID,
        externalId: String,
    ): Boolean {
        val namespace = findOrCreate(externalId)
        permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
        agentConfigService.findByName(null, CodayAgentSeeder.AGENT_NAME)?.let { coday ->
            namespaceService.deployAgents(namespace.id, listOf(coday.id))
            if (namespace.defaultAgentName == null) {
                namespaceService.update(namespace.copy(defaultAgentName = coday.name))
            }
        }
        return true
    }

    private fun findOrCreate(externalId: String): Namespace {
        val key = namespaceExternalId(externalId)
        return namespaceService.findByExternalId(key)
            ?: try {
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = namespaceName(externalId),
                        description = "Personal space of $externalId",
                        externalId = key,
                    ),
                )
            } catch (e: ConflictException) {
                // Created concurrently by another request or instance: the unique externalId did its job.
                namespaceService.findByExternalId(key) ?: throw e
            }
    }

    companion object : KLogging() {
        /** Prefix of the `externalId` of a personal namespace: `user:<externalId of the user>`. */
        const val EXTERNAL_ID_PREFIX = "user:"

        fun namespaceExternalId(userExternalId: String): String = "$EXTERNAL_ID_PREFIX$userExternalId"

        /** The local part of an email, the raw external id otherwise. */
        fun namespaceName(userExternalId: String): String =
            userExternalId.substringBefore('@').takeIf { '@' in userExternalId && it.isNotBlank() } ?: userExternalId
    }
}
