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
 * Active only when `agentos.persistence.in-memory=true`.
 * The default mode is file-system persistence via [FilesystemNamespaceRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryNamespaceRepository :
    NamespaceRepository,
    EntityRepository<Namespace, Unit> by InMemoryEntityRepository(
        parentIdExtractor = { },
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
