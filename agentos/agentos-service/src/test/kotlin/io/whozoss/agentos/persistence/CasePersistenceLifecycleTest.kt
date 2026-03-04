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
import io.whozoss.agentos.caseEvent.FilesystemCaseEventRepository
import io.whozoss.agentos.caseFlow.CaseModel
import io.whozoss.agentos.caseFlow.FilesystemCaseRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * End-to-end lifecycle test for file-system persistence (WZ-28667).
 *
 * Validates the complete CRUD lifecycle using real file I/O and simulates
 * an application restart by creating a fresh repository instance pointing
 * at the same directory.
 *
 * Acceptance criteria covered:
 * - Agents can create a case and retrieve it in a new session.
 * - Agents can read their own cases.
 * - Agents can update their case data.
 * - Agents can delete their cases.
 * - Case data persists across application restarts.
 */
class CasePersistenceLifecycleTest : StringSpec() {
    val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun tmpDir(): Path = Files.createTempDirectory("agentos-e2e")!!

    init {

        // =========================================================================
        // Full CRUD lifecycle with persistence across "restarts"
        // =========================================================================

        "full CRUD lifecycle: create, read, update, delete — all survive restart" {
            val dataDir = tmpDir()
            val projectId = UUID.randomUUID()
            val agentId = "agent-lifecycle-test"

            // --- Session 1: CREATE ---
            val caseRepo1 = FilesystemCaseRepository(dataDir, mapper)
            val caseModel =
                CaseModel(
                    metadata = EntityMetadata(createdBy = agentId),
                    projectId = projectId,
                    status = CaseStatus.PENDING,
                )
            val created = caseRepo1.save(caseModel)
            created.metadata.createdBy shouldBe agentId
            created.projectId shouldBe projectId

            // --- Session 2: READ (simulated restart) ---
            val caseRepo2 = FilesystemCaseRepository(dataDir, mapper)
            val found = caseRepo2.findByIds(listOf(created.metadata.id))
            found shouldHaveSize 1
            found.first().metadata.id shouldBe created.metadata.id
            found.first().metadata.createdBy shouldBe agentId
            found.first().status shouldBe CaseStatus.PENDING

            // --- Session 3: UPDATE ---
            val caseRepo3 = FilesystemCaseRepository(dataDir, mapper)
            val updated = created.copy(status = CaseStatus.RUNNING)
            caseRepo3.save(updated)

            val caseRepo4 = FilesystemCaseRepository(dataDir, mapper)
            val afterUpdate = caseRepo4.findByIds(listOf(created.metadata.id)).first()
            afterUpdate.status shouldBe CaseStatus.RUNNING

            // --- Session 4: DELETE ---
            val caseRepo5 = FilesystemCaseRepository(dataDir, mapper)
            caseRepo5.delete(created.metadata.id).shouldBeTrue()

            val caseRepo6 = FilesystemCaseRepository(dataDir, mapper)
            caseRepo6.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
        }

        // =========================================================================
        // Events persist alongside cases
        // =========================================================================

        "case events persist across restarts" {
            val dataDir = tmpDir()
            val projectId = UUID.randomUUID()
            val caseId = UUID.randomUUID()

            val eventRepo1 = FilesystemCaseEventRepository(dataDir, mapper)
            val event =
                MessageEvent(
                    metadata = EntityMetadata(),
                    projectId = projectId,
                    caseId = caseId,
                    actor = Actor(id = "u1", displayName = "User", role = ActorRole.USER),
                    content = listOf(MessageContent.Text("hello from session 1")),
                )
            eventRepo1.save(event)

            // Simulated restart
            val eventRepo2 = FilesystemCaseEventRepository(dataDir, mapper)
            val found = eventRepo2.findByParent(caseId)
            found shouldHaveSize 1
            found.first().metadata.id shouldBe event.metadata.id
        }

        // =========================================================================
        // Multiple cases per namespace
        // =========================================================================

        "multiple cases in same namespace are all retrievable after restart" {
            val dataDir = tmpDir()
            val projectId = UUID.randomUUID()
            val repo1 = FilesystemCaseRepository(dataDir, mapper)

            val ids =
                (1..5).map {
                    repo1
                        .save(
                            CaseModel(
                                metadata = EntityMetadata(createdBy = "agent-$it"),
                                projectId = projectId,
                            ),
                        ).metadata.id
                }

            val repo2 = FilesystemCaseRepository(dataDir, mapper)
            val found = repo2.findByParent(projectId)
            found shouldHaveSize 5
            found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
        }

        // =========================================================================
        // Isolation between namespaces
        // =========================================================================

        "cases in different namespaces are isolated" {
            val dataDir = tmpDir()
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            val repo = FilesystemCaseRepository(dataDir, mapper)

            repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns1))
            repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns1))
            repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns2))

            repo.findByParent(ns1) shouldHaveSize 2
            repo.findByParent(ns2) shouldHaveSize 1
        }

        // =========================================================================
        // deleteByParent cascade
        // =========================================================================

        "deleteByParent removes all cases in a namespace without touching others" {
            val dataDir = tmpDir()
            val ns1 = UUID.randomUUID()
            val ns2 = UUID.randomUUID()
            val repo = FilesystemCaseRepository(dataDir, mapper)

            repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns1))
            repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns1))
            val survivor = repo.save(CaseModel(metadata = EntityMetadata(), projectId = ns2))

            val deleted = repo.deleteByParent(ns1)
            deleted shouldBe 2
            repo.findByParent(ns1).shouldBeEmpty()
            repo.findByParent(ns2) shouldHaveSize 1
            repo
                .findByParent(ns2)
                .first()
                .metadata.id shouldBe survivor.metadata.id
        }

        // =========================================================================
        // double-delete is idempotent
        // =========================================================================

        "deleting an already-deleted case returns false" {
            val dataDir = tmpDir()
            val repo = FilesystemCaseRepository(dataDir, mapper)
            val c = repo.save(CaseModel(metadata = EntityMetadata(), projectId = UUID.randomUUID()))

            repo.delete(c.metadata.id).shouldBeTrue()
            repo.delete(c.metadata.id).shouldBeFalse()
        }
    }
}
