package io.whozoss.agentos.selfConfig

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor
import io.whozoss.agentos.sdk.caseEvent.ThinkingEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ConfirmationMode
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for the five `SELF_CONFIG` tools.
 *
 * The tools are deliberately thin: they validate the LLM's arguments, delegate to
 * [SelfConfigToolService] through a lambda, and render the outcome. These tests cover exactly that
 * surface -- argument validation, faithful rendering, and the confirmation stance of each tool --
 * while every authorization rule is asserted in [SelfConfigToolServiceSpec], where it lives.
 *
 * The lambdas are hand-written rather than mocked: each test states what the service answered, and
 * a captured-argument assertion reads better as a plain `var` than as a MockK slot for a
 * function type.
 */
class SelfConfigToolsSpec : StringSpec({

    val mapper = jacksonObjectMapper()
    val namespaceId = UUID.randomUUID()
    val caseId = UUID.randomUUID()

    val ctx =
        ToolContext(
            namespaceId = namespaceId,
            userId = UUID.randomUUID(),
            userExternalId = "alice@example.com",
            caseEvents = listOf(ThinkingEvent(namespaceId = namespaceId, caseId = caseId)),
        )

    fun config(
        id: UUID = UUID.randomUUID(),
        nsId: UUID? = namespaceId,
        name: String = "MY_JIRA",
    ) = IntegrationConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        userId = ctx.userId,
        name = name,
        integrationType = "HTTP_API",
        description = "my jira",
        parameters = mapper.readTree("""{"baseUrl":"https://jira.example.com"}"""),
        authSettingName = "jira-token",
    )

    val refused = SelfConfigOutcome.Failure(SelfConfigErrors.NOT_AUTHENTICATED, "no user")

    // -------------------------------------------------------------------------
    // Naming: the configuration name is the prefix the LLM sees
    // -------------------------------------------------------------------------

    "tool names are prefixed with the configuration name, and bare without one" {
        ListIntegrationTypesTool("SELF_CONFIG") { emptyList() }.name shouldBe "SELF_CONFIG__listIntegrationTypes"
        ListIntegrationsTool("SELF_CONFIG") { refused }.name shouldBe "SELF_CONFIG__listIntegrations"
        CreateIntegrationTool("SELF_CONFIG") { _, _, _, _, _, _ -> refused }.name shouldBe "SELF_CONFIG__createIntegration"
        UpdateIntegrationTool("SELF_CONFIG") { _, _, _, _, _ -> refused }.name shouldBe "SELF_CONFIG__updateIntegration"
        DeleteIntegrationTool("SELF_CONFIG") { _, _ -> refused }.name shouldBe "SELF_CONFIG__deleteIntegration"

        ListIntegrationsTool(configName = null) { refused }.name shouldBe "listIntegrations"
    }

    // -------------------------------------------------------------------------
    // Confirmation stance
    // -------------------------------------------------------------------------

    "reads are ungated, writes are gated, and deletion never trusts implicit consent" {
        ListIntegrationTypesTool { emptyList() }.getConfirmationMode() shouldBe ConfirmationMode.NONE
        ListIntegrationsTool { refused }.getConfirmationMode() shouldBe ConfirmationMode.NONE
        CreateIntegrationTool { _, _, _, _, _, _ -> refused }.getConfirmationMode() shouldBe ConfirmationMode.INFER
        UpdateIntegrationTool { _, _, _, _, _ -> refused }.getConfirmationMode() shouldBe ConfirmationMode.INFER
        DeleteIntegrationTool { _, _ -> refused }.getConfirmationMode() shouldBe ConfirmationMode.EVERY_TIME
    }

    // -------------------------------------------------------------------------
    // listIntegrationTypes -- the schema is the payload
    // -------------------------------------------------------------------------

    "listIntegrationTypes returns each type WITH its configuration schema" {
        val schema = mapper.readTree("""{"type":"object","properties":{"defaultTimezone":{"type":"string"}}}""")
        val tool =
            ListIntegrationTypesTool {
                listOf(
                    IntegrationTypeDescriptor("DATETIME", "Datetime", "clock", schema),
                    IntegrationTypeDescriptor("HTTP_API", "Http api", "http", null),
                )
            }

        val result = tool.execute(null, ctx)

        result.success shouldBe true
        val types = result.structuredOutput!!
        types.size() shouldBe 2
        // Without the schema the agent would have to guess parameter names.
        types[0]["configSchema"] shouldBe schema
        types[1]["configSchema"].isNull shouldBe true
    }

    "listIntegrationTypes filters on an exact type and names the known ones when it misses" {
        val tool =
            ListIntegrationTypesTool {
                listOf(IntegrationTypeDescriptor("DATETIME", "Datetime", "clock", null))
            }

        tool.execute(ListIntegrationTypesTool.Input(type = "datetime"), ctx).structuredOutput!!.size() shouldBe 1

        val miss = tool.execute(ListIntegrationTypesTool.Input(type = "NOPE"), ctx)
        miss.success shouldBe false
        miss.errorType shouldBe SelfConfigErrors.NOT_FOUND
        miss.output shouldContain "DATETIME"
    }

    // -------------------------------------------------------------------------
    // listIntegrations
    // -------------------------------------------------------------------------

    "listIntegrations renders the scope each configuration applies in" {
        val tool =
            ListIntegrationsTool {
                SelfConfigOutcome.Ok(
                    listOf(config(name = "SCOPED"), config(nsId = null, name = "EVERYWHERE")),
                )
            }

        val result = tool.execute(null, ctx)

        result.success shouldBe true
        val rows = result.structuredOutput!!
        rows[0]["scope"].asText() shouldBe "namespace"
        rows[1]["scope"].asText() shouldBe "global"
        rows[0]["parameters"]["baseUrl"].asText() shouldBe "https://jira.example.com"
    }

    "an empty list is a success, not an error: the user simply has none yet" {
        val result = ListIntegrationsTool { SelfConfigOutcome.Ok(emptyList()) }.execute(null, ctx)

        result.success shouldBe true
        result.output shouldContain "no personal integration configuration"
    }

    "a service failure becomes a tool error carrying its errorType and message" {
        val result =
            ListIntegrationsTool {
                SelfConfigOutcome.Failure(SelfConfigErrors.NOT_AUTHENTICATED, "no identified user")
            }.execute(null, ctx)

        result.success shouldBe false
        result.errorType shouldBe SelfConfigErrors.NOT_AUTHENTICATED
        result.output shouldBe "no identified user"
    }

    // -------------------------------------------------------------------------
    // createIntegration
    // -------------------------------------------------------------------------

    "createIntegration requires a name and a type" {
        val tool = CreateIntegrationTool { _, _, _, _, _, _ -> SelfConfigOutcome.Ok(config()) }

        listOf(
            null,
            CreateIntegrationTool.Input(name = "X"),
            CreateIntegrationTool.Input(integrationType = "DATETIME"),
            CreateIntegrationTool.Input(name = "  ", integrationType = "DATETIME"),
        ).forEach { input ->
            val result = tool.execute(input, ctx)
            result.success shouldBe false
            result.errorType shouldBe SelfConfigErrors.MISSING_INPUT
        }
    }

    "createIntegration defaults to the namespace scope and maps 'global' to a scope-less row" {
        var namespaceScoped: Boolean? = null
        val tool =
            CreateIntegrationTool { _, _, _, _, _, scoped ->
                namespaceScoped = scoped
                SelfConfigOutcome.Ok(config())
            }

        tool.execute(CreateIntegrationTool.Input(name = "A", integrationType = "DATETIME"), ctx)
        namespaceScoped shouldBe true

        tool.execute(
            CreateIntegrationTool.Input(name = "A", integrationType = "DATETIME", scope = "GLOBAL"),
            ctx,
        )
        namespaceScoped shouldBe false
    }

    "createIntegration refuses a scope it does not know rather than silently picking one" {
        var called = false
        val tool =
            CreateIntegrationTool { _, _, _, _, _, _ ->
                called = true
                SelfConfigOutcome.Ok(config())
            }

        val result =
            tool.execute(
                CreateIntegrationTool.Input(name = "A", integrationType = "DATETIME", scope = "platform"),
                ctx,
            )

        result.success shouldBe false
        result.errorType shouldBe SelfConfigErrors.INVALID_INPUT
        called shouldBe false
    }

    // -------------------------------------------------------------------------
    // updateIntegration / deleteIntegration
    // -------------------------------------------------------------------------

    "a malformed id is answered, not thrown" {
        val update = UpdateIntegrationTool { _, _, _, _, _ -> SelfConfigOutcome.Ok(config()) }
        val delete = DeleteIntegrationTool { _, id -> SelfConfigOutcome.Ok(id) }

        listOf(
            update.execute(UpdateIntegrationTool.Input(id = "not-a-uuid"), ctx),
            update.execute(UpdateIntegrationTool.Input(id = null), ctx),
            delete.execute(DeleteIntegrationTool.Input(id = "MY_JIRA"), ctx),
            delete.execute(null, ctx),
        ).forEach { result ->
            result.success shouldBe false
            result.errorType shouldBe SelfConfigErrors.MISSING_INPUT
            result.output shouldContain "listIntegrations"
        }
    }

    "updateIntegration forwards only the fields the agent actually supplied" {
        val target = UUID.randomUUID()
        var seen: Triple<String?, String?, Boolean>? = null
        val tool =
            UpdateIntegrationTool { _, _, name, description, parameters ->
                seen = Triple(name, description, parameters != null)
                SelfConfigOutcome.Ok(config(id = target))
            }

        tool.execute(UpdateIntegrationTool.Input(id = target.toString(), name = "  "), ctx)

        // A blank name is an omission, not a request to blank the name.
        seen shouldBe Triple(null, null, false)
    }

    "deleteIntegration reports the deleted id" {
        val target = UUID.randomUUID()
        val result =
            DeleteIntegrationTool { _, id -> SelfConfigOutcome.Ok(id) }
                .execute(DeleteIntegrationTool.Input(id = target.toString()), ctx)

        result.success shouldBe true
        result.output shouldContain target.toString()
        result.metadata["deletedId"] shouldBe target.toString()
    }

    // -------------------------------------------------------------------------
    // The plugin
    // -------------------------------------------------------------------------

    "the plugin declares a non-null schema, or the type would never reach the catalogue" {
        // CompositeIntegrationTypeRegistry.registerFromPlugin skips a plugin whose configSchema is
        // null: a null here would make SELF_CONFIG permanently undeclarable.
        SelfConfigToolPlugin.CONFIG_SCHEMA.isNull shouldBe false
        SelfConfigToolPlugin.CONFIG_SCHEMA["type"].asText() shouldBe "object"
        // No parameter, on purpose: a knob on this type would be the one an agent could turn to
        // widen its own reach.
        SelfConfigToolPlugin.CONFIG_SCHEMA["properties"].size() shouldBe 0
        SelfConfigToolPlugin.INTEGRATION_TYPE shouldBe "SELF_CONFIG"
    }
})
