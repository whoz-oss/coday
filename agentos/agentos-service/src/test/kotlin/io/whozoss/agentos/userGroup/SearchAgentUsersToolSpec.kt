package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.workflow.SearchResult
import io.whozoss.agentos.workflow.SearchResultTarget
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
        data[0]["entityId"].asText() shouldBe "alice@example.com"
        data[0]["targets"].size() shouldBe 1
        data[0]["targets"][0]["entityType"].asText() shouldBe "User"
        data[0]["targets"][0]["entityId"].asText() shouldBe "alice@example.com"

        data[1]["entityId"].asText() shouldBe "bob@example.com"
        data[1]["targets"][0]["entityId"].asText() shouldBe "bob@example.com"
    }

    "execute returns a structured output the loop can parse as a SearchResult" {
        val agentId = UUID.randomUUID()
        every { loader(agentId, NAMESPACE_ID, USER_ID) } returns
            listOf(member("alice@example.com", "ADMIN"), member("bob@example.com"))

        val result = tool().execute(ListAgentUsersTool.Input(agentId), CONTEXT)

        val parsed = jacksonObjectMapper().treeToValue(result.structuredOutput, SearchResult::class.java)
        parsed.data.map { it.entityType } shouldBe listOf("User", "User")
        parsed.data.map { it.entityId } shouldBe listOf("alice@example.com", "bob@example.com")
        parsed.data.map { it.targets } shouldBe
            listOf(
                listOf(SearchResultTarget(entityType = "User", entityId = "alice@example.com")),
                listOf(SearchResultTarget(entityType = "User", entityId = "bob@example.com")),
            )
        parsed.metadata!!.totalCount shouldBe 2L
        parsed.metadata!!.next shouldBe null
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

    "output schema is the shared SearchResult schema" {
        val schema = tool().outputSchema()
        schema shouldBe SearchResult.SCHEMA
        schema["required"].map { it.asText() }.toSet() shouldBe setOf("data")
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
