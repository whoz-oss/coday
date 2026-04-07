package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.FilesystemUserRepository
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * End-to-end lifecycle test for user file-system persistence (WZ-30778).
 *
 * Validates the complete CRUD lifecycle using real file I/O and simulates
 * application restarts by creating fresh repository instances on the same directory.
 *
 * Acceptance criteria covered:
 * - A user can be created and retrieved by ID
 * - A user can be found by external identity (email)
 * - A user can be updated (bio, firstname, lastname persist)
 * - A user can be soft-deleted
 * - User data (ID, email, externalId) persists across restarts
 */
class UserPersistenceLifecycleSpec : StringSpec() {
    private val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun tmpDir(): Path = Files.createTempDirectory("agentos-user-lifecycle-test")

    init {

        // =========================================================================
        // Full CRUD lifecycle with persistence across "restarts"
        // =========================================================================

        "full CRUD lifecycle: create, read, update, delete — all survive restart" {
            val dataDir = tmpDir()

            // --- Session 1: CREATE ---
            val repo1 = FilesystemUserRepository(dataDir, mapper)
            val user =
                User(
                    metadata = EntityMetadata(),
                    externalId = "alice@example.com",
                    email = "alice@example.com",
                    firstname = "Alice",
                    lastname = "Smith",
                    bio = "Platform engineer",
                )
            val created = repo1.save(user)
            created.email shouldBe "alice@example.com"
            created.firstname shouldBe "Alice"

            // --- Session 2: READ (simulated restart) ---
            val repo2 = FilesystemUserRepository(dataDir, mapper)
            val found = repo2.findByIds(listOf(created.metadata.id))
            found shouldHaveSize 1
            found.first().metadata.id shouldBe created.metadata.id
            found.first().email shouldBe "alice@example.com"
            found.first().bio shouldBe "Platform engineer"

            // --- Session 3: UPDATE bio ---
            val repo3 = FilesystemUserRepository(dataDir, mapper)
            val updated = created.copy(bio = "Staff engineer, loves Kotlin")
            repo3.save(updated)

            val repo4 = FilesystemUserRepository(dataDir, mapper)
            val afterUpdate = repo4.findByIds(listOf(created.metadata.id)).first()
            afterUpdate.bio shouldBe "Staff engineer, loves Kotlin"
            afterUpdate.firstname shouldBe "Alice"

            // --- Session 4: DELETE ---
            val repo5 = FilesystemUserRepository(dataDir, mapper)
            repo5.delete(created.metadata.id).shouldBeTrue()

            val repo6 = FilesystemUserRepository(dataDir, mapper)
            repo6.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
            repo6.findByParent(UserRepository.USER_PARENT_KEY).shouldBeEmpty()
        }

        // =========================================================================
        // findByExternalId — identity resolution
        // =========================================================================

        "findByExternalId returns the correct user" {
            val dataDir = tmpDir()
            val repo = FilesystemUserRepository(dataDir, mapper)

            repo.save(User(externalId = "alice@example.com", email = "alice@example.com", firstname = "Alice"))
            repo.save(User(externalId = "bob@example.com", email = "bob@example.com", firstname = "Bob"))

            val found = repo.findByExternalId("alice@example.com")
            found.shouldNotBeNull()
            found.firstname shouldBe "Alice"
        }

        "findByExternalId returns null for unknown external id" {
            val dataDir = tmpDir()
            val repo = FilesystemUserRepository(dataDir, mapper)
            repo.findByExternalId("nobody@example.com").shouldBeNull()
        }

        "findByExternalId returns null after user is soft-deleted" {
            val dataDir = tmpDir()
            val repo = FilesystemUserRepository(dataDir, mapper)
            val user = repo.save(User(externalId = "gone@example.com", email = "gone@example.com"))
            repo.delete(user.metadata.id)
            repo.findByExternalId("gone@example.com").shouldBeNull()
        }

        "findByExternalId survives a restart" {
            val dataDir = tmpDir()
            val repo1 = FilesystemUserRepository(dataDir, mapper)
            repo1.save(User(externalId = "persist@example.com", email = "persist@example.com", firstname = "Persist"))

            val repo2 = FilesystemUserRepository(dataDir, mapper)
            val found = repo2.findByExternalId("persist@example.com")
            found.shouldNotBeNull()
            found.firstname shouldBe "Persist"
        }

        // =========================================================================
        // Multiple users
        // =========================================================================

        "multiple users are all retrievable after restart" {
            val dataDir = tmpDir()
            val repo1 = FilesystemUserRepository(dataDir, mapper)

            val emails = listOf("a@x.com", "b@x.com", "c@x.com")
            val ids = emails.map { repo1.save(User(externalId = it, email = it)).metadata.id }

            val repo2 = FilesystemUserRepository(dataDir, mapper)
            val found = repo2.findByParent(UserRepository.USER_PARENT_KEY)
            found shouldHaveSize 3
            found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
        }

        // =========================================================================
        // Soft-delete idempotence
        // =========================================================================

        "deleting an already-deleted user returns false" {
            val dataDir = tmpDir()
            val repo = FilesystemUserRepository(dataDir, mapper)
            val user = repo.save(User(externalId = "del@x.com", email = "del@x.com"))

            repo.delete(user.metadata.id).shouldBeTrue()
            repo.delete(user.metadata.id).shouldBeFalse()
        }

        // =========================================================================
        // Stable identity
        // =========================================================================

        "user id and externalId are stable after creation" {
            val dataDir = tmpDir()
            val repo1 = FilesystemUserRepository(dataDir, mapper)

            val fixedId = UUID.randomUUID()
            repo1.save(
                User(
                    metadata = EntityMetadata(id = fixedId),
                    externalId = "stable@example.com",
                    email = "stable@example.com",
                ),
            )

            val repo2 = FilesystemUserRepository(dataDir, mapper)
            val found = repo2.findByIds(listOf(fixedId)).first()
            found.metadata.id shouldBe fixedId
            found.externalId shouldBe "stable@example.com"
        }
    }
}
