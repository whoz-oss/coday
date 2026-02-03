package io.whozoss.agentos.sdk.model

/**
 * Structured content for messages.
 * Can represent text or image content in a message.
 */
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
