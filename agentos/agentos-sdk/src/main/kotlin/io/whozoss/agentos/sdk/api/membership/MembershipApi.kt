package io.whozoss.agentos.sdk.api.membership

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import jakarta.validation.Valid
import java.util.UUID

/**
 * HTTP API contract for managing the membership (ADMIN / MEMBER relations) of any
 * shareable entity: Namespace, Case, UserGroup, …
 *
 * Two endpoints per entity:
 * - `GET  /{entityId}/members` — list current members with their roles
 * - `PATCH /{entityId}/members` — delta update (add / change role / revoke)
 *
 * Implemented by concrete `*MembershipController` classes in agentos-service, each
 * adding their own `@RestController` + `@RequestMapping` and entity-specific
 * authorization rules. External consumers implement this interface as a Feign client.
 *
 * The [UserMembershipRole] request body uses delta semantics: only listed users are
 * affected; users absent from the list are left untouched. A null role means revoke.
 */
interface MembershipApi {

    @Operation(
        summary = "List members",
        description = "Returns all users holding a direct ADMIN or MEMBER relation on the entity.",
    )
    fun getMembers(entityId: UUID): List<MemberItem>

    @Operation(
        summary = "Update members",
        description = "Delta update: add, change role, or revoke users. Users absent from the list are untouched.",
    )
    fun updateMembers(entityId: UUID, @Valid members: List<UserMembershipRole>): List<MemberItem>
}
