package io.whozoss.agentos.authSetting

import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [AuthSettingService].
 *
 * Triple-mode invariant — `(namespaceId != null) OR (userId != null)` — is enforced on both
 * [create] and [update] (defence-in-depth, even if the controller already validates). A
 * violation surfaces as HTTP 400.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 *
 * The applicative pre-check ([findByTriple]) is kept for the common case so the caller gets a
 * descriptive 409 message; the catch on [DataIntegrityViolationException] is the defence
 * against concurrent inserts that race past the pre-check (the DB-level unique constraint on
 * `tripleKey` catches the loser). Mirrors the [AiProviderServiceImpl] pattern.
 */
@Service
class AuthSettingServiceImpl(
    private val repository: AuthSettingRepository,
    private val mergeStrategy: AuthSettingMergeStrategy,
    private val permissionService: PermissionService,
    private val userService: UserService,
) : AuthSettingService {
    override fun create(entity: AuthSetting): AuthSetting {
        findByTriple(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity))
        }
        assertConsistentAuthTypeAcrossLayers(entity)
        return saveOrConflict(entity)
    }

    override fun update(entity: AuthSetting): AuthSetting {
        findByTriple(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity))
            }
        assertConsistentAuthTypeAcrossLayers(entity)
        return saveOrConflict(entity)
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<AuthSetting> = repository.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<AuthSetting> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AuthSetting> = repository.findByNamespaceId(namespaceId)

    override fun findByUserId(userId: UUID): List<AuthSetting> = repository.findByUserId(userId)

    override fun findPlatformLevel(): List<AuthSetting> = repository.findPlatformLevel()

    override fun resolveAuthSetting(
        namespaceId: UUID,
        userId: UUID,
        name: String,
    ): AuthSetting {
        val layers =
            repository
                .findAllForScope(namespaceId, userId)
                .filter { it.name == name }
                .sortedWith(LAYER_COMPARATOR)
        return layers
            .drop(1)
            .fold(layers.firstOrNull() ?: throw ConfigNotFoundException(namespaceId, userId, name)) { base, override ->
                mergeStrategy.merge(base, override)
            }
    }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AuthSetting? = repository.findByTriple(namespaceId, userId, name)

    override fun findFiltered(
        namespaceId: UUID?,
        namespaceIsNone: Boolean,
        callerId: UUID,
        userRequested: Boolean,
    ): List<AuthSetting> =
        when {
            // Platform level: namespaceId=none without userId — open to all authenticated users
            namespaceIsNone && !userRequested -> {
                findPlatformLevel()
            }

            // NS-shared layer of a specific namespace (no userId param) : check READ permission
            namespaceId != null && !userRequested -> {
                if (!callerCanReadNamespace(namespaceId)) {
                    emptyList()
                } else {
                    findByNamespaceId(namespaceId).filter { it.userId == null }
                }
            }

            // User-scoped (userId=me requested) : start from user's configs and narrow by namespace
            userRequested -> {
                val nsFilter: (UUID?) -> Boolean =
                    when {
                        namespaceIsNone -> { nsId -> nsId == null }
                        namespaceId != null -> { nsId -> nsId == namespaceId }
                        else -> { _ -> true }
                    }
                findByUserId(callerId).filter { nsFilter(it.namespaceId) }
            }

            // No filter at all : surface the caller's own overlays
            else -> {
                findByUserId(callerId)
            }
        }

    private fun callerCanReadNamespace(namespaceId: UUID): Boolean =
        permissionService.hasPermission(
            userId = userService.getCurrentUser().id.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = namespaceId.toString(),
            action = Action.READ,
        )

    /**
     * Reject create/update when another layer that would merge with this entity at reconciliation
     * time already carries the same `name` but a different `authType`. Mirrors the IG-3 guard in
     * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl].
     *
     * The 4-tier reconciliation merges layers param-by-param assuming all layers share the same
     * `authType`. If they diverge, the merged setting silently switches the auth mechanism at
     * runtime — an OAUTH_DISCOVERABLE-typed entity ends up being treated as API_KEY, failing
     * opaquely deep in the agent run.
     *
     * Cross-user comparison is intentionally not a conflict: each user's overlay is private and
     * never merges into another user's resolution.
     */
    private fun assertConsistentAuthTypeAcrossLayers(entity: AuthSetting) {
        val userId = entity.userId
        val namespaceId = entity.namespaceId
        val name = entity.name
        val type = entity.authType
        val entityId = entity.metadata.id

        fun reject(other: AuthSetting): Nothing =
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An AuthSetting named '$name' already exists in another layer with " +
                    "authType='${other.authType}' (this entity wants authType='$type'). Overlay " +
                    "layers for the same name must share the same authType to merge correctly at " +
                    "reconciliation time. Either use a different name or align the authType.",
            )

        if (namespaceId != null) {
            repository
                .findByTriple(namespaceId, null, name)
                ?.takeIf { it.metadata.id != entityId && it.authType != type }
                ?.let(::reject)
        }

        if (userId != null) {
            repository
                .findByTriple(null, userId, name)
                ?.takeIf { it.metadata.id != entityId && it.authType != type }
                ?.let(::reject)

            if (namespaceId == null) {
                repository
                    .findByUserId(userId)
                    .firstOrNull {
                        it.metadata.id != entityId &&
                            it.namespaceId != null &&
                            it.name == name &&
                            it.authType != type
                    }?.let(::reject)
            }
        }
    }

    private fun saveOrConflict(entity: AuthSetting): AuthSetting =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Catches the race window: two concurrent creates pass the applicative pre-check,
            // both reach `save`, the DB unique constraint on `tripleKey` rejects one of them.
            // Translate to 409 only when the violation is identifiably the triple-uniqueness
            // constraint — any other integrity error is rethrown so it surfaces as 500 with
            // an honest stack trace rather than a misleading "name already exists" message.
            if (!isTripleKeyConflict(e)) {
                throw e
            }
            // Do NOT chain `e` to the logger: the Neo4j driver's exception message can echo
            // property values (incl. data map values, NFR-SEC-4). The exception is preserved as
            // the cause of the rethrown ResponseStatusException for stack-trace continuity.
            logger.warn {
                "[AuthSettingService] tripleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, name='${entity.name}')"
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, conflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun conflictMessage(entity: AuthSetting): String =
        "An auth setting named '${entity.name}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "auth_setting_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"

        /**
         * Comparator defining the 4-tier overlay precedence (lowest → highest).
         *
         * | Scope            | namespaceId | userId   | rank |
         * |------------------|-------------|----------|------|
         * | Platform         | null        | null     | 0    |
         * | User-global      | null        | non-null | 1    |
         * | Namespace-shared | non-null    | null     | 2    |
         * | User × namespace | non-null    | non-null | 3    |
         *
         * Namespace-shared (rank 2) overrides user-global (rank 1): namespace admins
         * enforce a config that supersedes user preferences. User×namespace (rank 3)
         * lets the user restore a personal override for that specific namespace.
         */
        val LAYER_COMPARATOR: Comparator<AuthSetting> =
            Comparator { a, b ->
                layerRank(a).compareTo(layerRank(b))
            }

        private fun layerRank(s: AuthSetting): Int {
            val nsRank = if (s.namespaceId == null) 0 else 2
            val userRank = if (s.userId == null) 0 else 1
            return nsRank + userRank
        }
    }
}
