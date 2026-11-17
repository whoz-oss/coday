package io.whozoss.agentos.plugins.mcp

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema

/**
 * Builds the [McpJsonMapper] handed to every MCP transport of this plugin.
 *
 * ## Why not `McpJsonDefaults.getMapper()`
 *
 * `McpSchema.ResourceContents` is polymorphic and resolves its subtypes (`TextResourceContents`,
 * `BlobResourceContents`) by **class name**. Jackson loads those classes through its `TypeFactory`, which, unless a
 * class loader is bound to it, uses the *thread context class loader* of the thread decoding the message, then falls
 * back to its own class loader. The SDK decodes inbound messages on its own Reactor threads, whose context class
 * loader is the application one, and Jackson itself is provided by the application. `mcp-core` is bundled only inside
 * the PF4J plugin class loader, so neither can see `McpSchema$TextResourceContents` and any result carrying an
 * `EmbeddedResource` fails with `InvalidTypeIdException: Could not resolve type id ...: no such class found`.
 * Results made of text only keep working because `TextContent` is resolved by its `type` name, without loading a class.
 *
 * The mapper built here is the SDK default configuration (a plain [ObjectMapper] that ignores unknown properties)
 * with its `TypeFactory` bound to the class loader that defined [McpSchema], i.e. the plugin class loader. The thread
 * context class loader of the Reactor threads is deliberately left alone.
 */
internal object McpJsonMapperFactory {

    /** Shared instance: an [ObjectMapper] is thread-safe once configured. */
    val mapper: McpJsonMapper by lazy { JacksonMcpJsonMapper(createObjectMapper()) }

    /** Builds a new [ObjectMapper] whose class resolution is bound to the class loader of the MCP SDK classes. */
    fun createObjectMapper(): ObjectMapper =
        ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .also { it.typeFactory = it.typeFactory.withClassLoader(McpSchema::class.java.classLoader) }
}
