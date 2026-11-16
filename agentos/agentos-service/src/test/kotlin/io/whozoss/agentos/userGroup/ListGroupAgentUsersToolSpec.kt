package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

private val NAMESPACE_ID: UUID = UUID.randomUUID()
private val USER_ID: UUID = UUID.randomUUID()
private val CONTEXT = mockk<ToolContext>(relaxed = true).also {
    every { it.namespaceId } returns NAMESPACE_ID
    every { it.userId } returns USER_ID
}

class ListGroupAgentUsersToolSpec : StringSpec({

    val loader = mockk<(UUID, UUID, UUID, UUID?) -> List<UserGroupMember>?>()

    fun tool() = ListGroupAgentUsersTool(membersByGroupAndAgentLoader = loader)

    fun member(externalId: String, role: String = "MEMBER") = UserGroupMember(
        userId = UUID.randomUUID(),
        externalId = externalId,
        role = role,
        email = null,
        firstname = null,
        lastname = null,
    )

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    "execute returns MISSING_INPUT when input is null" {
        val result = tool().execute(null, CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "MISSING_INPUT"
    }

    "execute returns RETRIEVAL_ERROR when loader throws" {
        val groupId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        every { loader(groupId, agentId, NAMESPACE_ID, USER_ID) } throws RuntimeException("DB error")
        val result = tool().execute(ListGroupAgentUsersTool.Input(groupId, agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "RETRIEVAL_ERROR"
        if (result.output.contains("DB error")) throw AssertionError("Exception message leaked into output")
    }

    "execute returns NOT_FOUND when loader returns null (group missing or no access)" {
        val groupId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        every { loader(groupId, agentId, NAMESPACE_ID, USER_ID) } returns null
        val result = tool().execute(ListGroupAgentUsersTool.Input(groupId, agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }

    // -------------------------------------------------------------------------
    // Agent not deployed to group — empty list, not an error
    // -------------------------------------------------------------------------

    "execute returns success with empty list when agent is not deployed to group" {
        val groupId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        every { loader(groupId, agentId, NAMESPACE_ID, USER_ID) } returns emptyList()
        val result = tool().execute(ListGroupAgentUsersTool.Input(groupId, agentId), CONTEXT)
        result.success shouldBe true
        result.structuredOutput!!.isArray shouldBe true
        result.structuredOutput!!.size() shouldBe 0
        result.output shouldContain "No member of group"
    }

    // -------------------------------------------------------------------------
    // Happy path — agent deployed, members returned
    // -------------------------------------------------------------------------

    "execute returns group members when agent is deployed" {
        val groupId = UUID.randomUUID()
        val agentId = UUID.randomUUID()
        val alice = member("alice@example.com", "ADMIN")
        val bob = member("bob@example.com")
        every { loader(groupId, agentId, NAMESPACE_ID, USER_ID) } returns listOf(alice, bob)

        val result = tool().execute(ListGroupAgentUsersTool.Input(groupId, agentId), CONTEXT)
        result.success shouldBe true
        val arr = result.structuredOutput!!
        arr.size() shouldBe 2
        arr[0]["externalId"].asText() shouldBe "alice@example.com"
        arr[1]["externalId"].asText() shouldBe "bob@example.com"
        result.output shouldContain "2 member"
    }

    // -------------------------------------------------------------------------
    // Name convention
    // -------------------------------------------------------------------------

    "bare name is 'listGroupAgentUsers'" {
        ListGroupAgentUsersTool(configName = null, membersByGroupAndAgentLoader = loader).name shouldBe "listGroupAgentUsers"
    }

    "prefixed name is 'USER_GROUP__listGroupAgentUsers'" {
        ListGroupAgentUsersTool(configName = "USER_GROUP", membersByGroupAndAgentLoader = loader).name shouldBe "USER_GROUP__listGroupAgentUsers"
    }
})
