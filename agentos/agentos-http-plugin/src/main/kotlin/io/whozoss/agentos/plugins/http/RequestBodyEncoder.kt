package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Outcome of [RequestBodyEncoder.encode]. */
sealed interface BodyEncoding {
    class Encoded(val body: RequestBody) : BodyEncoding

    /** The `body` argument does not fit the media type; [reason] is meant for the LLM. */
    class Invalid(val reason: String) : BodyEncoding
}

/**
 * Serialises the `body` argument of a tool call for the media type the operation declares:
 * `application/json` as compact JSON, `application/x-www-form-urlencoded` as form fields where a nested
 * value is JSON-encoded and a null value is skipped.
 */
object RequestBodyEncoder {

    fun encode(value: JsonNode, mediaType: String): BodyEncoding =
        when (mediaType) {
            JSON -> BodyEncoding.Encoded(HttpApiJson.mapper.writeValueAsString(value).toRequestBody(JSON_MEDIA_TYPE))
            FORM -> encodeForm(value)
            else -> BodyEncoding.Invalid("request body media type '$mediaType' is not supported")
        }

    private fun encodeForm(value: JsonNode): BodyEncoding {
        if (!value.isObject) return BodyEncoding.Invalid("'body' must be an object of form fields for this operation")
        val form = FormBody.Builder()
        value.properties()
            .filterNot { (_, field) -> field.isNull }
            .forEach { (name, field) -> form.add(name = name, value = textOf(field)) }
        return BodyEncoding.Encoded(form.build())
    }

    private fun textOf(node: JsonNode): String =
        if (node.isValueNode) node.asText() else HttpApiJson.mapper.writeValueAsString(node)

    private const val JSON = "application/json"
    private const val FORM = "application/x-www-form-urlencoded"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
