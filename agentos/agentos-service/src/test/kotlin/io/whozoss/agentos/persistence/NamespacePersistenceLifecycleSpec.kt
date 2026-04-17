package io.whozoss.agentos.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.InMemoryNamespaceRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * CRUD lifecycle contract tests for namespace in-memory persistence.
 *
 * Covers: create, read, update, soft-delete, [deleteByParent], idempotent delete,
 * stable identity, and ordering.
 *
 * Restart-survival semantics are verified by [Neo4jNamespacePersistenceSpec].
 */
class NamespacePersistenceLifecycleSpec : StringSpec({

    // =========================================================================
    // Full CRUD lifecycle
    // =========================================================================

    "full CRUD lifecycle: create, read, update, delete" {
        val repo = InMemoryNamespaceRepository()

        val ns = Namespace(
            metadata = EntityMetadata(),
            name = "engineering",
            description = "Engineering namespace",
        )
        val created = repo.save(ns)
        created.name shouldBe "engineering"
        created.description shouldBe "Engineering namespace"

        val found = repo.findByIds(listOf(created.metadata.id))
        found shouldHaveSize 1
        found.first().name shouldBe "engineering"

        val updated = created.copy(name = "engineering-v2", description = "Updated description")
        repo.save(updated)
        val afterUpdate = repo.findByIds(listOf(created.metadata.id)).first()
        afterUpdate.name shouldBe "engineering-v2"
        afterUpdate.description shouldBe "Updated description"

        repo.delete(created.metadata.id).shouldBeTrue()
        repo.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
    }

    // =========================================================================
    // Multiple namespaces
    // =========================================================================

    "multiple namespaces are all retrievable" {
        val repo = InMemoryNamespaceRepository()
        // InMemoryNamespaceRepository seeds a default namespace — clear the count baseline
        val before = repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).size

        val names = listOf("product", "engineering", "sales", "hr", "finance")
        val ids = names.map { repo.save(Namespace(metadata = EntityMetadata(), name = it)).metadata.id }

        val found = repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
        found shouldHaveSize before + 5
        found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
    }

    // =========================================================================
    // Soft-delete idempotence
    // =========================================================================

    "deleting an already-deleted namespace returns false" {
        val repo = InMemoryNamespaceRepository()
        val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "to-delete"))

        repo.delete(ns.metadata.id).shouldBeTrue()
        repo.delete(ns.metadata.id).shouldBeFalse()
    }

    // =========================================================================
    // deleteByParent cascade
    // =========================================================================

    "deleteByParent removes all namespaces" {
        val repo = InMemoryNamespaceRepository()
        repo.save(Namespace(metadata = EntityMetadata(), name = "ns-a"))
        repo.save(Namespace(metadata = EntityMetadata(), name = "ns-b"))
        repo.save(Namespace(metadata = EntityMetadata(), name = "ns-c"))

        // deleteByParent removes all including the seeded default
        val total = repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).size
        val deleted = repo.deleteByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
        deleted shouldBe total
        repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
    }

    // =========================================================================
    // configPath round-trip
    // =========================================================================

    "configPath is persisted and retrieved" {
        val repo = InMemoryNamespaceRepository()
        val ns = Namespace(
            metadata = EntityMetadata(),
            name = "coday-project",
            configPath = "/home/user/projects/coday",
        )
        val saved = repo.save(ns)
        saved.configPath shouldBe "/home/user/projects/coday"

        val found = repo.findByIds(listOf(saved.metadata.id)).first()
        found.configPath shouldBe "/home/user/projects/coday"

        val updated = saved.copy(configPath = null)
        repo.save(updated)
        val afterClear = repo.findByIds(listOf(saved.metadata.id)).first()
        afterClear.configPath shouldBe null
    }

    // =========================================================================
    // Stable identity
    // =========================================================================

    "namespace id and name are stable after creation" {
        val repo = InMemoryNamespaceRepository()
        val fixedId = UUID.randomUUID()
        repo.save(Namespace(metadata = EntityMetadata(id = fixedId), name = "stable-namespace"))

        val found = repo.findByIds(listOf(fixedId)).first()
        found.metadata.id shouldBe fixedId
        found.name shouldBe "stable-namespace"
    }
})
