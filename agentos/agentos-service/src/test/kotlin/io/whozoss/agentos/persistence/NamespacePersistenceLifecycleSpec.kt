package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.FilesystemNamespaceRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * End-to-end lifecycle test for namespace file-system persistence (WZ-30688).
 *
 * Validates the complete CRUD lifecycle using real file I/O and simulates
 * application restarts by creating fresh repository instances on the same directory.
 *
 * Acceptance criteria covered:
 * - A user can create a namespace
 * - A user can retrieve a namespace
 * - A user can modify a namespace (name and description persist)
 * - A user can delete a namespace
 * - Namespace data (ID, name) persists across restarts
 */
class NamespacePersistenceLifecycleSpec : StringSpec() {
    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun tmpDir(): Path = Files.createTempDirectory("agentos-namespace-lifecycle-test")

    init {

        // =========================================================================
        // Full CRUD lifecycle with persistence across "restarts"
        // =========================================================================

        "full CRUD lifecycle: create, read, update, delete — all survive restart" {
            val dataDir = tmpDir()

            // --- Session 1: CREATE ---
            val repo1 = FilesystemNamespaceRepository(dataDir, mapper)
            val ns =
                Namespace(
                    metadata = EntityMetadata(),
                    name = "engineering",
                    description = "Engineering namespace",
                )
            val created = repo1.save(ns)
            created.name shouldBe "engineering"
            created.description shouldBe "Engineering namespace"

            // --- Session 2: READ (simulated restart) ---
            val repo2 = FilesystemNamespaceRepository(dataDir, mapper)
            val found = repo2.findByIds(listOf(created.metadata.id))
            found shouldHaveSize 1
            found.first().metadata.id shouldBe created.metadata.id
            found.first().name shouldBe "engineering"

            // --- Session 3: UPDATE (rename + change description) ---
            val repo3 = FilesystemNamespaceRepository(dataDir, mapper)
            val updated = created.copy(name = "engineering-v2", description = "Updated description")
            repo3.save(updated)

            val repo4 = FilesystemNamespaceRepository(dataDir, mapper)
            val afterUpdate = repo4.findByIds(listOf(created.metadata.id)).first()
            afterUpdate.name shouldBe "engineering-v2"
            afterUpdate.description shouldBe "Updated description"

            // --- Session 4: DELETE ---
            val repo5 = FilesystemNamespaceRepository(dataDir, mapper)
            repo5.delete(created.metadata.id).shouldBeTrue()

            val repo6 = FilesystemNamespaceRepository(dataDir, mapper)
            repo6.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
            repo6.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        // =========================================================================
        // Multiple namespaces
        // =========================================================================

        "multiple namespaces are all retrievable after restart" {
            val dataDir = tmpDir()
            val repo1 = FilesystemNamespaceRepository(dataDir, mapper)

            val names = listOf("product", "engineering", "sales", "hr", "finance")
            val ids = names.map { repo1.save(Namespace(metadata = EntityMetadata(), name = it)).metadata.id }

            val repo2 = FilesystemNamespaceRepository(dataDir, mapper)
            val found = repo2.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            found shouldHaveSize 5
            found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
        }

        // =========================================================================
        // Soft-delete idempotence
        // =========================================================================

        "deleting an already-deleted namespace returns false" {
            val dataDir = tmpDir()
            val repo = FilesystemNamespaceRepository(dataDir, mapper)
            val ns = repo.save(Namespace(metadata = EntityMetadata(), name = "to-delete"))

            repo.delete(ns.metadata.id).shouldBeTrue()
            repo.delete(ns.metadata.id).shouldBeFalse()
        }

        // =========================================================================
        // deleteByParent cascade
        // =========================================================================

        "deleteByParent removes all namespaces" {
            val dataDir = tmpDir()
            val repo = FilesystemNamespaceRepository(dataDir, mapper)

            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-a"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-b"))
            repo.save(Namespace(metadata = EntityMetadata(), name = "ns-c"))

            val deleted = repo.deleteByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            deleted shouldBe 3
            repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        // =========================================================================
        // Namespace data available (ID and name) — acceptance criterion
        // =========================================================================

        "namespace id and name are stable after creation" {
            val dataDir = tmpDir()
            val repo1 = FilesystemNamespaceRepository(dataDir, mapper)

            val fixedId = UUID.randomUUID()
            val ns =
                Namespace(
                    metadata = EntityMetadata(id = fixedId),
                    name = "stable-namespace",
                )
            repo1.save(ns)

            val repo2 = FilesystemNamespaceRepository(dataDir, mapper)
            val found = repo2.findByIds(listOf(fixedId)).first()
            found.metadata.id shouldBe fixedId
            found.name shouldBe "stable-namespace"
        }
    }
}
