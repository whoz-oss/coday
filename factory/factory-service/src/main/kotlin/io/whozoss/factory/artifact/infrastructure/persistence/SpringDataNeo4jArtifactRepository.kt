package io.whozoss.factory.artifact.infrastructure.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query

/** Explicit organization and workstream boundaries for artifact metadata. */
interface SpringDataNeo4jArtifactRepository : Neo4jRepository<ArtifactMetadataNode, String> {
    @Query("MATCH (a:ArtifactMetadata) WHERE a.organizationId = \$organizationId AND a.id = \$artifactId RETURN a")
    fun findInOrganization(organizationId: String, artifactId: String): ArtifactMetadataNode?

    @Query("MATCH (a:ArtifactMetadata) WHERE a.organizationId = \$organizationId RETURN a")
    fun findAllInOrganization(organizationId: String): List<ArtifactMetadataNode>

    @Query("MATCH (a:ArtifactMetadata) WHERE a.organizationId = \$organizationId AND a.workstreamId = \$workstreamId RETURN a")
    fun findAllByScope(organizationId: String, workstreamId: String): List<ArtifactMetadataNode>
}
