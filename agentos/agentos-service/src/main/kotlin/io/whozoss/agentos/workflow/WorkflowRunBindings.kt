package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import java.util.UUID

/**
 * Values of the run a workflow is executed for, available to step params as bindings.
 *
 * A binding is a string value that is **exactly** `{{run.<name>}}`: no expressions and no
 * partial interpolation, so a definition stays readable without evaluating anything.
 */
data class WorkflowRunBindings(
    val agentConfigId: UUID,
    val agentName: String,
    val promptId: UUID,
    val title: String,
    val scheduledPromptId: UUID,
    val runId: UUID,
) {
    private fun valueOf(name: String): String? =
        when (name) {
            "agentConfigId" -> agentConfigId.toString()
            "agentName" -> agentName
            "promptId" -> promptId.toString()
            "title" -> title
            "scheduledPromptId" -> scheduledPromptId.toString()
            "runId" -> runId.toString()
            else -> null
        }

    /** [config] with every binding of every step's params replaced by its value. */
    fun applyTo(config: LoopConfig): LoopConfig =
        config.copy(
            steps =
                config.steps.map { step ->
                    step.copy(input = step.input.copy(params = step.input.params?.let(::substitute)))
                },
        )

    private fun substitute(node: JsonNode): JsonNode =
        when (node) {
            is ObjectNode ->
                node.deepCopy().also { copy ->
                    copy.fieldNames().asSequence().toList().forEach { key -> copy.set<JsonNode>(key, substitute(copy[key])) }
                }
            is ArrayNode -> node.deepCopy().also { copy -> (0 until copy.size()).forEach { copy.set(it, substitute(copy[it])) } }
            is TextNode -> bindingName(node.textValue())?.let { name -> valueOf(name)?.let(::TextNode) } ?: node
            else -> node
        }

    companion object {
        /** Names a step param may bind to. */
        val NAMES: Set<String> = setOf("agentConfigId", "agentName", "promptId", "title", "scheduledPromptId", "runId")

        private val BINDING = Regex("""^\{\{run\.(\w+)}}$""")

        /** Any `{{...}}` occurrence, used to detect malformed or partial bindings at write time. */
        private val ANY_PLACEHOLDER = Regex("""\{\{.*?}}""")

        /** The binding name when [text] is exactly `{{run.<name>}}`, null otherwise. */
        fun bindingName(text: String): String? = BINDING.matchEntire(text)?.groupValues?.get(1)

        /**
         * Problems with the bindings found in [node]: unknown names, and `{{...}}` used anywhere
         * other than as a whole value. Empty when every binding is valid.
         */
        fun problemsIn(node: JsonNode?): List<String> =
            buildList {
                fun visit(n: JsonNode) {
                    when {
                        n.isObject || n.isArray -> n.forEach(::visit)
                        n.isTextual -> {
                            val text = n.textValue()
                            val name = bindingName(text)
                            when {
                                name != null && name !in NAMES -> add("unknown binding '{{run.$name}}' (known: ${NAMES.joinToString()})")
                                name == null && ANY_PLACEHOLDER.containsMatchIn(text) ->
                                    add("'$text': a binding must be the whole value, exactly '{{run.<name>}}'")
                            }
                        }
                    }
                }
                node?.let(::visit)
            }
    }
}
