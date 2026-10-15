package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

private val CONTEXT = mockk<ToolContext>(relaxed = true)

class ListUserGroupMembersToolSpec : StringSpec({

    val membersLoader = mockk<(UUID) -> List<UserGroupMember>>()

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    "execute returns error when input is null" {
        val tool = ListUserGroupMembersTool(membersLoader = membersLoader)
        val result = tool.execute(null, CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "MISSING_INPUT"
        result.output shouldContain "userGroupId"
    }

    "execute returns error when loader throws" {
        val groupId = UUID.randomUUID()
        every { membersLoader(groupId) } throws RuntimeException("DB unavailable")

        val tool = ListUserGroupMembersTool(membersLoader = membersLoader)
        val result = tool.execute(ListUserGroupMembersTool.Input(groupId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "RETRIEVAL_ERROR"
        result.output shouldContain groupId.toString()
    }

    // -------------------------------------------------------------------------
    // Happy path: empty group
    // -------------------------------------------------------------------------

    "execute returns success with empty list when group has no members" {
        val groupId = UUID.randomUUID()
        every { membersLoader(groupId) } returns emptyList()

        val tool = ListUserGroupMembersTool(membersLoader = membersLoader)
        val result = tool.execute(ListUserGroupMembersTool.Input(groupId), CONTEXT)
        result.success shouldBe true
        result.output shouldContain "no members"
        result.structuredOutput!!.isArray shouldBe true
        result.structuredOutput!!.size() shouldBe 0
    }

    // -------------------------------------------------------------------------
    // Happy path: group with members
    // -------------------------------------------------------------------------

    "execute returns member list in structured output" {
        val groupId = UUID.randomUUID()
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        val members = listOf(
            UserGroupMember(userId = userId1, externalId = "alice@example.com", role = "ADMIN", email = "alice@example.com", firstname = "Alice", lastname = "Smith"),
            UserGroupMember(userId = userId2, externalId = "bob@example.com",   role = "MEMBER", email = null, firstname = null, lastname = null),
        )
        every { membersLoader(groupId) } returns members

        val tool = ListUserGroupMembersTool(membersLoader = membersLoader)
        val result = tool.execute(ListUserGroupMembersTool.Input(groupId), CONTEXT)

        result.success shouldBe true
        result.output shouldContain "2 member"
        result.output shouldContain "alice@example.com"
        result.output shouldContain "bob@example.com"

        val structured = result.structuredOutput!!
        structured.isArray shouldBe true
        structured.size() shouldBe 2

        val alice = structured[0]
        alice["userId"].asText() shouldBe userId1.toString()
        alice["externalId"].asText() shouldBe "alice@example.com"
        alice["role"].asText() shouldBe "ADMIN"
        alice["email"].asText() shouldBe "alice@example.com"
        alice["firstname"].asText() shouldBe "Alice"

        val bob = structured[1]
        bob["userId"].asText() shouldBe userId2.toString()
        bob["externalId"].asText() shouldBe "bob@example.com"
        bob["role"].asText() shouldBe "MEMBER"
        bob["email"].isNull shouldBe true
    }

    // -------------------------------------------------------------------------
    // Tool name convention
    // -------------------------------------------------------------------------

    "name is bare 'listMembers' when configName is null" {
        ListUserGroupMembersTool(configName = null, membersLoader = membersLoader).name shouldBe "listMembers"
    }

    "name is prefixed when configName is provided" {
        ListUserGroupMembersTool(configName = "USER_GROUP", membersLoader = membersLoader).name shouldBe
            "USER_GROUP__listMembers"
    }

    // -------------------------------------------------------------------------
    // outputSchema
    // -------------------------------------------------------------------------

    "outputSchema is a non-null array schema" {
        val schema = ListUserGroupMembersTool(membersLoader = membersLoader).outputSchema()!!
        schema["type"].asText() shouldBe "array"
        schema["items"]["required"].map { it.asText() } shouldBe listOf("userId", "externalId", "role")
    }
})
