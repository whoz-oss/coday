package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.agentos.sdk.caseEvent.MessageContent

/**
 * Utility for serialising/deserialising [MessageContent] values to and from
 * JSON strings stored as Neo4j node properties.
 *
 * Receives the application-wide [ObjectMapper] (injected via [Neo4jCaseEventRepository])
 * which has [MessageContent]'s Jackson polymorphism correctly configured via
 * the sealed interface annotations and the registered KotlinModule.
 *
 * Only used for fields that cannot be stored as a plain scalar:
 * - [List]<[MessageContent]> — for [MessageEventNode.contentJson]
 * - Single [MessageContent]  — for [ToolResponseEventNode.outputJson]
 * - [List]<[String]>         — for [QuestionEventNode.options]
 * - [Map]<[String],[Any?]>   — for [ToolResponseEventNode.metadataJson]
 */
class MessageContentSerializer(
    private val mapper: ObjectMapper,
) {
    // Use a typed writer so Jackson emits the @JsonTypeInfo discriminator for each element.
    // Without this, elements are serialised as their concrete type and the 'type' property
    // is omitted, causing InvalidTypeIdException on deserialisation.
    private val listWriter =
        mapper
            .writerFor(mapper.typeFactory.constructCollectionType(List::class.java, MessageContent::class.java))

    fun serialize(content: List<MessageContent>): String = listWriter.writeValueAsString(content)

    fun deserialize(json: String): List<MessageContent> =
        mapper.readValue(
            json,
            mapper.typeFactory.constructCollectionType(List::class.java, MessageContent::class.java),
        )

    fun serializeSingle(content: MessageContent): String = mapper.writerFor(MessageContent::class.java).writeValueAsString(content)

    fun deserializeSingle(json: String): MessageContent = mapper.readValue(json, MessageContent::class.java)

    fun serializeStringList(list: List<String>): String = mapper.writeValueAsString(list)

    fun deserializeStringList(json: String): List<String> = mapper.readValue(json)

    fun serializeMetadata(metadata: Map<String, Any?>): String = mapper.writeValueAsString(metadata)

    fun deserializeMetadata(json: String): Map<String, Any?> = mapper.readValue(json)
}
