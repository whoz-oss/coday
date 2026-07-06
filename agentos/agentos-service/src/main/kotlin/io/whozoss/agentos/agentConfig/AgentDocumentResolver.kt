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
 * Entries in [AgentConfig.docs] are absolute paths pre-resolved at load time by
 * [FilesystemAgentConfigRepository] relative to the agent YAML file. This component
 * only reads and formats them — no base-path arithmetic needed here.
 *
 * Three path patterns are supported:
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
     * Returns null when [docs] is empty or null.
     */
    suspend fun buildDocsBlock(docs: List<String>?): String? {
        if (docs.isNullOrEmpty()) return null

        val resolved: List<Pair<String, String>> =
            coroutineScope {
                docs
                    .map { entry -> async { resolveEntry(entry) } }
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

    private suspend fun resolveEntry(entry: String): List<Pair<String, String>> =
        when {
            entry.endsWith("/") -> resolveDirectoryListing(Path.of(entry.trimEnd('/')))
            entry.endsWith("/*") -> resolveDirectoryFiles(Path.of(entry.removeSuffix("/*")))
            else -> resolveSingleFile(Path.of(entry))
        }

    private suspend fun resolveDirectoryListing(dir: Path): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
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
                                "  - ${child.name}$suffix"
                            }.toList()
                    }
            listOf("${dir.name}/" to entries.joinToString("\n"))
        }

    private suspend fun resolveDirectoryFiles(dir: Path): List<Pair<String, String>> =
        coroutineScope {
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
                .map { file -> async { readFileSafe(file) } }
                .mapNotNull { it.await() }
        }

    private suspend fun resolveSingleFile(file: Path): List<Pair<String, String>> =
        listOfNotNull(readFileSafe(file))

    private suspend fun readFileSafe(file: Path): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (!file.isRegularFile()) {
                logger.warn { "[AgentDocumentResolver] Not a regular file: $file" }
                return@withContext null
            }
            runCatching { file.name to file.readText() }
                .onFailure { logger.warn(it) { "[AgentDocumentResolver] Could not read $file" } }
                .getOrNull()
        }

    companion object : KLogging()
}
