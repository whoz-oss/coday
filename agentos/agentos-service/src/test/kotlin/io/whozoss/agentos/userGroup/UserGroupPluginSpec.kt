package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

@Suppress("UNCHECKED_CAST")
class UserGroupPluginSpec :
    StringSpec({

        val membersLoader = mockk<(UUID) -> List<UserGroupMember>>()
        val usersByAgentLoader = mockk<(UUID) -> List<UserGroupMember>>()
        val membersByGroupAndAgentLoader = mockk<(UUID, UUID) -> List<UserGroupMember>>()

        fun context() =
            ToolContext(
                namespaceId = UUID.randomUUID(),
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        // -------------------------------------------------------------------------
        // integrationType
        // -------------------------------------------------------------------------

        "integrationType is USER_GROUP" {
            UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader).integrationType shouldBe "USER_GROUP"
        }

        // -------------------------------------------------------------------------
        // configSchema
        // -------------------------------------------------------------------------

        "configSchema is a non-null object schema" {
            val schema = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader).configSchema
            schema.shouldNotBeNull()
            schema["type"].asText() shouldBe "object"
        }

        "configSchema has no required properties" {
            val schema = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader).configSchema
            schema["properties"].size() shouldBe 0
        }

        // -------------------------------------------------------------------------
        // provideTools
        // -------------------------------------------------------------------------

        "provideTools returns three tools" {
            val plugin = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = context())
            tools shouldHaveSize 3
            tools.map { it::class.simpleName }.toSet() shouldBe setOf(
                "ListUserGroupMembersTool",
                "ListAgentUsersTool",
                "ListGroupAgentUsersTool",
            )
        }

        "provideTools returns tools with bare names when configName is null" {
            val plugin = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = context())
            tools.map { it.name }.toSet() shouldBe setOf("listMembers", "listAgentUsers", "listGroupAgentUsers")
        }

        "provideTools returns tools with prefixed names when configName is provided" {
            val plugin = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader)
            val tools = plugin.provideTools(config = null, configName = "USER_GROUP", context = context())
            tools.map { it.name }.toSet() shouldBe setOf(
                "USER_GROUP__listMembers",
                "USER_GROUP__listAgentUsers",
                "USER_GROUP__listGroupAgentUsers",
            )
        }

        "provideTools returns three tools even when context is null" {
            val plugin = UserGroupToolPlugin(membersLoader, usersByAgentLoader, membersByGroupAndAgentLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = null)
            tools shouldHaveSize 3
        }
    })
