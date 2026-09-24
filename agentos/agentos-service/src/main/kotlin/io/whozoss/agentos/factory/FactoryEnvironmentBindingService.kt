package io.whozoss.agentos.factory

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.tool.ToolRegistryService
import io.whozoss.agentos.tool.ToolResolverService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the worktree root from Factory using trusted namespace/case identity and grants the
 * FILE_ACCESS plugin only after exact canonical equality is proven. Any ambiguity returns no tools.
 */
@Service
class FactoryEnvironmentBindingService(
    private val registry: ToolRegistryService,
    private val resolver: ToolResolverService,
    private val objectMapper: ObjectMapper,
    @Value("\${agentos.factory.base-url:http://localhost:3141}") private val baseUrl: String,
) {
    private val client = OkHttpClient()

    fun grantTools(workflowId: String?, caseId: String?, allowedTools: List<String>?, context: ToolContext): List<StandardTool<*>> {
        if (workflowId.isNullOrBlank() || caseId.isNullOrBlank()) return emptyList()
        val filePlugin = registry.findPlugin("FILE_ACCESS") ?: return emptyList()
        val encoded = java.net.URLEncoder.encode(workflowId, Charsets.UTF_8).replace("+", "%20")
        val actor = context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString() ?: return emptyList()
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/factory/workflows/$encoded/environment")
            .header("X-Factory-Namespace-Id", context.namespaceId.toString())
            .header("X-Factory-Case-Id", caseId)
            .header("X-Factory-Actor-Id", actor)
            .get().build()
        val data = runCatching { client.newCall(request).execute().use { response -> if (!response.isSuccessful) null else objectMapper.readTree(response.body?.string()).path("data") } }.getOrNull() ?: return emptyList()
        if (data.path("fileAccess").path("status").asText() != "bound") return emptyList()
        if (data.path("environment").path("parentCaseId").asText() != caseId) return emptyList()
        if (data.path("environment").path("workflowId").asText() != workflowId) return emptyList()
        val declared = data.path("environment").path("worktreePath").asText()
        val lexical = runCatching { Path.of(declared) }.getOrNull() ?: return emptyList()
        val root = runCatching { lexical.toRealPath() }.getOrNull() ?: return emptyList()
        if (!lexical.isAbsolute || lexical.normalize() != lexical || root.toString() != declared || !Files.isDirectory(root)) return emptyList()
        val config = objectMapper.createObjectNode().put("rootPath", root.toString()).put("readOnly", false)
        return filePlugin.provideTools(config, "work-unit", context).filter { resolver.isToolAllowed(it.name, "work-unit", allowedTools) }
    }
}
