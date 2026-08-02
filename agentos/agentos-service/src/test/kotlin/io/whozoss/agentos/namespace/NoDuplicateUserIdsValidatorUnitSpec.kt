package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.api.user.UserMembershipRole
import jakarta.validation.ConstraintValidatorContext
import java.util.UUID

class NoDuplicateUserIdsValidatorUnitSpec : StringSpec({

    val validator = NoDuplicateUserIdsValidator()
    val ctx = mockk<ConstraintValidatorContext>(relaxed = true)

    "null list is valid" {
        validator.isValid(null, ctx) shouldBe true
    }

    "empty list is valid" {
        validator.isValid(emptyList(), ctx) shouldBe true
    }

    "single entry is valid" {
        validator.isValid(listOf(UserMembershipRole(UUID.randomUUID(), "ADMIN")), ctx) shouldBe true
    }

    "distinct userIds are valid" {
        val entries = listOf(
            UserMembershipRole(UUID.randomUUID(), "ADMIN"),
            UserMembershipRole(UUID.randomUUID(), null),
        )
        validator.isValid(entries, ctx) shouldBe true
    }

    "duplicate userId is invalid" {
        val id = UUID.randomUUID()
        val entries = listOf(
            UserMembershipRole(id, "ADMIN"),
            UserMembershipRole(id, "MEMBER"),
        )
        validator.isValid(entries, ctx) shouldBe false
    }

    "duplicate userId with null role is invalid" {
        val id = UUID.randomUUID()
        val entries = listOf(
            UserMembershipRole(id, "ADMIN"),
            UserMembershipRole(id, null),
        )
        validator.isValid(entries, ctx) shouldBe false
    }
})
