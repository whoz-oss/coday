package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.neo4j.CaseEventNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.CaseNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.NamespaceNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jCaseEventRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jCaseRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jNamespaceRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jUserRepository
import io.whozoss.agentos.persistence.neo4j.UserNodeNeo4jRepository
import io.whozoss.agentos.user.UserRepository
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers Neo4j-backed repository beans.
 *
 * Active for both persistence modes that use a live Neo4j engine:
 * - `neo4j`           standalone server (Docker / remote); Driver from Spring Boot auto-config
 * - `embedded-neo4j`  in-process engine; Driver from EmbeddedNeo4jConfiguration
 *
 * In both cases a Driver bean is present before this configuration runs,
 * so Spring Data Neo4j SDN repositories resolve correctly.
 *
 * For `neo4j` mode configure:
 *   spring.neo4j.uri / spring.neo4j.authentication.*
 * Start a local server: `docker compose up neo4j`
 *
 * The ObjectMapper must be the application-wide instance (KotlinModule + Jackson
 * polymorphism) so that CaseEvent payload serialisation round-trips correctly.
 */
@Configuration
@EnableConfigurationProperties(PersistenceConfigProperties::class)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:filesystem}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:filesystem}' == 'embedded-neo4j'",
)
class Neo4jPersistenceConfiguration {
    @Bean
    fun neo4jNamespaceRepository(sdnRepo: NamespaceNodeNeo4jRepository): NamespaceRepository {
        logger.info { "[Persistence] Neo4jNamespaceRepository active" }
        return Neo4jNamespaceRepository(sdnRepo)
    }

    @Bean
    fun neo4jCaseRepository(sdnRepo: CaseNodeNeo4jRepository): CaseRepository {
        logger.info { "[Persistence] Neo4jCaseRepository active" }
        return Neo4jCaseRepository(sdnRepo)
    }

    @Bean
    fun neo4jCaseEventRepository(
        sdnRepo: CaseEventNodeNeo4jRepository,
        objectMapper: ObjectMapper,
    ): CaseEventRepository {
        logger.info { "[Persistence] Neo4jCaseEventRepository active" }
        return Neo4jCaseEventRepository(sdnRepo, objectMapper)
    }

    @Bean
    fun neo4jUserRepository(sdnRepo: UserNodeNeo4jRepository): UserRepository {
        logger.info { "[Persistence] Neo4jUserRepository active" }
        return Neo4jUserRepository(sdnRepo)
    }

    companion object : KLogging()
}
