package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.agentos.sdk.caseEvent.MessageContent

/**
 * Utility for serialising/deserialising [MessageContent] values to and from
 * JSON strings stored as Neo4j node properties.
 *
 * Uses a module-local [ObjectMapper] with [MessageContent]'s Jackson polymorphism
 * already configured via the sealed interface annotations. This avoids injecting
 * the application-wide [ObjectMapper] into every node class.
 *
 * Only used for fields that cannot be stored as a plain scalar:
 * - [List]<[MessageContent]> — for [MessageEventNode.contentJson]
 * - Single [MessageContent] — for [ToolResponseEventNode.outputJson]
 * - [List]<[String]> — for [QuestionEventNode.options]
 */
internal object MessageContentSerializer {
    private val mapper: ObjectMapper = jacksonObjectMapper()

    fun serialize(content: List<MessageContent>): String = mapper.writeValueAsString(content)

    fun deserialize(json: String): List<MessageContent> = mapper.readValue(json)

    fun serializeSingle(content: MessageContent): String = mapper.writeValueAsString(content)

    fun deserializeSingle(json: String): MessageContent = mapper.readValue(json)

    fun serializeStringList(list: List<String>): String = mapper.writeValueAsString(list)

    fun deserializeStringList(json: String): List<String> = mapper.readValue(json)
}
