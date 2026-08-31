package io.whozoss.agentos.caseFlow

import org.springframework.data.neo4j.core.schema.RelationshipId
import org.springframework.data.neo4j.core.schema.RelationshipProperties
import org.springframework.data.neo4j.core.schema.TargetNode
import io.whozoss.agentos.user.UserNode
import java.time.Instant

/**
 * Properties on the `(User)-[:HAS_USER_CASE_STATE]->(Case)` relationship.
 *
 * This single edge consolidates all per-user, per-case state:
 * - [favoriteAt] — non-null when the user has favorited the case.
 * - [readAt] — timestamp of the user's last read; null means never read (unread).
 *
 * The edge is created lazily on first write (star or markRead). A missing edge
 * is semantically equivalent to `favoriteAt = null, readAt = null`.
 *
 * **Note**: this class is used as a `@RelationshipProperties` projection by SDN
 * when queries explicitly return the relationship. In most read paths we avoid
 * loading this via `@Relationship` on [CaseNode] to prevent fan-out; instead,
 * dedicated `@Query` methods on [CaseNodeNeo4jRepository] return only the scalar
 * values needed.
 */
@RelationshipProperties
class UserCaseStateRelationship(
    @RelationshipId
    val id: Long? = null,
    /**
     * Non-null when the user has favorited this case. The value is the timestamp of
     * the last favorite action — set to `now()` on favorite, cleared to `null` on
     * unfavorite.
     * Named `favoriteAt` to align with [io.whozoss.agentos.sdk.api.case.CaseDto.favorite].
     */
    val favoriteAt: Instant? = null,
    /**
     * Timestamp of the user's last explicit read of this case.
     * Null means the case has never been opened by this user.
     */
    val readAt: Instant? = null,
    @TargetNode
    val case: CaseNode? = null,
)
