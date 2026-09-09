package io.whozoss.agentos.plugins.http.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/** Generators of body schemas large enough to trip the [JsonSchemaBuilder] size guard. */
object DeepSchemas {

    /** An object with [count] properties, each nesting objects four levels deep (`p<i>.l3.l4.value`). */
    fun deepProperties(count: Int): ObjectNode {
        val root = JsonNodeFactory.instance.objectNode().put("type", "object")
        val props = root.putObject("properties")
        repeat(count) { i -> props.set<JsonNode>("p$i", deepMember(i)) }
        return root
    }

    /** A `oneOf` of [count] members, each nesting objects four levels deep (`oneOf[i].l3.l4.value`). */
    fun deepOneOf(count: Int): ObjectNode {
        val root = JsonNodeFactory.instance.objectNode()
        val members = root.putArray("oneOf")
        repeat(count) { i -> members.add(deepMember(i)) }
        return root
    }

    private fun deepMember(index: Int): ObjectNode {
        val level4 = JsonNodeFactory.instance.objectNode().put("type", "object").put("title", "Leaf$index")
        level4.putObject("properties").putObject("value").put("type", "string").put("description", "x".repeat(100))
        val level3 = JsonNodeFactory.instance.objectNode().put("type", "object")
        level3.putObject("properties").set<JsonNode>("l4", level4)
        val level2 = JsonNodeFactory.instance.objectNode().put("type", "object")
        level2.putObject("properties").set<JsonNode>("l3", level3)
        return level2
    }
}
