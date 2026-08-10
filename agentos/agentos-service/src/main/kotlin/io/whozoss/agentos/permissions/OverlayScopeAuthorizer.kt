package io.whozoss.agentos.permissions

import io.whozoss.agentos.entity.ExternalIdentifierResolver
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.user.UserService
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolved scope for an overlay-entity request, after authorization has already been
 * enforced by the [OverlayScopeAuthorizer] method that produced it.
 *
 * For [OverlayScopeAuthorizer.authorizeSearchOrThrow] and [OverlayScopeAuthorizer.authorizeCreateOrThrow],
 * either field may be null (the four-layer scope model below). For
 * [OverlayScopeAuthorizer.authorizeEffectiveOrThrow], [namespaceId] and [userId] are always
 * non-null the endpoint's contract requires a namespace and always resolves to the
 * authenticated caller.
 */
data class AuthorizedScope(
    val namespaceId: UUID?,
    val userId: UUID?,
)

/**
 * Centralises the authorization logic shared by every overlay-entity controller endpoint
 * that is not a simple per-id hasPermission(...) check, i.e. search, effective,
 * and create, where the target entity does not exist yet (or is not addressed by id) and
 * authorization must instead reason about the requested (namespaceId, userId) scope.
 *
 * The overlay model has four layers, ordered here by ascending specificity:
 *
 * | namespaceId | userId   | layer            | required permission                       |
 * |-------------|----------|------------------|---------------------------------------------|
 * | null        | null     | platform         | search/effective: authenticated; create: Super Admin |
 * | non-null    | null     | namespace-shared | READ (search/effective) / WRITE (create) on the namespace |
 * | null        | non-null | user-global      | authenticated (always the caller's own scope) |
 * | non-null    | non-null | user x namespace | READ on the namespace (the user owns the record) |
 *
 * Why this is a bean of require*-style methods rather than a @PreAuthorize SpEL
 * expression, two reasons, both structural rather than stylistic:
 *
 * 1. HTTP status codes are not uniform. The mass-assignment guard (body.userId must
 *    equal the authenticated caller) is a malformed request, 400, not a permission
 *    refusal, 403. A @PreAuthorize expression only ever produces AccessDeniedException
 *    (403) on false; it cannot distinguish the two. Throwing the correctly-typed
 *    exception from inside the method body is the only way to preserve both status codes.
 * 2. External-id resolution would otherwise happen twice. Request DTOs accept either
 *    a UUID or an external identifier (namespaceExternalId, userExternalId), resolved
 *    via [ExternalIdentifierResolver] with a Neo4j lookup. A SpEL expression evaluated
 *    before the method body cannot see that resolved value, so it would have to either
 *    re-resolve inside the method (a second round-trip per request) or duplicate the
 *    resolution logic in SpEL itself (not expressible). Doing both resolution and
 *    authorization in one method call keeps it to a single round-trip.
 *
 * Each method returns the [AuthorizedScope] it validated, so the controller never repeats
 * the resolution, it consumes the same values it authorized against.
 *
 * **Naming**: every method carries the `OrThrow` suffix. These are guards, not plain
 * queries — the whole point of calling one is that an unauthorized or malformed request
 * never reaches the line after the call. A name like `authorizeSearch` reads as if it could
 * be safely ignored or its result discarded; `authorizeSearchOrThrow` makes the control-flow
 * consequence part of the call site, consistent with how a reviewer should read it inline.
 */
