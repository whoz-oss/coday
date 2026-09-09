package io.whozoss.agentos.plugins.http.cache

import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.OperationOverride
import io.whozoss.agentos.plugins.http.file.FileStamp
import java.security.MessageDigest

/**
 * SHA-256 identity of the part of an [HttpApiConfig] that determines the catalogue: the document source,
 * `baseUrl`, the curation filters, `maxTools`, `allowMutations`, the per-operation overrides, the response
 * defaults, the API key placement (it decides the effective placement and which header parameters are
 * reserved) and the default header names (a name equal to the effective API key header fails the load).
 * Transport settings (default header values, timeouts, concurrency, refresh interval) are excluded, and no
 * credential material is ever part of the key.
 *
 * An inline document contributes its own SHA-256 rather than its text, so the key stays short; a file document
 * contributes its path and its [FileStamp] (last modification time and size), so a changed file is a new key.
 *
 * @param fileStamp The current stamp of the `spec.file` document, null when it cannot be read or for other sources.
 */
object HttpApiConfigHash {

    fun of(config: HttpApiConfig, fileStamp: FileStamp? = null): String {
        val canonical = buildString {
            field("spec.url", config.spec.url)
            field("spec.inline", config.spec.inline?.let(::sha256))
            field("spec.file", config.spec.file)
            field("spec.file.lastModified", fileStamp?.lastModifiedMillis?.toString())
            field("spec.file.size", fileStamp?.size?.toString())
            field("baseUrl", config.baseUrl)
            list("includeTags", config.includeTags)
            list("includePathPrefixes", config.includePathPrefixes)
            list("includeOperations", config.includeOperations)
            list("excludeOperations", config.excludeOperations)
            field("maxTools", config.maxTools.toString())
            field("allowMutations", config.allowMutations.toString())
            field("auth.apiKeyIn", config.auth.apiKeyIn.name)
            field("auth.apiKeyName", config.auth.apiKeyName)
            list("defaultHeaders.names", config.defaultHeaders.keys.sorted())
            config.operations.forEachIndexed { i, override -> operation(i, override) }
            field("responseFormat", config.responseFormat.name)
            field("maxResponseChars", config.maxResponseChars.toString())
        }
        return sha256(canonical)
    }

    private fun StringBuilder.operation(index: Int, override: OperationOverride) {
        field("operations[$index].operationId", override.operationId)
        field("operations[$index].description", override.description)
        list("operations[$index].keepPaths", override.keepPaths)
        list("operations[$index].ignorePaths", override.ignorePaths)
        field("operations[$index].responseFormat", override.responseFormat?.name)
        field("operations[$index].maxResponseChars", override.maxResponseChars?.toString())
    }

    /** `name=<length>:<value>` per line: the length prefix keeps values containing separators unambiguous. */
    private fun StringBuilder.field(name: String, value: String?) {
        append(name).append('=')
        if (value != null) append(value.length).append(':').append(value)
        append('\n')
    }

    private fun StringBuilder.list(name: String, values: List<String>) {
        field("$name.size", values.size.toString())
        values.forEachIndexed { i, value -> field("$name[$i]", value) }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
