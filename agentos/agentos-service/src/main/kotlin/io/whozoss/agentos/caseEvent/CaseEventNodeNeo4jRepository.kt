package io.whozoss.agentos.caseEvent

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

/**
 * Spring Data Neo4j repository for [CaseEventNode].
 */
interface CaseEventNodeNeo4jRepository : Neo4jRepository<CaseEventNode, String> {
    /**
     * Find all non-removed events for a case, ordered by timestamp then id.
     *
     * Returns `e, r, c` so SDN maps the [CaseEventNode.case] @Relationship field.
     */
    @Query(
        $$"""MATCH (e:CaseEvent)
            WHERE e.caseId = $caseId AND (e.removed IS NULL OR e.removed = false)
            OPTIONAL MATCH (e)-[r:BELONGS_TO]->(c:Case)
            RETURN e, r, c ORDER BY e.timestamp ASC, e.id ASC
            """,
    )
    fun findActiveByCaseId(caseId: String): List<CaseEventNode>

    /**
     * Return the timestamp of the most recent [MessageEventNode] for each of the given case ids.
     *
     * Returns one row per case that has at least one message. Cases with no messages are absent
     * from the result — the caller should fall back to the case's own creation timestamp.
     *
     * The result is a list of maps each holding `caseId` (String) and `lastMessageAt` (ZonedDateTime),
     * returned as a single `collect(...)` column so SDN can map it without a custom converter.
     * The Neo4j driver returns temporal values as [java.time.ZonedDateTime] in raw Map projections;
     * the caller must convert to [java.time.Instant] via [java.time.ZonedDateTime.toInstant].
     *
     * Used by [io.whozoss.agentos.caseFlow.CaseController] to enrich list responses with
     * [io.whozoss.agentos.sdk.api.case.CaseDto.lastMessageAt] without storing the value
     * on the Case node itself.
     */
    @Transactional(readOnly = true)
    @Query(
        $$"""UNWIND $caseIds AS cid
            MATCH (msg:MessageEvent {caseId: cid})
            WITH cid, max(msg.timestamp) AS lastMessageAt
            RETURN collect({caseId: cid, lastMessageAt: lastMessageAt})
            """,
    )
    fun findLastMessageTimestamps(
        @Param("caseIds") caseIds: List<String>,
    ): List<Map<String, Any>>
}
