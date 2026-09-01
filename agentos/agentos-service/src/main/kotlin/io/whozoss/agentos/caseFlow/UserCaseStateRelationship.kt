package io.whozoss.agentos.caseFlow

import org.springframework.data.neo4j.core.schema.RelationshipId
import org.springframework.data.neo4j.core.schema.RelationshipProperties
import org.springframework.data.neo4j.core.schema.TargetNode
import io.whozoss.agentos.user.UserNode
import java.time.Instant

/**
 * Properties on the `(User)-[:WATCHES]->(Case)` relationship.
 *
 * This single edge consolidates all per-user, per-case state:
 * - [favorite] — true when the user has favorited the case.
 * - [readAt] — timestamp of the user's last read; null means never read (unread).
 *
 * The edge is created lazily on first write (star or markRead). A missing edge
 * is semantically equivalent to `favorite = false, readAt = null`.
 *
 * **Note**: this class is used as a `@RelationshipProperties` projection by SDN
 * when queries explicitly return the relationship. In most read paths we avoid
 * loading this via `@Relationship` on [CaseNode] to prevent fan-out; instead,
 * dedicated `@Query` methods on [CaseNodeNeo4jRepository] return only the scalar
 * values needed.
 *
 * The class name `UserCaseStateRelationship` is intentionally implementation-neutral;
 * the graph relationship type is `WATCHES`.
 */
@RelationshipProperties
class UserCaseStateRelationship(
    @RelationshipId
    val id: Long? = null,
    /** True when the user has favorited this case; false otherwise. */
    val favorite: Boolean = false,
    /**
     * Timestamp of the user's last explicit read of this case.
     * Null means the case has never been opened by this user.
     */
    val readAt: Instant? = null,
    @TargetNode
    val case: CaseNode? = null,
)
