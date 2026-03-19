package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseEvent.FilesystemCaseEventRepository
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.time.Instant
import java.util.*

/**
 * Unit tests for [io.whozoss.agentos.caseEvent.FilesystemCaseEventRepository].
 *
 * Validates:
 * - Polymorphic (de)serialisation of [io.whozoss.agentos.sdk.caseEvent.CaseEvent] subtypes
 * - Ordering by timestamp within a case
 * - Persistence across repository restarts
 * - Soft-delete semantics
 */
class FilesystemCaseEventRepositoryTest : StringSpec() {
    val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun newRepo(dir: java.nio.file.Path) = FilesystemCaseEventRepository(dir, mapper)

    fun tmpDir(): java.nio.file.Path = Files.createTempDirectory("agentos-test-events")

    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()
    val userActor = Actor(id = "u1", displayName = "User", role = ActorRole.USER)

    fun msgEvent(
        timestamp: Instant = Instant.now(),
        cId: UUID = caseId,
    ) = MessageEvent(
        metadata = EntityMetadata(),
        namespaceId = namespaceId,
        caseId = cId,
        timestamp = timestamp,
        actor = userActor,
        content = listOf(MessageContent.Text("hello")),
    )

    fun warnEvent(timestamp: Instant = Instant.now()) =
        WarnEvent(
            metadata = EntityMetadata(),
            namespaceId = namespaceId,
            caseId = caseId,
            timestamp = timestamp,
            message = "something went wrong",
        )

    init {

        // -------------------------------------------------------------------------
        // Create / Read
        // -------------------------------------------------------------------------

        "save and findByIds returns the same event" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val e = repo.save(msgEvent())

            val found = repo.findByIds(listOf(e.metadata.id))
            found shouldHaveSize 1
            found.first().metadata.id shouldBe e.metadata.id
        }

        "findByParent returns events ordered by timestamp" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val t1 = Instant.ofEpochMilli(1000)
            val t2 = Instant.ofEpochMilli(2000)
            val t3 = Instant.ofEpochMilli(3000)

            // Insert out of order
            repo.save(msgEvent(t3))
            repo.save(msgEvent(t1))
            repo.save(msgEvent(t2))

            val found = repo.findByParent(caseId)
            found shouldHaveSize 3
            found[0].timestamp shouldBe t1
            found[1].timestamp shouldBe t2
            found[2].timestamp shouldBe t3
        }

        // -------------------------------------------------------------------------
        // Polymorphic serialisation
        // -------------------------------------------------------------------------

        "WarnEvent and MessageEvent are correctly deserialised" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            repo.save(msgEvent())
            repo.save(warnEvent())

            val found = repo.findByParent(caseId)
            found shouldHaveSize 2
            found.filterIsInstance<MessageEvent>() shouldHaveSize 1
            found.filterIsInstance<WarnEvent>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // Delete (soft)
        // -------------------------------------------------------------------------

        "delete soft-deletes an event" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val e = repo.save(msgEvent())

            repo.delete(e.metadata.id).shouldBeTrue()
            repo.findByIds(listOf(e.metadata.id)).shouldBeEmpty()
            repo.findByParent(caseId).shouldBeEmpty()
        }

        "delete returns illegalargumentexception for unknown id" {
            val dir = tmpDir()
            val repo = newRepo(dir)

            shouldThrow<IllegalArgumentException> {
                repo.delete(UUID.randomUUID())
            }
        }

        // -------------------------------------------------------------------------
        // Persistence across restarts
        // -------------------------------------------------------------------------

        "events persist across repository restarts" {
            val dir = tmpDir()
            val repo1 = newRepo(dir)
            val e1 = repo1.save(msgEvent(Instant.ofEpochMilli(1000)))
            val e2 = repo1.save(warnEvent(Instant.ofEpochMilli(2000)))

            val repo2 = newRepo(dir)
            val found = repo2.findByParent(caseId)

            found shouldHaveSize 2
            found[0].metadata.id shouldBe e1.metadata.id
            found[1].metadata.id shouldBe e2.metadata.id
        }

        "events for different cases are isolated" {
            val dir = tmpDir()
            val repo = newRepo(dir)
            val case2 = UUID.randomUUID()

            repo.save(msgEvent(cId = caseId))
            repo.save(msgEvent(cId = case2))

            repo.findByParent(caseId) shouldHaveSize 1
            repo.findByParent(case2) shouldHaveSize 1
        }
    }
}
