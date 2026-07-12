package io.whozoss.agentos.a2a.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * A2A `Part` — smallest unit of content in a Message or Artifact.
 *
 * Prototype scope: only [TextPart] and [DataPart] are used on the wire.
 * File parts (uploads) are declared for spec completeness but not implemented server-side.
 *
 * The wire discriminator is `kind` per A2A v1 spec (§4.1.6).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = A2APart.TextPart::class, name = "text"),
    JsonSubTypes.Type(value = A2APart.FilePart::class, name = "file"),
    JsonSubTypes.Type(value = A2APart.DataPart::class, name = "data"),
)
@JsonIgnoreProperties(ignoreUnknown = true)
sealed interface A2APart {
    val metadata: Map<String, Any?>?

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TextPart(
        val text: String,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        override val metadata: Map<String, Any?>? = null,
    ) : A2APart

    /**
     * Prototype: not consumed server-side. Declared so incoming file parts are not
     * rejected as unknown types; treated as text if forced through the pipeline.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FilePart(
        val file: FileContent,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        override val metadata: Map<String, Any?>? = null,
    ) : A2APart

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DataPart(
        val data: Map<String, Any?>,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        override val metadata: Map<String, Any?>? = null,
    ) : A2APart
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileContent(
    @JsonInclude(JsonInclude.Include.NON_NULL) val name: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val mimeType: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL) val bytes: String? = null, // base64
    @JsonInclude(JsonInclude.Include.NON_NULL) val uri: String? = null,
)
