package io.whozoss.agentos.prompt

import io.whozoss.agentos.exception.PromptResolutionException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Parses and resolves slash-command prompt invocations from user message text.
 *
 * All functions are pure — no state, no dependencies, no Spring wiring.
 *
 * A prompt command has the form:
 * ```
 * /prompt-name free text arguments here
 * /prompt-name "quoted arg" second-arg
 * ```
 *
 * Placeholder syntax in prompt content:
 * - `{{ARGUMENTS}}` — the entire raw argument string after the command name
 * - `{{paramName}}` — named placeholder, resolved **positionally** from user arguments.
 *   The first token maps to the first entry in [Prompt.parameters], the second token
 *   to the second entry, etc. The placeholder name in content must match the parameter
 *   name at that position.
 *
 * Resolution rules:
 * 1. The message must start with `/` followed immediately by a prompt name (no space).
 * 2. If the text does not start with `/`, it is returned unchanged.
 * 3. The caller provides a `promptsProvider` lambda that lazily loads the effective
 *    prompt set on first need. The lambda is called at most once per [resolve] call;
 *    all recursive resolution is done in-memory against the returned snapshot.
 * 4. If no prompt matches the name, the original text is returned unchanged.
 * 5. `{{ARGUMENTS}}` is replaced with the raw argument string verbatim.
 * 6. User arguments are tokenized positionally (quote-aware) and mapped to [Prompt.parameters]
 *    by declaration order. Each `{{paramName}}` in content is replaced with the corresponding value.
 * 7. Missing arguments fall back to [PromptParameter.defaultValue].
 * 8. Any unresolved `{{...}}` placeholder after substitution throws [PromptResolutionException].
 *
 * The resolved content strings are returned as a list. Content lines that themselves
 * start with `/` are resolved recursively, enabling prompt composition.
 *
 * **Cycle detection:**
 * Maintains a call stack (ancestry path) of [PromptCallKey] pairs `(name, resolvedArgs)`.
 * A cycle is detected when the same key appears again in the current ancestry.
 * This correctly handles:
 * - Same prompt, different arguments \u2192 allowed (may produce different output)
 * - Same prompt, same arguments in ancestry \u2192 [PromptResolutionException]
 * - Same prompt reused in sibling branches \u2192 allowed (stack unwinds between siblings)
 *
 * Depth is also capped at [MAX_PROMPT_DEPTH] to catch long chains that bypass
 * argument-based cycle detection.
 */
object PromptCommandParser {
    /** Placeholder for the entire raw argument string. */
    private const val ARGUMENTS_PLACEHOLDER = "{{ARGUMENTS}}"

    /** Detects any unresolved `{{...}}` placeholder after substitution. */
    private val UNRESOLVED_REGEX = Regex("""\{\{\w+\}\}""")

    /** Maximum recursive prompt nesting depth. */
    private const val MAX_PROMPT_DEPTH = 10

    /**
     * Attempt to parse [text] as a prompt command.
     *
     * @param promptsProvider lazy supplier of the effective prompt list. Called at most
     *        once, and only when [text] starts with `/`. The caller controls when and
     *        how the prompts are loaded (typically via [PromptService.findEffective]).
     * @return a list of resolved strings when [text] is a recognised prompt command,
     *         or `listOf(text)` when it does not start with `/` or matches no known prompt.
     * @throws PromptResolutionException when a cycle is detected, the maximum nesting
     *         depth is exceeded, or substitution leaves unresolved placeholders.
     */
    fun resolve(
        text: String,
        promptsProvider: () -> List<Prompt>,
    ): List<String> {
        if (!text.startsWith("/")) return listOf(text)
        val effectivePrompts = promptsProvider()
        return resolveInternal(text, effectivePrompts, depth = 0, callStack = ArrayDeque())
    }

    private fun resolveInternal(
        text: String,
        effectivePrompts: List<Prompt>,
        depth: Int,
        callStack: ArrayDeque<PromptCallKey>,
    ): List<String> {
        if (!text.startsWith("/")) return listOf(text)

        val withoutSlash = text.removePrefix("/").trimStart()
        val spaceIdx = withoutSlash.indexOf(' ')
        val commandName = (if (spaceIdx == -1) withoutSlash else withoutSlash.substring(0, spaceIdx)).lowercase()
        val argString = if (spaceIdx == -1) "" else withoutSlash.substring(spaceIdx + 1).trim()

        if (commandName.isBlank()) return listOf(text)

        val prompt = effectivePrompts.firstOrNull { it.name.lowercase() == commandName }
            ?: run {
                logger.debug { "No prompt found for slash command '/$commandName' \u2014 passing text through" }
                return listOf(text)
            }

        if (depth >= MAX_PROMPT_DEPTH) {
            throw PromptResolutionException("Maximum prompt nesting depth ($MAX_PROMPT_DEPTH) exceeded")
        }

        val tokens = tokenize(argString)

        // Build positional argument map: parameter name -> user value or default
        val args = prompt.parameters.mapIndexed { index, param ->
            val value = if (index < tokens.size) stripQuotes(tokens[index]) else param.defaultValue
            param.name to value
        }.toMap()

        val callKey = PromptCallKey(commandName, args)
        if (callStack.any { it == callKey }) {
            val cycle = (callStack.map { it.name } + commandName).joinToString(" \u2192 ")
            throw PromptResolutionException("Cycle detected in prompt resolution: $cycle")
        }

        callStack.addLast(callKey)
        try {
            val resolved = prompt.content.map { line ->
                var result = line
                result = result.replace(ARGUMENTS_PLACEHOLDER, argString)
                for ((name, value) in args) {
                    result = result.replace("{{$name}}", value)
                }
                result
            }

            // Validate: no placeholders should remain after substitution.
            val remaining = resolved.flatMap { line ->
                UNRESOLVED_REGEX.findAll(line).map { it.value }.toList()
            }.toSet()
            if (remaining.isNotEmpty()) {
                throw PromptResolutionException(
                    "Missing required arguments for /${prompt.name}: ${remaining.joinToString(", ")}",
                )
            }

            logger.debug { "Resolved prompt command '/$commandName' (${tokens.size} args provided)" }

            return resolved.flatMap { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("/") -> resolveInternal(trimmed, effectivePrompts, depth + 1, callStack)
                    else -> listOf(line)
                }
            }
        } finally {
            callStack.removeLast()
        }
    }

    /**
     * Tokenises [input] by walking character by character.
     *
     * - Whitespace outside quotes ends the current token.
     * - Double-quoted and single-quoted spans preserve inner whitespace.
     * - Quotes are kept in the raw token so [stripQuotes] can remove them.
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inDouble = false
        var inSingle = false

        for (ch in input) {
            when {
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    current.append(ch)
                }
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    current.append(ch)
                }
                ch == ' ' && !inDouble && !inSingle -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Strips enclosing single or double quotes from a token. */
    private fun stripQuotes(token: String): String = when {
        token.length >= 2 && token.first() == '"' && token.last() == '"' -> token.drop(1).dropLast(1)
        token.length >= 2 && token.first() == '\'' && token.last() == '\'' -> token.drop(1).dropLast(1)
        else -> token
    }

    /**
     * Identifies a prompt invocation in the call stack by name and resolved arguments.
     * Two calls with the same name but different resolved arguments are distinct and
     * do not constitute a cycle.
     */
    private data class PromptCallKey(
        val name: String,
        val args: Map<String, String>,
    )
}
