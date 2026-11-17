package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.aiModel.ModelResolver
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import kotlinx.coroutines.CancellationException
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Writes the end-user summary of a scheduled loop: a few lines of **Markdown** that tell a non-technical reader when
 * it runs, what is selected each time, and what is done for each item. For example:
 *
 * ```markdown
 * **Every week, Monday to Friday**, *Bench Optimizer* will:
 * - review each **staffable talent** currently on the bench
 * - send their manager the best matching missions
 *
 * Uses the bench as the search scope.
 * ```
 *
 * ## Markdown guarantee
 *
 * The LLM answers with the Markdown text itself ([StructuredLlmClient.callText]), not inside a JSON string, where
 * models tend to flatten or escape the line breaks a list needs. The answer is then normalized by [toMarkdown]: a
 * wrapping code fence is removed, `*` and `•` bullets become `- `, and line endings are unified. An answer that is not
 * Markdown of the expected shape (a bold opening and at least one `- ` bullet) is dropped with a warning rather than
 * shown as unformatted text.
 *
 * The summary is written from the **final** values of a proposal (provided or inferred, search block included), not
 * from the request, so that it describes what would actually run. The LLM is told to rephrase these facts only.
 *
 * It is a nice-to-have: whatever prevents it (no model, an unusable answer, a failed or timed-out call) yields no
 * summary and a warning, never an error. Cancellation is not swallowed.
 *
 * Separate from [ScheduledPromptGenerationService] so that a summary can also be written for settings edited after
 * the generation.
 */
@Service
class ScheduledPromptSummaryService(
    private val modelResolver: ModelResolver,
    private val structuredLlmClient: StructuredLlmClient,
    private val objectMapper: ObjectMapper,
) {
    /** The facts the summary is written from. */
    data class SummaryInput(
        val description: String,
        val recurrence: RecurrenceDto,
        val planning: PlanningDto,
        val targetAgentName: String,
        val targetAgentDescription: String?,
        val prompt: String,
        val searchTool: String,
        val searchToolDescription: String?,
        val searchParams: JsonNode?,
    )

    /** The Markdown summary, or null with a [warning] saying why there is none. */
    data class SummaryOutcome(
        val summary: String?,
        val warning: String?,
    )

    /**
     * @param language BCP 47 tag of the language to write in (the reader's preferred language), or null to write in
     *   the language of the user's request.
     */
    suspend fun summarize(
        namespaceId: UUID,
        userId: UUID,
        input: SummaryInput,
        language: String?,
    ): SummaryOutcome {
        val model = modelResolver.resolve(namespaceId, userId)
        return when (model) {
            null -> {
                SummaryOutcome(summary = null, warning = "No summary: no AI model is configured for this namespace.")
            }

            else -> {
                try {
                    val text = structuredLlmClient.callText(model.first, model.second, systemPrompt(language), facts(input))
                    val markdown = text?.let { toMarkdown(it) }
                    SummaryOutcome(
                        summary = markdown,
                        warning =
                            when {
                                text == null -> EMPTY_WARNING
                                markdown == null -> NOT_MARKDOWN_WARNING
                                else -> null
                            },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "[ScheduledPromptSummary] Summary generation failed for namespace=$namespaceId" }
                    SummaryOutcome(summary = null, warning = FAILED_WARNING)
                }
            }
        }
    }

    private fun facts(input: SummaryInput): String =
        buildString {
            appendLine("Schedule:")
            appendLine("- recurrence: ${objectMapper.writeValueAsString(input.recurrence)}")
            appendLine("- planning: ${objectMapper.writeValueAsString(input.planning)}")
            appendLine()
            appendLine("Items selected each time, with the search tool '${input.searchTool}':")
            input.searchToolDescription?.takeIf { it.isNotBlank() }?.let { appendLine("- what the tool does: $it") }
            appendLine("- filters: ${input.searchParams?.let { objectMapper.writeValueAsString(it) } ?: "{}"}")
            appendLine()
            appendLine("For each item, a conversation is started with the agent '${input.targetAgentName}':")
            input.targetAgentDescription?.takeIf { it.isNotBlank() }?.let { appendLine("- what the agent does: $it") }
            appendLine("- the message it receives: ${input.prompt}")
            appendLine()
            appendLine("The user's original request (for intent only; describe what is configured above):")
            append(input.description)
        }

    private fun systemPrompt(language: String?): String {
        val writeIn =
            when (language) {
                null -> "the language of the user's original request"
                else -> "the language with BCP 47 tag '$language'"
            }
        return "$SYSTEM_PROMPT\n- Write in $writeIn."
    }

    companion object : KLogging() {
        private const val EMPTY_WARNING = "No summary: the LLM returned no content."
        private const val NOT_MARKDOWN_WARNING = "No summary: the LLM answer was not Markdown of the expected shape."
        private const val FAILED_WARNING = "No summary could be generated."

        private val WRAPPING_FENCE = Regex("""^```[a-zA-Z]*\s*\n(.*)\n\s*```$""", RegexOption.DOT_MATCHES_ALL)
        private val OTHER_BULLET = Regex("""^(\s*)[*•]\s+""", RegexOption.MULTILINE)
        private val DASH_BULLET = Regex("""^\s*- \S""", RegexOption.MULTILINE)

        /**
         * Normalizes an LLM answer into the summary Markdown: unified line endings, no wrapping code fence, `- ` bullets.
         * Returns null when the result is not of the expected shape: a `**bold**` opening and at least one `- ` bullet.
         */
        internal fun toMarkdown(answer: String): String? {
            val unified = answer.replace("\r\n", "\n").trim()
            val unfenced = WRAPPING_FENCE.find(unified)?.groupValues?.get(1)?.trim() ?: unified
            val markdown = OTHER_BULLET.replace(unfenced) { "${it.groupValues[1]}- " }
            return markdown.takeIf { it.contains("**") && DASH_BULLET.containsMatchIn(it) }
        }

        private val SYSTEM_PROMPT =
            """
            You summarize a scheduled automation for a non-technical end user, who reads it before saving it.

            Answer with Markdown only, exactly in this shape (line breaks included):

            **<the schedule>**, *<the agent's name>* will:
            - <what is selected each time, with the key term in **bold**>
            - <what is done for each item, and for whom>

            <optional: one short sentence about the scope or the end of the schedule>

            Rules:
            - The opening line is the schedule in bold, then the agent's name in italics, then "will:" (translated).
              Example: "**Every week, Monday to Friday**, *Bench Optimizer* will:"
            - Two or three bullets, each starting with "- " on its own line.
            - Rephrase only the facts given. Never invent a filter, a person, a frequency or an outcome.
            - No technical terms: no tool names, no JSON, no field names, no ids. Mention the time only if it matters,
              and then with its timezone (UTC).
            - At most 60 words.
            - Output the Markdown text itself: no JSON, no code fence, no heading, no preamble or closing remark.
            """.trimIndent()
    }
}
