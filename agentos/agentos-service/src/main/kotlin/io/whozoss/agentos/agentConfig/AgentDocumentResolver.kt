package io.whozoss.agentos.agentConfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import mu.KLogging
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Resolves and formats document content for filesystem-backed agents.
 *
 * Paths in [AgentConfig.docs] are resolved relative to [AgentConfig.agentDir]
 * (the directory containing the agent YAML file), not the namespace configPath.
 *
 * Supports three path patterns:
 * - explicit file path: single file, content injected verbatim
 * - path ending with slash: directory listing (first-level entries, no content)
 * - path ending with slash-star: all readable files in the directory, content injected
 *
 * File reads are parallelised via coroutines. Errors on individual entries are
 * logged and skipped: a missing doc never aborts the agent.
 */
@Component
class AgentDocumentResolver {

    /**
     * Builds the formatted block to inject into agent instructions.
     *
     * Returns null when [docs] is empty/null or [agentDir] is null.
     */
    suspend fun buildDocsBlock(
        agentDir: String?,
        docs: List<String>?,
    ): String? {
        if (agentDir == null || docs.isNullOrEmpty()) return null

        val base = Path.of(agentDir)
        val resolved: List<Pair<String, String>> =
            coroutineScope {
                docs
                    .map { entry -> async { resolveMandatoryEntry(base, entry) } }
                    .flatMap { it.await() }
            }

        if (resolved.isEmpty()) return null

        return buildString {
            appendLine()
            appendLine("Mandatory documents")
            appendLine()
            appendLine("Each of the following files are included entirely as deemed important.")
            resolved.forEach { (label, content) ->
                appendLine()
                appendLine("File: $label")
                appendLine()
                appendLine(content)
            }
        }.trimEnd().takeIf { it.isNotBlank() }
    }

    private suspend fun resolveMandatoryEntry(
        base: Path,
        entry: String,
    ): List<Pair<String, String>> =
        when {
            entry.endsWith("/") -> resolveDirectoryListing(base, entry.trimEnd('/'))
            entry.endsWith("/*") -> resolveDirectoryFiles(base, entry.removeSuffix("/*"))
            else -> resolveSingleFile(base, entry)
        }

    private suspend fun resolveDirectoryListing(
        base: Path,
        relDir: String,
    ): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val dir = base.resolve(relDir)
            if (!dir.isDirectory()) {
                logger.warn { "[AgentDocumentResolver] Directory not found: $dir" }
                return@withContext emptyList()
            }
            val entries =
                Files
                    .list(dir)
                    .use { stream ->
                        stream
                            .sorted(Comparator.comparing { p: Path -> p.name })
                            .map { child ->
                                val suffix = if (child.isDirectory()) "/" else ""
                                "  - $relDir/${child.name}$suffix"
                            }.toList()
                    }
            listOf("$relDir/" to entries.joinToString("\n"))
        }

    private suspend fun resolveDirectoryFiles(
        base: Path,
        relDir: String,
    ): List<Pair<String, String>> =
        coroutineScope {
            val dir = base.resolve(relDir)
            if (!dir.isDirectory()) {
                logger.warn { "[AgentDocumentResolver] Directory not found: $dir" }
                return@coroutineScope emptyList()
            }
            val files =
                withContext(Dispatchers.IO) {
                    Files
                        .list(dir)
                        .use { stream ->
                            stream
                                .filter { it.isRegularFile() }
                                .sorted(Comparator.comparing { p: Path -> p.name })
                                .toList()
                        }
                }
            files
                .map { file ->
                    async {
                        val relPath = "$relDir/${file.name}"
                        readFileSafe(file, relPath)
                    }
                }.mapNotNull { it.await() }
        }

    private suspend fun resolveSingleFile(
        base: Path,
        relPath: String,
    ): List<Pair<String, String>> {
        val file = base.resolve(relPath)
        return listOfNotNull(readFileSafe(file, relPath))
    }

    private suspend fun readFileSafe(
        file: Path,
        label: String,
    ): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!file.isRegularFile()) {
                logger.warn { "[AgentDocumentResolver] Not a regular file: $file" }
                return@withContext null
            }
            runCatching { label to file.readText() }
                .onFailure { logger.warn(it) { "[AgentDocumentResolver] Could not read $file" } }
                .getOrNull()
        }

    companion object : KLogging()
}
