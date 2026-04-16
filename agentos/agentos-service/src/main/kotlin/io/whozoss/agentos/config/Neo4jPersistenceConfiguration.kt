package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.agentConfig.AgentConfigNodeNeo4jRepository
import io.whozoss.agentos.agentConfig.Neo4jAgentConfigRepository
import io.whozoss.agentos.persistence.neo4j.CaseEventNodeMapper
import io.whozoss.agentos.persistence.neo4j.CaseEventNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.CaseNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.IntegrationConfigNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.MessageContentSerializer
import io.whozoss.agentos.persistence.neo4j.NamespaceNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jCaseEventRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jCaseRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jIntegrationConfigRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jNamespaceRepository
import io.whozoss.agentos.persistence.neo4j.Neo4jUserRepository
import io.whozoss.agentos.persistence.neo4j.UserNodeNeo4jRepository
import io.whozoss.agentos.user.UserRepository
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories

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
 */
@Configuration
@EnableConfigurationProperties(PersistenceConfigProperties::class)
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
@EnableNeo4jRepositories(basePackages = ["io.whozoss.agentos.persistence.neo4j", "io.whozoss.agentos.agentConfig"])
class Neo4jPersistenceConfiguration {
    @Bean
    fun neo4jAgentConfigRepository(agentConfigNodeNeo4jRepository: AgentConfigNodeNeo4jRepository): AgentConfigRepository {
        logger.info { "[Persistence] Neo4jAgentConfigRepository active" }
        return Neo4jAgentConfigRepository(agentConfigNodeNeo4jRepository)
    }

    @Bean
    fun neo4jNamespaceRepository(namespaceNodeNeo4jRepository: NamespaceNodeNeo4jRepository): NamespaceRepository {
        logger.info { "[Persistence] Neo4jNamespaceRepository active" }
        return Neo4jNamespaceRepository(namespaceNodeNeo4jRepository)
    }

    @Bean
    fun neo4jCaseRepository(caseNodeNeo4jRepository: CaseNodeNeo4jRepository): CaseRepository {
        logger.info { "[Persistence] Neo4jCaseRepository active" }
        return Neo4jCaseRepository(caseNodeNeo4jRepository)
    }

    @Bean
    fun neo4jCaseEventRepository(
        caseEventNodeNeo4jRepository: CaseEventNodeNeo4jRepository,
        objectMapper: ObjectMapper,
    ): CaseEventRepository {
        logger.info { "[Persistence] Neo4jCaseEventRepository active" }
        val mapper = CaseEventNodeMapper(MessageContentSerializer(objectMapper))
        return Neo4jCaseEventRepository(caseEventNodeNeo4jRepository, mapper)
    }

    @Bean
    fun neo4jUserRepository(userNodeNeo4jRepository: UserNodeNeo4jRepository): UserRepository {
        logger.info { "[Persistence] Neo4jUserRepository active" }
        return Neo4jUserRepository(userNodeNeo4jRepository)
    }

    @Bean
    fun neo4jIntegrationConfigRepository(
        integrationConfigNodeNeo4jRepository: IntegrationConfigNodeNeo4jRepository,
        objectMapper: ObjectMapper,
    ): IntegrationConfigRepository {
        logger.info { "[Persistence] Neo4jIntegrationConfigRepository active" }
        return Neo4jIntegrationConfigRepository(integrationConfigNodeNeo4jRepository, objectMapper)
    }

    companion object : KLogging()
}
