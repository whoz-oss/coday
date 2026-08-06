package io.whozoss.agentos.permissions

/**
 * SDN projection for a `(userId, relation)` row returned by
 * [PermissionNodeNeo4jRepository.findRelationsForUsers].
 *
 * Spring Data Neo4j maps the `RETURN u.id AS userId, type(r) AS relation` columns
 * onto this interface automatically.
 */
interface UserRelationRow {
    val userId: String
    val relation: String
}
