package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

private val CONTEXT = mockk<ToolContext>(relaxed = true)

class ListAgentUsersToolSpec : StringSpec({

    val loader = mockk<(UUID) -> List<UserGroupMember>>()

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
        every { loader(agentId) } throws RuntimeException("DB error")
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "RETRIEVAL_ERROR"
        result.output shouldContain agentId.toString()
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    "execute returns empty structured output when no users are deployed" {
        val agentId = UUID.randomUUID()
        every { loader(agentId) } returns emptyList()
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe true
        result.structuredOutput!!.isArray shouldBe true
        result.structuredOutput!!.size() shouldBe 0
        result.output shouldContain "no users"
    }

    "execute returns all users across deployed groups deduplicated" {
        val agentId = UUID.randomUUID()
        val alice = member("alice@example.com", "ADMIN")
        val bob = member("bob@example.com")
        every { loader(agentId) } returns listOf(alice, bob)

        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe true
        val arr = result.structuredOutput!!
        arr.size() shouldBe 2
        arr[0]["externalId"].asText() shouldBe "alice@example.com"
        arr[1]["externalId"].asText() shouldBe "bob@example.com"
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
