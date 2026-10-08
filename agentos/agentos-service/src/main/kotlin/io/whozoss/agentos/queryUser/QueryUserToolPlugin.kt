package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.stereotype.Component

/**
 * Spring-managed [ToolPlugin] that provides the QUERY_USER integration.
 *
 * Unlike PF4J plugins, this class is annotated with `@Component` directly -- no
 * separate `@Configuration` class is needed because [QueryUserToolPlugin] has no
 * Spring dependencies to inject and therefore creates no circular-dependency risk.
 *
 * Compare with [io.whozoss.agentos.redirect.RedirectToolPlugin], which requires a
 * `@Configuration` class ([io.whozoss.agentos.redirect.RedirectConfiguration]) to
 * inject an [io.whozoss.agentos.agentConfig.AgentConfigService]-backed lambda without
 * creating a cycle through [io.whozoss.agentos.agent.AgentServiceImpl]. Here the tool
 * is pure Kotlin with no external state, so `@Component` is both simpler and correct.
 *
 * [io.whozoss.agentos.tool.ToolRegistryService] collects all `ToolPlugin` beans via
 * its `springToolPlugins: List<ToolPlugin>` constructor parameter, so this bean is
 * automatically discovered alongside PF4J-loaded plugins.
 *
 * ## Configuration
 *
 * The integration now declares [CONFIG_SCHEMA]: an admin can restrict which
 * [QuestionType] forms the agent is allowed to use via the `allowedQuestionTypes`
 * parameter. This is required for the integration type to be catalogued at all --
 * [io.whozoss.agentos.integrationConfig.CompositeIntegrationTypeRegistry.registerFromPlugin]
 * skips plugins whose [configSchema] is `null`, so a null schema here would make
 * `QUERY_USER` permanently undeclarable in the integration form.
 *
 * [QuestionType.OAUTH_AUTHORIZE] is deliberately absent from [CONFIG_SCHEMA]: that
 * question form is produced exclusively by `OAuthFlowService`, never by this tool.
 * Exposing it here would mislead an administrator into believing they can configure
 * OAuth behaviour through this integration.
 */
@Component
class QueryUserToolPlugin : ToolPlugin {
    override val integrationType: String = INTEGRATION_TYPE

    override val configSchema: JsonNode = CONFIG_SCHEMA

    /**
     * Reads `allowedQuestionTypes` from [config] tolerantly: unrecognised values are ignored
     * rather than thrown (an exception here would be swallowed by
     * [io.whozoss.agentos.tool.ToolResolverService.extractTools], silently yielding zero
     * tools -- an opaque failure mode a malformed config must never trigger). When the
     * resulting set is empty (absent field, empty array, or only unrecognised values), all
     * three question types are allowed. Mirrors the idiom used by
     * [io.whozoss.agentos.redirect.RedirectToolPlugin] for its `agents` parameter.
     *
     * "Unrecognised" is checked against [DEFAULT_ALLOWED_QUESTION_TYPES], not against the
     * whole [QuestionType] enum: `OAUTH_AUTHORIZE` parses fine as an enum constant but is not
     * a form this tool can ever produce. Accepting it would yield a tool whose only allowed
     * form is unreachable -- every call rejected, for a reason no one could diagnose from the
     * config. [QueryUserConfigPolicy] rejects it outright at save time; here it is simply
     * dropped, and dropping it is what makes the fallback to all three forms kick in.
     */
    override fun provideTools(
        config: JsonNode?,
        configName: String?,
        context: ToolContext?,
    ): List<StandardTool<*>> {
        val allowedQuestionTypes =
            config
                .stringList("allowedQuestionTypes")
                .mapNotNull { runCatching { QuestionType.valueOf(it) }.getOrNull() }
                .filter { it in DEFAULT_ALLOWED_QUESTION_TYPES }
                .toSet()
                .takeIf { it.isNotEmpty() } ?: DEFAULT_ALLOWED_QUESTION_TYPES

        return listOf(QueryUserTool(configName = configName, allowedQuestionTypes = allowedQuestionTypes))
    }

    companion object {
        const val INTEGRATION_TYPE = "QUERY_USER"

        val DEFAULT_ALLOWED_QUESTION_TYPES: Set<QuestionType> =
            setOf(QuestionType.FREE_TEXT, QuestionType.SINGLE_CHOICE, QuestionType.OPEN_CHOICE)

        /** Returns the non-blank string values of a JSON array field, or an empty list when absent or not an array. */
        private fun JsonNode?.stringList(field: String): List<String> =
            this?.get(field)?.takeIf { it.isArray }?.map { it.asText() }?.filter { it.isNotBlank() } ?: emptyList()

        val CONFIG_SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "title": "Query User Configuration",
                  "description": "Lets an agent ask the user a question mid-run and resume with the answer.",
                  "properties": {
                    "allowedQuestionTypes": {
                      "type": "array",
                      "title": "Allowed question types",
                      "description": "Question forms the agent may use. Leave empty to allow all. FREE_TEXT: open question with no options. SINGLE_CHOICE: the user must pick one of the proposed options. OPEN_CHOICE: options plus a free-text field.",
                      "items": { "type": "string", "enum": ["FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE"] },
                      "uniqueItems": true,
                      "default": ["FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE"]
                    }
                  },
                  "additionalProperties": false
                }
                """.trimIndent(),
            )
    }
}
