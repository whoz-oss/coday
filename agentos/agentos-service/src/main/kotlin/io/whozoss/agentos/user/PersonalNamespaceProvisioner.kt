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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Gives every user a private namespace they administer, with its own agent `Coday` deployed on it
 * and set as its default agent, so a user who connects can start working at once.
 *
 * ## What it guarantees for a user
 *
 * 1. a namespace with `externalId = "user:<externalId>"` (a **contract**, other components look the
 *    namespace up by it) and a name that is the local part of the email, or the raw external id;
 * 2. an ADMIN permission edge from the user to it;
 * 3. when the default agent is enabled ([CodayAgentSeeder.ensureIn]): the namespace owns an agent named
 *    [CodayAgentSeeder.AGENT_NAME] (a namespace-scoped agent, created in it when it has none), that agent is
 *    deployed there, and it becomes the namespace's `defaultAgentName` if the namespace has none.
 *
 * The agent belongs to the namespace, not to the platform, so that it can never clash with an agent of the
 * same name in another namespace.
 *
 * Every step is idempotent and only ever adds: a namespace the user renamed, or whose default agent they
 * changed, is left as it is, and a `Coday` the namespace already owns (ours or one the user made) is never
 * rewritten. The whole operation is [ensure].
 *
 * ## The one exception to "only ever adds": migrating the legacy platform `Coday`
 *
 * Earlier versions created a single **platform** agent `Coday` at startup and deployed it on each personal
 * namespace. Once the personal namespace owns its own `Coday` (ours, or the user's own), the provisioner
 * **undeploys** that platform agent from **that personal namespace**, so that a personal namespace never has
 * two deployed agents named `Coday`. This is the only thing the provisioner ever removes, and it is limited
 * to a `DEPLOYED_TO` edge: the platform agent itself is neither modified nor deleted (an administrator can
 * delete it through the API), and the namespaces that are not personal are not touched. The legacy agent is
 * found among the platform agents ([AgentConfigService.findByNamespace] with a null namespace) by name,
 * not through `findByName`, which would return the namespace's own agent first. Nothing is undeployed when
 * the namespace does not own a `Coday`, for instance when the default agent is disabled.
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
 * - **Upgrade**: nothing runs at startup. A user that existed before the upgrade gets its own `Coday` (and
 *   loses the legacy platform one on its namespace) on its next request, through [ensureOnce], which is not
 *   cached across restarts. [ensureForAllUsers] remains for tests and operations.
 *
 * ## Concurrency
 *
 * On a first launch the client fires several requests at once, and the creation event runs [ensure] on the
 * thread that created the user while the other requests find that user and run it too. Within this process
 * the calls for **one user** are therefore serialised by a per-user lock (users do not wait for each
 * other), taken *before* the transaction is opened: a waiting call only starts its transaction once the
 * first one has committed, so it sees the namespace instead of racing to create it. [ensureOnce] checks the
 * cache again once it holds the lock, so the callers queued behind the first one return at once.
 * Across processes nothing is shared: the unique `externalId` of an active namespace is the arbiter and
 * the loser recovers, see below.
 *
 * ## Transactions
 *
 * The work runs in its own `REQUIRES_NEW` transaction. In the after-commit phase the transaction that
 * created the user is still bound to the thread but already committed, and a write that joined it would
 * fail at the driver; a fresh transaction suspends it.
 *
 * The creation of the namespace runs in a transaction of its own, nested in that one. When another
 * instance creates the same namespace first, the unique constraint rejects the loser's creation and that
 * transaction is rolled back (the failed statement leaves it unusable, and the service joining it marks it
 * rollback-only). Only then is the namespace read again, from the enclosing transaction which was never
 * touched by the failure, and used as if it had been found in the first place.
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
    private val codayAgentSeeder: CodayAgentSeeder,
    private val userService: UserService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Creation of the namespace, isolated so that a constraint violation rolls back only itself. */
    private val creation =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** A lock and the number of callers holding or waiting for it, so it can be dropped when unused. */
    private class UserLock {
        val lock = ReentrantLock()
        var users = 0 // only touched inside ConcurrentHashMap.compute, atomic per key
    }

    private val userLocks = ConcurrentHashMap<UUID, UserLock>()

    /** Users provisioned by this process: the cheap guard of [ensureOnce]. */
    private val provisioned = ConcurrentHashMap.newKeySet<UUID>()

    @TransactionalEventListener(fallbackExecution = true)
    fun onUserCreated(event: UserCreatedEvent) {
        ensure(event.userId, event.externalId)
    }

    /**
     * [ensure] unless this process already provisioned [user]. The cache is checked again under the
     * user's lock, so concurrent first requests provision once.
     */
    fun ensureOnce(user: User) {
        if (properties.enabled && user.id !in provisioned) provisionSerialised(user.id, user.externalId, skipIfProvisioned = true)
    }

    /** [ensure] for every existing user, bypassing the cache. Nothing calls it at startup; for tests and operations. */
    fun ensureForAllUsers() {
        if (properties.enabled) userService.findAll().forEach { ensure(it.id, it.externalId) }
    }

    /**
     * Provisions the personal namespace of one user, whether or not it was provisioned before (so that a
     * lost grant or a missing `Coday` is repaired). Calls for the same user are serialised. Never throws.
     *
     * @return true when the namespace exists and the user administers it.
     */
    fun ensure(
        userId: UUID,
        externalId: String,
    ): Boolean = provisionSerialised(userId, externalId, skipIfProvisioned = false)

    private fun provisionSerialised(
        userId: UUID,
        externalId: String,
        skipIfProvisioned: Boolean,
    ): Boolean =
        when {
            !properties.enabled -> false
            else ->
                runCatching {
                    // The lock is taken outside the transaction: the next caller opens its own once this one is committed.
                    withUserLock(userId) {
                        if (skipIfProvisioned && userId in provisioned) {
                            true
                        } else {
                            (transaction.execute { provision(userId, externalId) } == true).also { if (it) provisioned.add(userId) }
                        }
                    }
                }.onFailure { logger.warn(it) { "[PersonalNamespace] Provisioning failed for '$externalId', retried on its next request" } }
                    .getOrDefault(false)
        }

    private fun <T> withUserLock(
        userId: UUID,
        action: () -> T,
    ): T {
        val entry = userLocks.compute(userId) { _, current -> (current ?: UserLock()).also { it.users++ } }!!
        try {
            return entry.lock.withLock(action)
        } finally {
            userLocks.compute(userId) { _, current -> if (current != null && --current.users == 0) null else current }
        }
    }

    private fun provision(
        userId: UUID,
        externalId: String,
    ): Boolean {
        val namespace = findOrCreate(externalId)
        permissionService.grantPermission(userId.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
        codayAgentSeeder.ensureIn(namespace.id)?.let { coday ->
            namespaceService.deployAgents(namespace.id, listOf(coday.id))
            if (namespace.defaultAgentName == null) {
                namespaceService.update(namespace.copy(defaultAgentName = coday.name))
            }
            undeployLegacyPlatformCoday(namespace.id)
        }
        return true
    }

    /**
     * Removes the `DEPLOYED_TO` edge between the legacy platform `Coday` and this personal namespace, if any (see
     * the class KDoc). Looked up among the platform agents only, never through `findByName`, whose fallback
     * would hand back the agent the namespace owns. The platform agent row itself is left alone.
     */
    private fun undeployLegacyPlatformCoday(namespaceId: UUID) {
        agentConfigService
            .findByNamespace(null, withDisabled = true)
            .filter { it.name.equals(CodayAgentSeeder.AGENT_NAME, ignoreCase = true) }
            .takeIf { it.isNotEmpty() }
            ?.let { legacy ->
                namespaceService.undeployAgents(namespaceId, legacy.map { it.id })
                logger.debug { "[PersonalNamespace] Undeployed the platform '${CodayAgentSeeder.AGENT_NAME}' from namespace $namespaceId" }
            }
    }

    private fun findOrCreate(externalId: String): Namespace {
        val key = namespaceExternalId(externalId)
        return namespaceService.findByExternalId(key)
            ?: try {
                creation.execute {
                    namespaceService.create(
                        Namespace(
                            metadata = EntityMetadata(id = UUID.randomUUID()),
                            name = namespaceName(externalId),
                            description = "Personal space of $externalId",
                            externalId = key,
                        ),
                    )
                }!!
            } catch (e: ConflictException) {
                // Created concurrently by another instance: the unique externalId did its job. The creation
                // transaction is rolled back by now and unusable, so the winner's namespace is read here, outside it.
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
