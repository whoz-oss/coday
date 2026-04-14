package io.whozoss.agentos.persistence

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.InMemoryUserRepository
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import java.util.UUID

/**
 * CRUD lifecycle contract tests for user in-memory persistence.
 *
 * Covers: create, read, update, soft-delete, [findByExternalId], idempotent
 * delete, multiple users, and stable identity.
 *
 * Restart-survival semantics are verified by the Neo4j integration specs.
 */
class UserPersistenceLifecycleSpec : StringSpec({

    // =========================================================================
    // Full CRUD lifecycle
    // =========================================================================

    "full CRUD lifecycle: create, read, update, delete" {
        val repo = InMemoryUserRepository()

        val user = User(
            metadata = EntityMetadata(),
            externalId = "alice@example.com",
            email = "alice@example.com",
            firstname = "Alice",
            lastname = "Smith",
            bio = "Platform engineer",
        )
        val created = repo.save(user)
        created.email shouldBe "alice@example.com"
        created.firstname shouldBe "Alice"

        val found = repo.findByIds(listOf(created.metadata.id))
        found shouldHaveSize 1
        found.first().bio shouldBe "Platform engineer"

        val updated = created.copy(bio = "Staff engineer, loves Kotlin")
        repo.save(updated)
        val afterUpdate = repo.findByIds(listOf(created.metadata.id)).first()
        afterUpdate.bio shouldBe "Staff engineer, loves Kotlin"
        afterUpdate.firstname shouldBe "Alice"

        repo.delete(created.metadata.id).shouldBeTrue()
        repo.findByIds(listOf(created.metadata.id)).shouldBeEmpty()
        repo.findByParent(UserRepository.USER_PARENT_KEY).shouldBeEmpty()
    }

    // =========================================================================
    // findByExternalId — identity resolution
    // =========================================================================

    "findByExternalId returns the correct user" {
        val repo = InMemoryUserRepository()
        repo.save(User(externalId = "alice@example.com", email = "alice@example.com", firstname = "Alice"))
        repo.save(User(externalId = "bob@example.com", email = "bob@example.com", firstname = "Bob"))

        val found = repo.findByExternalId("alice@example.com")
        found.shouldNotBeNull()
        found.firstname shouldBe "Alice"
    }

    "findByExternalId returns null for unknown external id" {
        InMemoryUserRepository().findByExternalId("nobody@example.com").shouldBeNull()
    }

    "findByExternalId returns null after user is soft-deleted" {
        val repo = InMemoryUserRepository()
        val user = repo.save(User(externalId = "gone@example.com", email = "gone@example.com"))
        repo.delete(user.metadata.id)
        repo.findByExternalId("gone@example.com").shouldBeNull()
    }

    // =========================================================================
    // Multiple users
    // =========================================================================

    "multiple users are all retrievable" {
        val repo = InMemoryUserRepository()
        val emails = listOf("a@x.com", "b@x.com", "c@x.com")
        val ids = emails.map { repo.save(User(externalId = it, email = it)).metadata.id }

        val found = repo.findByParent(UserRepository.USER_PARENT_KEY)
        found shouldHaveSize 3
        found.map { it.metadata.id }.containsAll(ids).shouldBeTrue()
    }

    // =========================================================================
    // Soft-delete idempotence
    // =========================================================================

    "deleting an already-deleted user returns false" {
        val repo = InMemoryUserRepository()
        val user = repo.save(User(externalId = "del@x.com", email = "del@x.com"))

        repo.delete(user.metadata.id).shouldBeTrue()
        repo.delete(user.metadata.id).shouldBeFalse()
    }

    // =========================================================================
    // isRoot property
    // =========================================================================

    "user isRoot defaults to false" {
        val user = User(externalId = "default@example.com", email = "default@example.com")
        user.isRoot shouldBe false
    }

    "user isRoot is preserved through save and retrieval" {
        val repo = InMemoryUserRepository()
        val user = repo.save(User(externalId = "root@example.com", email = "root@example.com", isRoot = true))
        user.isRoot shouldBe true

        val found = repo.findByIds(listOf(user.metadata.id)).first()
        found.isRoot shouldBe true
    }

    // =========================================================================
    // Stable identity
    // =========================================================================

    "user id and externalId are stable after creation" {
        val repo = InMemoryUserRepository()
        val fixedId = UUID.randomUUID()
        repo.save(User(
            metadata = EntityMetadata(id = fixedId),
            externalId = "stable@example.com",
            email = "stable@example.com",
        ))

        val found = repo.findByIds(listOf(fixedId)).first()
        found.metadata.id shouldBe fixedId
        found.externalId shouldBe "stable@example.com"
    }
})
