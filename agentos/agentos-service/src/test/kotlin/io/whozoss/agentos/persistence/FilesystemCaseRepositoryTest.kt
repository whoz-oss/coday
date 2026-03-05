package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.FilesystemCaseRepository
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.util.*

/**
 * Unit tests for [FilesystemCaseRepository].
 *
 * Each test uses an isolated temporary directory so tests do not interfere
 * with each other and no cleanup is needed at the project level.
 *
 * These tests validate:
 * - CRUD lifecycle with real file I/O
 * - Persistence across repository instance restarts (simulated by creating a
 *   new [io.whozoss.agentos.caseFlow.FilesystemCaseRepository] pointing at the same directory)
 * - Soft-delete semantics
 * - Parent-scoped queries
 */
class FilesystemCaseRepositoryTest : StringSpec() {
    val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()

    fun newRepo(dir: java.nio.file.Path) = FilesystemCaseRepository(dir, mapper)

    fun tmpDir(): java.nio.file.Path = Files.createTempDirectory("agentos-test-cases")

    fun case(projectId: UUID = UUID.randomUUID()): Case =
        Case(
            metadata = EntityMetadata(),
            projectId = projectId,
            status = CaseStatus.PENDING,
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "save and findByIds returns the same case" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val c = case()

            val saved = repo.save(c)
            val found = repo.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().metadata.id shouldBe saved.metadata.id
            found.first().projectId shouldBe c.projectId
            found.first().status shouldBe CaseStatus.PENDING
        }

        "findByIds returns empty list for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.findByIds(listOf(UUID.randomUUID())).shouldBeEmpty()
        }

        "findByParent returns all cases for a project" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val projectId = UUID.randomUUID()

            repo.save(case(projectId))
            repo.save(case(projectId))
            repo.save(case(UUID.randomUUID())) // different project — must not appear

            val found = repo.findByParent(projectId)
            found shouldHaveSize 2
            found.all { it.projectId == projectId }.shouldBeTrue()
        }

        "findByParent returns empty list when no cases exist for project" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            repo.findByParent(UUID.randomUUID()).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Update
        // -------------------------------------------------------------------------

        "save updates an existing case" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val c = case()
            val saved = repo.save(c)

            val updated = saved.copy(status = CaseStatus.RUNNING)
            repo.save(updated)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            found.status shouldBe CaseStatus.RUNNING
        }

        // -------------------------------------------------------------------------
        // Delete (soft)
        // -------------------------------------------------------------------------

        "delete soft-deletes a case" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val c = repo.save(case())

            repo.delete(c.metadata.id).shouldBeTrue()

            // Must not appear in queries
            repo.findByIds(listOf(c.metadata.id)).shouldBeEmpty()
            repo.findByParent(c.projectId).shouldBeEmpty()
        }

        "delete returns illegalargumentexception for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            shouldThrow<IllegalArgumentException> { repo.delete(UUID.randomUUID()) }
        }

        "deleteByParent soft-deletes all cases for a project" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val projectId = UUID.randomUUID()
            repo.save(case(projectId))
            repo.save(case(projectId))
            val other = repo.save(case()) // different project

            val count = repo.deleteByParent(projectId)
            count shouldBe 2
            repo.findByParent(projectId).shouldBeEmpty()
            // Other project's case is untouched
            repo.findByParent(other.projectId) shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Persistence across restarts
        // -------------------------------------------------------------------------

        "data persists across repository restarts" {
            val dir = tmpDir()
            val projectId = UUID.randomUUID()

            // Write with first instance
            val repo1 = newRepo(dir)
            val saved = repo1.save(case(projectId))

            // Read with a fresh instance pointing at the same directory
            val repo2 = newRepo(dir)
            val found = repo2.findByIds(listOf(saved.metadata.id))

            found shouldHaveSize 1
            found.first().metadata.id shouldBe saved.metadata.id
            found.first().projectId shouldBe projectId
        }

        "soft-delete persists across repository restarts" {
            val dir = tmpDir()
            val repo1 = newRepo(dir)
            val c = repo1.save(case())
            repo1.delete(c.metadata.id)

            val repo2 = newRepo(dir)
            repo2.findByIds(listOf(c.metadata.id)).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // createdBy (ownership tracking)
        // -------------------------------------------------------------------------

        "createdBy is persisted and retrieved" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val c =
                case().copy(
                    metadata = EntityMetadata(createdBy = "agent-abc"),
                )
            val saved = repo.save(c)

            val found = repo.findByIds(listOf(saved.metadata.id)).first()
            found.metadata.createdBy shouldBe "agent-abc"
        }
    }
}
