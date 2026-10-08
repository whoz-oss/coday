package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigPolicy
import io.whozoss.agentos.sdk.caseEvent.QuestionType
import org.springframework.stereotype.Component

/**
 * Validates the `allowedQuestionTypes` parameter of a `QUERY_USER` [IntegrationConfig] when it
 * is saved.
 *
 * ## Asymmetry with [QueryUserToolPlugin.provideTools] -- by design
 *
 * This policy is **strict at save time**: any value in `allowedQuestionTypes` that is not one
 * of the three acceptable [QuestionType]s is rejected outright, with a message naming the
 * offending value. [QueryUserToolPlugin.provideTools] is, in contrast, **tolerant at execution
 * time**: it silently ignores unknown values and falls back to the default set rather than
 * throwing, because [io.whozoss.agentos.tool.ToolResolverService.extractTools] swallows
 * exceptions from tool resolution and would otherwise turn a malformed config into a silent
 * zero-tool outcome -- an opaque failure far from the mistake.
 *
 * Validating here keeps the failure where the mistake was made (cf.
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl.assertTypeSpecificRules]);
 * tolerating at execution time keeps a previously-valid config from suddenly breaking an agent
 * run because of a value that, for whatever reason, slipped past validation (e.g. data migrated
 * from an older schema version).
 */
@Component
class QueryUserConfigPolicy : IntegrationConfigPolicy {
    override fun supports(integrationType: String): Boolean = integrationType == QueryUserToolPlugin.INTEGRATION_TYPE

    override fun validate(config: IntegrationConfig) {
        val values = config.parameters.stringList("allowedQuestionTypes")
        values.forEach { value ->
            if (value == QuestionType.OAUTH_AUTHORIZE.name) {
                throw UnprocessableEntityException(
                    "allowedQuestionTypes: 'OAUTH_AUTHORIZE' is produced by the OAuth flow " +
                        "(OAuthFlowService), not by the queryUser tool, and cannot be declared here.",
                )
            }
            if (runCatching { QuestionType.valueOf(value) }.getOrNull() !in ACCEPTED_QUESTION_TYPES) {
                throw UnprocessableEntityException(
                    "allowedQuestionTypes: '$value' is not a valid question type for this integration. " +
                        "Valid values: ${ACCEPTED_QUESTION_TYPES.joinToString(", ") { it.name }}.",
                )
            }
        }
    }

    override fun afterSave(config: IntegrationConfig) {
        // No-op: nothing to provision or queue for this integration.
    }

    companion object {
        private val ACCEPTED_QUESTION_TYPES: Set<QuestionType> = QueryUserToolPlugin.DEFAULT_ALLOWED_QUESTION_TYPES

        private fun JsonNode?.stringList(field: String): List<String> =
            this?.get(field)?.takeIf { it.isArray }?.map { it.asText() }?.filter { it.isNotBlank() } ?: emptyList()
    }
}