@Service
class OverlayScopeAuthorizer(
    private val permissionService: PermissionService,
    private val userService: UserService,
    private val externalIdentifierResolver: ExternalIdentifierResolver,
) {
    /**
     * Authorizes a search request: resolves external ids, applies the mass-assignment
     * guard on [userId] (a caller may only search their own user-scoped layer unless they
     * are Super Admin), then requires namespace READ when [namespaceId] resolves non-null.
     *
     * @param pluralLabel Human-readable plural noun used verbatim in error messages
     *   (e.g. "prompts", "scheduled prompts").
     */
    fun authorizeSearchOrThrow(
        pluralLabel: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
        userId: UUID?,
        userExternalId: String?,
    ): AuthorizedScope {
        val currentUser = userService.getCurrentUser()
        val resolvedNs = externalIdentifierResolver.resolveOptionalNamespaceId(namespaceId, namespaceExternalId)
        val resolvedUserId = externalIdentifierResolver.resolveOptionalUserId(userId, userExternalId)

        if (resolvedUserId != null && resolvedUserId != currentUser.id && !currentUser.isAdmin) {
            throw AccessDeniedException("Cannot search $pluralLabel for another user")
        }
        if (resolvedNs != null) {
            requireNamespaceRead(currentUser.id, resolvedNs, "Cannot read $pluralLabel in namespace $resolvedNs")
        }
        return AuthorizedScope(namespaceId = resolvedNs, userId = resolvedUserId)
    }

    /**
     * Authorizes an effective request: the namespace is mandatory (resolved via
     * [ExternalIdentifierResolver.resolveNamespaceId], which throws [BadRequestException]
     * when both identifiers are absent), and READ on it is required. The returned
     * [AuthorizedScope] always has both fields non-null: [AuthorizedScope.namespaceId] is
     * the resolved namespace, [AuthorizedScope.userId] is the authenticated caller,
     * effective only ever resolves the merged overlay set for the caller themselves.
     *
     * @param pluralLabel Human-readable plural noun used verbatim in error messages.
     */
    fun authorizeEffectiveOrThrow(
        pluralLabel: String,
        namespaceId: UUID?,
        namespaceExternalId: String?,
    ): AuthorizedScope {
        val nsId = externalIdentifierResolver.resolveNamespaceId(namespaceId, namespaceExternalId)
        val currentUser = userService.getCurrentUser()
        requireNamespaceRead(currentUser.id, nsId, "Cannot read $pluralLabel in namespace $nsId")
        return AuthorizedScope(namespaceId = nsId, userId = currentUser.id)
    }

    /**
     * Authorizes a create request: applies the mass-assignment guard on
     * [requestedUserId], then dispatches on the four-layer scope table (see class KDoc).
     *
     * Namespace existence is deliberately not checked here, callers that need it
     * (platform-scoped entities have none; namespace-scoped ones do) run that check
     * themselves, and it must run after this method returns successfully. Leaking a 404
     * before authorization would let an unauthorized caller distinguish "namespace exists
     * but I can't write to it" from "namespace doesn't exist", an enumeration oracle.
     *
     * @param entityLabel Human-readable entity name used verbatim in error messages
     *   (e.g. "Prompt", "ScheduledPrompt").
     */
    fun authorizeCreateOrThrow(
        entityLabel: String,
        requestedNamespaceId: UUID?,
        requestedUserId: UUID?,
    ): AuthorizedScope {
        val currentUser = userService.getCurrentUser()
        val me = currentUser.id
        if (requestedUserId != null && requestedUserId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }

        val resolvedNs = requestedNamespaceId
        val resolvedUser = if (requestedUserId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        when {
            isPlatform -> {
                if (!currentUser.isAdmin) {
                    throw AccessDeniedException("Platform-level $entityLabel requires Super Admin")
                }
            }
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted =
                    permissionService.hasPermission(
                        userId = me.toString(),
                        entityType = EntityType.NAMESPACE,
                        entityId = resolvedNs.toString(),
                        action = authzAction,
                    )
                if (!granted) {
                    throw AccessDeniedException(
                        "Cannot create $entityLabel in namespace $resolvedNs (${authzAction.name} required)",
                    )
                }
            }
            // user-global: isAuthenticated() from @PreAuthorize is sufficient
        }

        return AuthorizedScope(namespaceId = resolvedNs, userId = resolvedUser)
    }

    private fun requireNamespaceRead(
        callerId: UUID,
        namespaceId: UUID,
        message: String,
    ) {
        val granted =
            permissionService.hasPermission(
                userId = callerId.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = namespaceId.toString(),
                action = Action.READ,
            )
        if (!granted) throw AccessDeniedException(message)
    }
}
