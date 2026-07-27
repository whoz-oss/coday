package io.whozoss.agentos.permissions

/**
 * Represents the type of permission relationship between a user and an entity.
 * The entity graph these relations live in is described in `agentos/docs/study/entity-schema.md`.
 *
 * **Single-relation invariant**: a user holds AT MOST ONE of `[:ADMIN]`/`[:MEMBER]` per entity —
 * the relation is a role, not a capability stack. Role changes swap the edge type; they never
 * stack edges. Every write path upholds this: `promoteMemberToAdmin`/`demoteAdminToMember` and
 * `batchSetAdminRole`/`batchSetMemberRole` swap atomically, `createAdminPermission`/
 * `createMemberPermission` delete the opposite edge before merging theirs.
 *
 * **ADMIN supersedes MEMBER**: an ADMIN needs no parallel MEMBER edge — membership/READ checks
 * traverse `[:ADMIN|MEMBER]` (e.g. `hasMemberOrAdminPermission`, `filterIdsWhereUserHasAccess`).
 *
 * The MEMBER value is interpreted per method intent:
 * - threshold checks (`hasPermission`, `filterVisibleIds`): "at least member" — ADMIN passes;
 * - role-exact operations (`listUsersWithPermission`, `revokePermission`, grants): "exactly the
 *   MEMBER role" — admins are excluded.
 *
 * Neo4j cannot enforce relationship exclusivity with a constraint, so some readers defensively
 * collapse a hypothetical dual-edge state (ADMIN wins); that is belt-and-braces, not the model.
 */
enum class PermissionRelation {
    /**
     * Admin relationship - grants full access (READ, WRITE, DELETE) to the entity
     * and all its child entities through transitive permissions.
     */
    ADMIN,

    /**
     * Member relationship - grants limited access (READ only) to the entity
     * and its child entities through transitive permissions.
     */
    MEMBER,
}
