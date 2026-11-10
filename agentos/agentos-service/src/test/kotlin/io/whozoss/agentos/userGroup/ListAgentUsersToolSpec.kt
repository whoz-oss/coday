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

class ListAgentUsersToolSpec : StringSpec({

    val loader = mockk<(UUID, UUID, UUID?) -> List<UserGroupMember>?>()

    fun tool() = ListAgentUsersTool(usersByAgentLoader = loader)

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
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } throws RuntimeException("DB error")
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "RETRIEVAL_ERROR"
        if (result.output.contains("DB error")) throw AssertionError("Exception message leaked into output")
    }

    "execute returns NOT_FOUND when loader returns null (no access)" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns null
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    "execute returns empty targets when no users are deployed" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns emptyList()
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe true
        result.structuredOutput!!["targets"].size() shouldBe 0
        result.structuredOutput!!["members"].size() shouldBe 0
        result.output shouldContain "no users"
    }

    "execute returns all users across deployed groups, as workflow targets and as member rows" {
        val agentId = UUID.randomUUID()
        val alice = member("alice@example.com", "ADMIN")
        val bob = member("bob@example.com")
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns listOf(alice, bob)

        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe true
        val targets = result.structuredOutput!!["targets"]
        targets.map { it["userExternalId"].asText() } shouldBe listOf("alice@example.com", "bob@example.com")
        targets[0].has("entityId") shouldBe false
        val members = result.structuredOutput!!["members"]
        members[0]["externalId"].asText() shouldBe "alice@example.com"
        members[0]["role"].asText() shouldBe "ADMIN"
        result.output shouldContain "2 user"
    }

    // -------------------------------------------------------------------------
    // Name convention
    // -------------------------------------------------------------------------

    "bare name is 'listAgentUsers'" {
        ListAgentUsersTool(configName = null, usersByAgentLoader = loader).name shouldBe "listAgentUsers"
    }

    "prefixed name is 'USER_GROUP__listAgentUsers'" {
        ListAgentUsersTool(configName = "USER_GROUP", usersByAgentLoader = loader).name shouldBe "USER_GROUP__listAgentUsers"
    }
})
