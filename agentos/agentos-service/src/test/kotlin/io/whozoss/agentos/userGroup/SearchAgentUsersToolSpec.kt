package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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

class SearchAgentUsersToolSpec : StringSpec({

    val loader = mockk<(UUID, UUID, UUID?) -> List<UserGroupMember>?>()

    fun tool() = SearchAgentUsersTool(usersByAgentLoader = loader)

    fun member(externalId: String, role: String = "MEMBER") = UserGroupMember(
        userId = UUID.randomUUID(),
        externalId = externalId,
        role = role,
        email = null,
        firstname = null,
        lastname = null,
    )

    // -------------------------------------------------------------------------
    // Errors are those of ListAgentUsersTool, passed through
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
        result.structuredOutput shouldBe null
    }

    "execute returns NOT_FOUND when loader returns null (no access)" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns null
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe false
        result.errorType shouldBe "NOT_FOUND"
    }

    // -------------------------------------------------------------------------
    // Search result contract
    // -------------------------------------------------------------------------

    "execute returns an empty search result when no users are deployed" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns emptyList()
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.success shouldBe true
        val structured = result.structuredOutput!!
        structured["data"].isArray shouldBe true
        structured["data"].size() shouldBe 0
        structured["metadata"]["totalCount"].asInt() shouldBe 0
        structured["metadata"]["next"].isNull shouldBe true
    }

    "execute returns one hit per user, targeting that user by external id" {
        val agentId = UUID.randomUUID()
        val alice = member("alice@example.com", "ADMIN")
        val bob = member("bob@example.com")
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns listOf(alice, bob)

        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)

        result.success shouldBe true
        val structured = result.structuredOutput!!
        val data = structured["data"]
        data.size() shouldBe 2
        structured["metadata"]["totalCount"].asInt() shouldBe 2

        data[0]["entityType"].asText() shouldBe "User"
        data[0]["entityId"].asText() shouldBe alice.userId.toString()
        data[0]["targets"].size() shouldBe 1
        data[0]["targets"][0]["entityType"].asText() shouldBe "User"
        data[0]["targets"][0]["entityId"].asText() shouldBe "alice@example.com"

        data[1]["entityId"].asText() shouldBe bob.userId.toString()
        data[1]["targets"][0]["entityId"].asText() shouldBe "bob@example.com"
    }

    "execute keeps the textual summary of ListAgentUsersTool" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns listOf(member("alice@example.com"))
        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)
        result.output shouldBe "Agent $agentId is accessible to 1 user(s): alice@example.com (MEMBER)"
    }

    // -------------------------------------------------------------------------
    // Declared contract
    // -------------------------------------------------------------------------

    "output schema declares the search result envelope" {
        val schema = tool().outputSchema()
        schema["type"].asText() shouldBe "object"
        schema["required"].map { it.asText() }.toSet() shouldBe setOf("data", "metadata")
    }

    "input schema is the one of listAgentUsers" {
        tool().inputSchema shouldBe ListAgentUsersTool(usersByAgentLoader = loader).inputSchema
    }

    // -------------------------------------------------------------------------
    // Name convention
    // -------------------------------------------------------------------------

    "bare name is 'searchAgentUsers'" {
        SearchAgentUsersTool(configName = null, usersByAgentLoader = loader).name shouldBe "searchAgentUsers"
    }

    "prefixed name is 'USER_GROUP__searchAgentUsers'" {
        SearchAgentUsersTool(configName = "USER_GROUP", usersByAgentLoader = loader).name shouldBe "USER_GROUP__searchAgentUsers"
    }
})
