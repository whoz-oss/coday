package io.whozoss.agentos.sdk.caseEvent

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Structured content for messages.
 * Can represent text, image or audio content in a message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageContent.Text::class, name = "Text"),
    JsonSubTypes.Type(value = MessageContent.Image::class, name = "Image"),
    JsonSubTypes.Type(value = MessageContent.Audio::class, name = "Audio"),
)
sealed interface MessageContent {
    /**
     * Plain text content.
     */
    data class Text(
        val content: String,
    ) : MessageContent

    /**
     * Image content (base64 encoded).
     *
     * The explicit allOf keeps the OpenAPI composition with [MessageContent] (and its
     * `type` discriminator) even though the type is also referenced directly (outside
     * the sealed-interface oneOf) by [ToolResponseEvent.images].
     */
    @Schema(allOf = [MessageContent::class])
    data class Image(
        val content: String, // base64 encoded
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
    ) : MessageContent

    /**
     * Audio content (a recorded voice message).
     *
     * The audio itself is not inlined: it is stored in the case file exchange and referenced by
     * [path] (relative to the case exchange root, as used by the `/files/download?path=` endpoint).
     * Not textual: it is never sent to the LLM, the accompanying [Text] (transcript) is.
     */
    data class Audio(
        val path: String,
        val mimeType: String,
        val durationMs: Long? = null,
    ) : MessageContent
}
