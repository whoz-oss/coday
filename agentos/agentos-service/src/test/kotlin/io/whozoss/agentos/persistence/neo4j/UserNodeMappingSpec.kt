package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import java.util.UUID

/**
 * Unit tests for [UserNode] ↔ [User] mapping, focusing on `isRoot` property.
 *
 * Verifies that `isRoot` survives the domain → node → domain round-trip
 * and that the default value is `false`.
 */
class UserNodeMappingSpec : StringSpec({
    timeout = 5000

    "UserNode.fromDomain preserves isRoot = true" {
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "root@example.com",
            email = "root@example.com",
            isRoot = true,
        )

        val node = UserNode.fromDomain(user)

        node.isRoot shouldBe true
    }

    "UserNode.fromDomain preserves isRoot = false" {
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "regular@example.com",
            email = "regular@example.com",
            isRoot = false,
        )

        val node = UserNode.fromDomain(user)

        node.isRoot shouldBe false
    }

    "UserNode.toDomain preserves isRoot = true" {
        val node = UserNode(
            id = UUID.randomUUID().toString(),
            externalId = "root@example.com",
            email = "root@example.com",
            isRoot = true,
        )

        val user = node.toDomain()

        user.isRoot shouldBe true
    }

    "UserNode.toDomain preserves isRoot = false (default)" {
        val node = UserNode(
            id = UUID.randomUUID().toString(),
            externalId = "regular@example.com",
            email = "regular@example.com",
        )

        val user = node.toDomain()

        user.isRoot shouldBe false
    }

    "round-trip: User → UserNode → User preserves isRoot" {
        val original = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "roundtrip@example.com",
            email = "roundtrip@example.com",
            isRoot = true,
        )

        val roundTripped = UserNode.fromDomain(original).toDomain()

        roundTripped.isRoot shouldBe original.isRoot
    }
})
