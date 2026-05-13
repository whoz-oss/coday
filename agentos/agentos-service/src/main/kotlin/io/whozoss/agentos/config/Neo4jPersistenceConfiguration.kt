package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigNodeNeo4jRepository
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository
import io.whozoss.agentos.agentConfig.Neo4jAgentConfigRepository
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.aiModel.AiModelNodeNeo4jRepository
import io.whozoss.agentos.aiModel.AiModelRepository
import io.whozoss.agentos.aiModel.Neo4JAiModelRepository
import io.whozoss.agentos.aiProvider.AiProviderNodeNeo4jRepository
import io.whozoss.agentos.aiProvider.AiProviderRepository
import io.whozoss.agentos.aiProvider.Neo4jAiProviderRepository
import io.whozoss.agentos.caseEvent.CaseEventNodeMapper
import io.whozoss.agentos.caseEvent.CaseEventNodeNeo4jRepository
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseEvent.MessageContentSerializer
import io.whozoss.agentos.caseEvent.Neo4jCaseEventRepository
import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.Neo4jCaseRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigNodeNeo4jRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.Neo4jIntegrationConfigRepository
import io.whozoss.agentos.namespace.NamespaceNodeNeo4jRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.namespace.Neo4jNamespaceRepository
import io.whozoss.agentos.permissions.Neo4jPermissionRepository
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import io.whozoss.agentos.permissions.PermissionRepository
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.user.Neo4jUserRepository
import io.whozoss.agentos.user.UserNodeNeo4jRepository
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.userGroup.Neo4jUserGroupRepository
import io.whozoss.agentos.userGroup.UserGroupNodeNeo4jRepository
import io.whozoss.agentos.userGroup.UserGroupRepository
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
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
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
@EnableNeo4jRepositories(
    basePackages = [
        "io.whozoss.agentos.agentConfig",
        "io.whozoss.agentos.aiModel",
        "io.whozoss.agentos.aiProvider",
        "io.whozoss.agentos.user",
        "io.whozoss.agentos.namespace",
        "io.whozoss.agentos.caseFlow",
        "io.whozoss.agentos.caseEvent",
        "io.whozoss.agentos.integrationConfig",
        "io.whozoss.agentos.permissions",
        "io.whozoss.agentos.userGroup",
    ],
)
class Neo4jPersistenceConfiguration {
    @Bean
    fun neo4jAgentConfigRepository(
        agentConfigNodeNeo4jRepository: AgentConfigNodeNeo4jRepository,
        childLinkService: Neo4jChildLinkService,
        namespaceService: NamespaceService,
    ): AgentConfigRepository {
        logger.info { "[Persistence] Neo4jAgentConfigRepository active (filesystem augmentation enabled)" }
        return FilesystemAgentConfigRepository(
            delegate = Neo4jAgentConfigRepository(agentConfigNodeNeo4jRepository, childLinkService),
            namespaceService = namespaceService,
        )
    }

    @Bean
    fun neo4jNamespaceRepository(namespaceNodeNeo4jRepository: NamespaceNodeNeo4jRepository): NamespaceRepository {
        logger.info { "[Persistence] Neo4jNamespaceRepository active" }
        return Neo4jNamespaceRepository(namespaceNodeNeo4jRepository)
    }

    @Bean
    fun neo4jCaseRepository(
        caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
        childLinkService: Neo4jChildLinkService,
    ): CaseRepository {
        logger.info { "[Persistence] Neo4jCaseRepository active" }
        return Neo4jCaseRepository(caseNodeNeo4jRepository, childLinkService)
    }

    @Bean
    fun neo4jCaseEventRepository(
        caseEventNodeNeo4jRepository: CaseEventNodeNeo4jRepository,
        objectMapper: ObjectMapper,
        childLinkService: Neo4jChildLinkService,
    ): CaseEventRepository {
        logger.info { "[Persistence] Neo4jCaseEventRepository active" }
        val mapper = CaseEventNodeMapper(MessageContentSerializer(objectMapper))
        return Neo4jCaseEventRepository(caseEventNodeNeo4jRepository, mapper, childLinkService)
    }

    @Bean
    fun neo4jUserRepository(userNodeNeo4jRepository: UserNodeNeo4jRepository): UserRepository {
        logger.info { "[Persistence] Neo4jUserRepository active" }
        return Neo4jUserRepository(userNodeNeo4jRepository)
    }

    @Bean
    fun neo4jPermissionRepository(permissionNodeNeo4jRepository: PermissionNodeNeo4jRepository): PermissionRepository {
        logger.info { "[Persistence] Neo4jPermissionRepository active" }
        return Neo4jPermissionRepository(permissionNodeNeo4jRepository)
    }

    @Bean
    fun neo4jIntegrationConfigRepository(
        integrationConfigNodeNeo4jRepository: IntegrationConfigNodeNeo4jRepository,
        objectMapper: ObjectMapper,
        childLinkService: Neo4jChildLinkService,
    ): IntegrationConfigRepository {
        logger.info { "[Persistence] Neo4jIntegrationConfigRepository active" }
        return Neo4jIntegrationConfigRepository(integrationConfigNodeNeo4jRepository, objectMapper, childLinkService)
    }

    @Bean
    fun neo4jAiProviderRepository(
        aiProviderNodeNeo4JRepository: AiProviderNodeNeo4jRepository,
        childLinkService: Neo4jChildLinkService,
    ): AiProviderRepository {
        logger.info { "[Persistence] Neo4jAiProviderRepository active" }
        return Neo4jAiProviderRepository(aiProviderNodeNeo4JRepository, childLinkService)
    }

    @Bean
    fun neo4jUserGroupRepository(
        userGroupNodeNeo4jRepository: UserGroupNodeNeo4jRepository,
        childLinkService: Neo4jChildLinkService,
        neo4jClient: Neo4jClient,
    ): UserGroupRepository {
        logger.info { "[Persistence] Neo4jUserGroupRepository active" }
        return Neo4jUserGroupRepository(userGroupNodeNeo4jRepository, childLinkService, neo4jClient)
    }

    @Bean
    fun neo4jAiModelRepository(
        aiModelNodeNeo4JRepository: AiModelNodeNeo4jRepository,
        childLinkService: Neo4jChildLinkService,
    ): AiModelRepository {
        logger.info { "[Persistence] Neo4jAiModelRepository active" }
        return Neo4JAiModelRepository(aiModelNodeNeo4JRepository, childLinkService)
    }

    companion object : KLogging()
}
