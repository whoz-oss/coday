package io.whozoss.agentos.plugins.http.response

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.agentos.plugins.http.HttpApiJson
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.openapi.ResponseShaping

/** @property truncated True when the text was cut, at the character cap or earlier at the byte cap. */
data class ShapedOutput(val text: String, val truncated: Boolean)

/**
 * Turns a response body into the text the LLM receives.
 *
 * A body that parses as JSON (declared JSON, or text that happens to be JSON) is filtered by [PathFilter]
 * and rendered compact JSON or YAML; any other text is returned as is. The result is then capped at
 * `maxResponseChars` with a marker telling the model how to narrow the response.
 */
object ResponseShaper {

    /** True when a body of this content type is text the LLM can read; null is treated as text. */
    fun isTextual(contentType: String?): Boolean {
        val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return mediaType.startsWith("text/") ||
            TEXTUAL_SUFFIXES.any { mediaType.endsWith(it) } ||
            mediaType in TEXTUAL_MEDIA_TYPES
    }

    fun shape(text: String, contentType: String?, bytesTruncated: Boolean, shaping: ResponseShaping): ShapedOutput {
        val rendered = parseJson(text, contentType)
            ?.let { PathFilter.filter(it, keepPaths = shaping.keepPaths, ignorePaths = shaping.ignorePaths) }
            ?.let { render(it, shaping.responseFormat) }
            ?: text
        return cap(rendered, shaping.maxResponseChars, bytesTruncated)
    }

    private fun parseJson(text: String, contentType: String?): JsonNode? {
        val firstChar = text.firstOrNull { !it.isWhitespace() }
        if (firstChar != '{' && firstChar != '[') return null
        return try {
            HttpApiJson.mapper.readTree(text)
        } catch (e: JsonProcessingException) {
            null
        }
    }

    private fun render(node: JsonNode, format: ResponseFormat): String =
        when (format) {
            ResponseFormat.JSON -> HttpApiJson.mapper.writeValueAsString(node)
            ResponseFormat.YAML -> HttpApiJson.yamlMapper.writeValueAsString(node)
        }

    /** When the body was already cut at the byte cap, [text] is not the whole response: its length is a lower bound. */
    private fun cap(text: String, maxChars: Int, bytesTruncated: Boolean): ShapedOutput =
        when {
            text.length > maxChars -> {
                val total = if (bytesTruncated) "more than ${text.length}" else "${text.length}"
                ShapedOutput(
                    text = text.take(maxChars) +
                        "... [truncated: showing $maxChars of $total chars; narrow with keepPaths or paginate]",
                    truncated = true,
                )
            }
            bytesTruncated ->
                ShapedOutput(
                    text = "$text... [truncated: response body exceeded the byte cap; " +
                        "narrow with keepPaths or paginate]",
                    truncated = true,
                )
            else -> ShapedOutput(text = text, truncated = false)
        }

    private val TEXTUAL_SUFFIXES = listOf("+json", "+xml", "+yaml")
    private val TEXTUAL_MEDIA_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
        "application/javascript",
        "application/x-www-form-urlencoded",
    )
}
