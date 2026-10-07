package io.whozoss.factory.artifact.infrastructure.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/**
 * Spring Data Neo4j repository for [ArtifactMetadataNode].
 *
 * The CRUD operations inherited from [Neo4jRepository] cover the point lookups
 * and the metadata commit/update. [findAllByScope] supports the GC reconciler,
 * which needs every authoritative metadata row of a tenant.
 */
interface SpringDataNeo4jArtifactRepository : Neo4jRepository<ArtifactMetadataNode, String> {

    @Query(
        """
        MATCH (a:ArtifactMetadata)
        WHERE a.organizationId = ${'$'}organizationId AND a.workstreamId = ${'$'}workstreamId
        RETURN a
        """,
    )
    fun findAllByScope(organizationId: String, workstreamId: String): List<ArtifactMetadataNode>
}
