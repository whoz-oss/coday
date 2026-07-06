package io.whozoss.agentos.prompt

import io.whozoss.agentos.exception.BadRequestException
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Parses and resolves slash-command prompt invocations from user message text.
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
 * 2. The effective prompt set for `(namespaceId, userId)` is resolved via [PromptService.findEffective].
 * 3. If no prompt matches the name, the original text is returned unchanged.
 * 4. `{{ARGUMENTS}}` is replaced with the raw argument string verbatim.
 * 5. User arguments are tokenized positionally (quote-aware) and mapped to [Prompt.parameters]
 *    by declaration order. Each `{{paramName}}` in content is replaced with the corresponding value.
 * 6. Missing arguments fall back to [PromptParameter.defaultValue].
 * 7. Any unresolved `{{...}}` placeholder after substitution throws [BadRequestException].
 *
 * The resolved content strings are joined with `\n\n` to produce a single message.
 */
@Service
class PromptCommandParser(
    private val promptService: PromptService,
) {
    /**
     * Attempt to parse [text] as a prompt command in the context of [namespaceId] / [userId].
     *
     * @return the resolved prompt content when [text] is a recognised prompt command,
     *         or [text] unchanged when it does not start with `/` or matches no known prompt.
     * @throws BadRequestException when the command matches a prompt but substitution
     *         leaves unresolved placeholders (missing required arguments).
     */
    fun resolve(
        text: String,
        namespaceId: UUID,
        userId: UUID,
    ): String {
        if (!text.startsWith("/")) return text

        val withoutSlash = text.removePrefix("/").trimStart()
        val spaceIdx = withoutSlash.indexOf(' ')
        val commandName = (if (spaceIdx == -1) withoutSlash else withoutSlash.substring(0, spaceIdx)).lowercase()
        val argString = if (spaceIdx == -1) "" else withoutSlash.substring(spaceIdx + 1).trim()

        if (commandName.isBlank()) return text

        val prompt = promptService
            .findEffective(namespaceId, userId)
            .firstOrNull { it.name.lowercase() == commandName }
            ?: run {
                logger.debug { "No prompt found for slash command '/$commandName' — passing text through" }
                return text
            }

        val tokens = tokenize(argString)

        // Build positional argument map: parameter name -> user value or default
        val args = prompt.parameters.mapIndexed { index, param ->
            val value = if (index < tokens.size) stripQuotes(tokens[index]) else param.defaultValue
            param.name to value
        }.toMap()

        val resolved = prompt.content.map { line ->
            var result = line

            // Replace {{ARGUMENTS}} with the raw argument string
            result = result.replace(ARGUMENTS_PLACEHOLDER, argString)

            // Replace {{paramName}} with positional values
            for ((name, value) in args) {
                result = result.replace("{{$name}}", value)
            }

            result
        }

        // Validate: no placeholders should remain
        val remaining = resolved.flatMap { line ->
            UNRESOLVED_REGEX.findAll(line).map { it.value }.toList()
        }.toSet()
        if (remaining.isNotEmpty()) {
            throw BadRequestException(
                "Missing required arguments for /${prompt.name}: ${remaining.joinToString(", ")}",
            )
        }

        logger.debug { "Resolved prompt command '/$commandName' (${tokens.size} args provided)" }
        return resolved.joinToString("\n\n")
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

    companion object : KLogging() {
        /** Placeholder for the entire raw argument string. */
        private const val ARGUMENTS_PLACEHOLDER = "{{ARGUMENTS}}"

        /** Detects any unresolved `{{...}}` placeholder after substitution. */
        private val UNRESOLVED_REGEX = Regex("""\{\{\w+\}\}""")
    }
}
