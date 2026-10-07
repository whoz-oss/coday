package io.whozoss.factory.agentattempt.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository

/**
 * Spring Data Neo4j repository for [IdempotencyRecordNode].
 *
 * The CRUD inherited from [Neo4jRepository] is enough: the tenant-scoped node id
 * is the composite business key, so a replay is a `findById` and the
 * first-write-wins `ON CONFLICT DO NOTHING` semantics is expressed by the
 * adapter as an existence check before `save`.
 */
interface SpringDataNeo4jIdempotencyRepository : Neo4jRepository<IdempotencyRecordNode, String>
