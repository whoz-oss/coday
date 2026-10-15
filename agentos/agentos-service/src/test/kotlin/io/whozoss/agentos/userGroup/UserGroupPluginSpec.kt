package io.whozoss.agentos.userGroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

@Suppress("UNCHECKED_CAST")
class UserGroupPluginSpec :
    StringSpec({

        val membersLoader = mockk<(UUID) -> List<UserGroupMember>>()

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
            UserGroupToolPlugin(membersLoader).integrationType shouldBe "USER_GROUP"
        }

        // -------------------------------------------------------------------------
        // configSchema
        // -------------------------------------------------------------------------

        "configSchema is a non-null object schema" {
            val schema = UserGroupToolPlugin(membersLoader).configSchema
            schema.shouldNotBeNull()
            schema["type"].asText() shouldBe "object"
        }

        "configSchema has no required properties" {
            val schema = UserGroupToolPlugin(membersLoader).configSchema
            // properties node exists but is empty
            schema["properties"].size() shouldBe 0
        }

        // -------------------------------------------------------------------------
        // provideTools
        // -------------------------------------------------------------------------

        "provideTools returns a single ListUserGroupMembersTool" {
            val plugin = UserGroupToolPlugin(membersLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = context())
            tools shouldHaveSize 1
            tools.first().shouldBeInstanceOf<ListUserGroupMembersTool>()
        }

        "provideTools returns tool with bare name when configName is null" {
            val plugin = UserGroupToolPlugin(membersLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = context())
            tools.first().name shouldBe "listMembers"
        }

        "provideTools returns tool with prefixed name when configName is provided" {
            val plugin = UserGroupToolPlugin(membersLoader)
            val tools = plugin.provideTools(config = null, configName = "USER_GROUP", context = context())
            tools.first().name shouldBe "USER_GROUP__listMembers"
        }

        "provideTools returns a single tool even when context is null" {
            val plugin = UserGroupToolPlugin(membersLoader)
            val tools = plugin.provideTools(config = null, configName = null, context = null)
            tools shouldHaveSize 1
        }
    })
