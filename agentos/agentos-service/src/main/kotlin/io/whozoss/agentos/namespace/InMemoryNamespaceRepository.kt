package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [NamespaceRepository].
 *
 * Active when `agentos.persistence.mode` is absent, `in-memory`, or any value
 * other than `neo4j` or `embedded-neo4j`. This is the default fallback used by
 * the openapi spec generation task and lightweight local runs.
 */
@Repository
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' != 'neo4j' " +
        "and '\${agentos.persistence.mode:in-memory}' != 'embedded-neo4j'",
)
class InMemoryNamespaceRepository :
    NamespaceRepository,
    EntityRepository<Namespace, String> by InMemoryEntityRepository(
        parentIdExtractor = { NamespaceRepository.NAMESPACE_PARENT_KEY },
        comparator = compareBy { it.name },
    ) {
    init {
        // Seed a well-known default namespace so in-memory mode (dev/test) is usable
        // out of the box without requiring an explicit creation step.
        // The fixed UUID makes the seed deterministic and idempotent across restarts.
        save(
            Namespace(
                metadata = EntityMetadata(id = UUID.fromString("00000000-0000-0000-0000-000000000001")),
                name = "default_namespace",
                description = "Default namespace",
            ),
        )
    }
}
