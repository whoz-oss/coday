package io.whozoss.agentos.plugins.factorybridge

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import mu.KLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the Factory-owned worktree root from trusted namespace/case identity and grants
 * the FILE_ACCESS tool set only after exact canonical equality is proven. Any ambiguity
 * grants nothing.
 *
 * Extraction note: the historical implementation depended on AgentOS service internals
 * (`ToolRegistryService`, `ToolResolverService`). The plugin instead receives a
 * [fileAccess] provider (resolving the `FILE_ACCESS` [ToolPlugin]) and an [isToolAllowed]
 * predicate, keeping the trust-critical logic (canonical path proof) identical while
 * removing the service dependency.
 *
 * @param fileAccess resolves the `FILE_ACCESS` plugin, or returns null when it is not loaded.
 * @param isToolAllowed allowlist predicate applied to the FILE_ACCESS tool names.
 */
class FactoryEnvironmentBindingService(
    private val fileAccess: () -> ToolPlugin?,
    private val isToolAllowed: (String) -> Boolean = { true },
    private val objectMapper: ObjectMapper,
    private val baseUrl: String,
    private val httpClient: OkHttpClient,
) {
    fun grantTools(
        workflowId: String?,
        caseId: String?,
        allowedTools: List<String>?,
        context: ToolContext,
    ): List<StandardTool<*>> {
        if (workflowId.isNullOrBlank() || caseId.isNullOrBlank()) return emptyList()
        val filePlugin = fileAccess() ?: return emptyList()
        val encoded = java.net.URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
        val actor =
            context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
                ?: return emptyList()
        val request =
            Request
                .Builder()
                .url("${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded/environment")
                .header("X-Factory-Namespace-Id", context.namespaceId.toString())
                .header("X-Factory-Case-Id", caseId)
                .header("X-Factory-Actor-Id", actor)
                .get()
                .build()
        val data =
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null else objectMapper.readTree(response.body?.string()).path("data")
                }
            }.getOrNull() ?: return emptyList()
        if (data.path("fileAccess").path("status").asText() != "bound") return emptyList()
        if (data.path("environment").path("parentCaseId").asText() != caseId) return emptyList()
        if (data.path("environment").path("workflowId").asText() != workflowId) return emptyList()
        val declared = data.path("environment").path("worktreePath").asText()
        val lexical = runCatching { Path.of(declared) }.getOrNull() ?: return emptyList()
        val root = runCatching { lexical.toRealPath() }.getOrNull() ?: return emptyList()
        if (!lexical.isAbsolute || lexical.normalize() != lexical || root.toString() != declared || !Files.isDirectory(root)) {
            return emptyList()
        }
        val config = objectMapper.createObjectNode().put("rootPath", root.toString()).put("readOnly", false)
        val allowlist = allowedTools?.toSet()
        return filePlugin
            .provideTools(config, "work-unit", context)
            .filter { allowlist == null || (it.name in allowlist) }
            .filter { isToolAllowed(it.name) }
    }

    companion object : KLogging()
}
