package io.whozoss.agentos.membership

import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.membership.MemberItem
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import io.whozoss.agentos.user.UserService
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Validates that [members] contains no duplicate [UserMembershipRole.userId] values.
 * Throws 400 if duplicates are found.
 *
 * This replaces the `@NoDuplicateUserIds` parameter annotation which cannot be applied
 * on an interface-override parameter due to Hibernate Validator HV000151 (an overriding
 * method must not redefine the parameter constraint configuration of the interface).
 */
internal fun requireNoDuplicateUserIds(members: List<UserMembershipRole>) {
    val duplicates = members.groupingBy { it.userId }.eachCount().filter { it.value > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "members list must not contain duplicate userIds: $duplicates",
        )
    }
}

/**
 * Resolves the users holding a direct ADMIN or MEMBER relation on [entityId] for the
 * given [entityType], with role precedence ADMIN > MEMBER for a user holding both
 * (should not normally happen given the single-relation invariant, but defended anyway).
 *
 * Shared by [NamespaceMembershipController], [CaseMembershipController], and
 * [NamespacePermissionServiceImpl.updateMembers] (which needs the current-state snapshot
 * for its two-tier authorization check, role delta, and anti-lockout guard).
 */
internal fun resolveMembers(
    entityId: UUID,
    entityType: EntityType,
    permissionService: PermissionService,
    userService: UserService,
): List<MemberItem> {
    val entityIdString = entityId.toString()
    val adminUserIds =
        permissionService
            .listUsersWithPermission(entityType, entityIdString, PermissionRelation.ADMIN)
            .toSet()
    val memberUserIds =
        permissionService
            .listUsersWithPermission(entityType, entityIdString, PermissionRelation.MEMBER)
            .toSet()
    val allUserIds = adminUserIds + memberUserIds
    if (allUserIds.isEmpty()) return emptyList()

    val uuids =
        allUserIds.mapNotNull { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()
                ?: run {
                    logger.warn { "Dropping malformed user id from permission listing on $entityType $entityId: '$raw'" }
                    null
                }
        }
    val users = userService.findByIds(uuids)

    val missingCount = uuids.size - users.size
    if (missingCount > 0) {
        logger.warn {
            "$entityType $entityId has $missingCount permission relation(s) pointing to " +
                "non-existent users — filtered from response"
        }
    }

    return users.map { user ->
        val userIdString = user.metadata.id.toString()
        MemberItem(
            id = user.metadata.id,
            externalId = user.externalId,
            email = user.email,
            firstname = user.firstname,
            lastname = user.lastname,
            role = if (userIdString in adminUserIds) "ADMIN" else "MEMBER",
        )
    }
}
