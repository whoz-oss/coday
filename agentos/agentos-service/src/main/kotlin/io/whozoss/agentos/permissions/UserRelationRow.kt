package io.whozoss.agentos.permissions

/**
 * SDN projection for a `(userId, relation)` row returned by
 * [PermissionNodeNeo4jRepository.findRelationsForUsers].
 *
 * Spring Data Neo4j maps the `RETURN u.id AS userId, type(r) AS relation` columns
 * onto this interface automatically. The `relation` column holds the Neo4j
 * relationship type string (e.g. `ADMIN`, `MEMBER`), which SDN maps directly to
 * [PermissionRelation] because the enum constant names match.
 */
interface UserRelationRow {
    val userId: String
    val relation: PermissionRelation
}
