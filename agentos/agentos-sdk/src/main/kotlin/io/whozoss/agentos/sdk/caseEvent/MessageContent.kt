package io.whozoss.agentos.sdk.caseEvent

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Structured content for messages.
 * Can represent text or image content in a message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MessageContent.Text::class, name = "Text"),
    JsonSubTypes.Type(value = MessageContent.Image::class, name = "Image"),
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
     */
    data class Image(
        val content: String, // base64 encoded
        val mimeType: String,
        val width: Int? = null,
        val height: Int? = null,
    ) : MessageContent
}
