package io.whozoss.agentos.namespace

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.util.UUID

/**
 * Unit tests for [FilesystemNamespaceRepository].
 *
 * Each test uses an isolated temporary directory to avoid inter-test interference.
 */
class FilesystemNamespaceRepositoryTest : StringSpec() {
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    fun newRepo(dir: java.nio.file.Path) = FilesystemNamespaceRepository(dir, mapper)

    fun tmpDir(): java.nio.file.Path = Files.createTempDirectory("agentos-test-namespaces")

    fun namespace(name: String = "ns-${UUID.randomUUID()}"): Namespace =
        Namespace(
            metadata = EntityMetadata(),
            name = name,
            description = "Description for $name",
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "save and findByIds returns the same namespace" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val ns = namespace("my-namespace")

            val saved = repo.save(ns)
            val found = repo.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().metadata.id shouldBe saved.metadata.id
            found.first().name shouldBe "my-namespace"
            found.first().description shouldBe "Description for my-namespace"
        }

        "findByIds returns empty list for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.findByIds(listOf(UUID.randomUUID())).shouldBeEmpty()
        }

        "findByParent with NAMESPACE_PARENT_KEY returns all namespaces" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.save(namespace("alpha"))
            repo.save(namespace("beta"))
            repo.save(namespace("gamma"))

            val found = repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            found shouldHaveSize 3
        }

        "findByParent returns namespaces sorted by name" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.save(namespace("charlie"))
            repo.save(namespace("alpha"))
            repo.save(namespace("bravo"))

            val found = repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            found.map { it.name } shouldBe listOf("alpha", "bravo", "charlie")
        }

        "findByParent returns empty list when no namespaces exist" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "save updates an existing namespace" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val ns = namespace("original")
            val saved = repo.save(ns)

            val updated = saved.copy(name = "renamed", description = "updated description")
            repo.save(updated)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            found.name shouldBe "renamed"
            found.description shouldBe "updated description"
        }

        // -------------------------------------------------------------------------
        // Delete (soft)
        // -------------------------------------------------------------------------

        "delete soft-deletes a namespace" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val ns = repo.save(namespace())

            repo.delete(ns.metadata.id).shouldBeTrue()

            repo.findByIds(listOf(ns.metadata.id)).shouldBeEmpty()
            repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        "delete returns IllegalArgumentException for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            shouldThrow<IllegalArgumentException> { repo.delete(UUID.randomUUID()) }
        }

        "deleteByParent soft-deletes all namespaces" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.save(namespace("ns-1"))
            repo.save(namespace("ns-2"))

            val count = repo.deleteByParent(NamespaceRepository.NAMESPACE_PARENT_KEY)
            count shouldBe 2
            repo.findByParent(NamespaceRepository.NAMESPACE_PARENT_KEY).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Persistence across restarts
        // -------------------------------------------------------------------------

        "data persists across repository restarts" {
            val dir = tmpDir()

            val repo1 = newRepo(dir)
            val saved = repo1.save(namespace("persistent-ns"))

            val repo2 = newRepo(dir)
            val found = repo2.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().metadata.id shouldBe saved.metadata.id
            found.first().name shouldBe "persistent-ns"
        }

        "soft-delete persists across repository restarts" {
            val dir = tmpDir()
            val repo1 = newRepo(dir)
            val ns = repo1.save(namespace())
            repo1.delete(ns.metadata.id)

            val repo2 = newRepo(dir)
            repo2.findByIds(listOf(ns.metadata.id)).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Optional description field
        // -------------------------------------------------------------------------

        "namespace with null description is persisted and retrieved correctly" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val ns = Namespace(metadata = EntityMetadata(), name = "no-description", description = null)
            val saved = repo.save(ns)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            found.description shouldBe null
        }
    }
}
