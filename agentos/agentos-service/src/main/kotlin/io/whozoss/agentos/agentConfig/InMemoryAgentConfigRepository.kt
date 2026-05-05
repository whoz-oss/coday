package io.whozoss.agentos.agentConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * In-memory base implementation of [AgentConfigRepository].
 *
 * Not registered directly as a Spring bean — wrapped by
 * [InMemoryAgentConfigRepositoryConfiguration] in a [FilesystemAgentConfigRepository]
 * decorator so that filesystem-based agent definitions are also available in
 * dev/test runs.
 *
 * Agent configs are sorted by name within a namespace.
 */
class InMemoryAgentConfigRepository :
    AgentConfigRepository,
    EntityRepository<AgentConfig, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    )

/**
 * Registers the [AgentConfigRepository] bean for the in-memory persistence mode.
 *
 * Wraps [InMemoryAgentConfigRepository] in [FilesystemAgentConfigRepository] so
 * that namespaces with a [configPath] also expose filesystem-defined agent configs.
 */
@Configuration
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryAgentConfigRepositoryConfiguration {
    @Bean
    fun inMemoryAgentConfigRepository(namespaceService: NamespaceService): AgentConfigRepository =
        FilesystemAgentConfigRepository(
            delegate = InMemoryAgentConfigRepository(),
            namespaceService = namespaceService,
        )
}
