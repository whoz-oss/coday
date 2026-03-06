package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [NamespaceRepository].
 *
 * Active only when `agentos.persistence.mode=in-memory`.
 * The default mode is file-system persistence via [FilesystemNamespaceRepository].
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory")
class InMemoryNamespaceRepository :
    NamespaceRepository,
    EntityRepository<Namespace, String> by InMemoryEntityRepository(
        parentIdExtractor = { "all" },
        comparator = compareBy { it.name },
    ) {
    init {
        save(
            Namespace(
                metadata = EntityMetadata(id = UUID.fromString("00000000-0000-0000-0000-000000000001")),
                name = "default_namespace",
                description = "Default namespace",
            ),
        )
    }
}
