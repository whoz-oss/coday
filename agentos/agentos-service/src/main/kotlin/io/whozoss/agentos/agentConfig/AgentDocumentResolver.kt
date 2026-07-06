package io.whozoss.agentos.agentConfig

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
 * Supports the same three path patterns as Coday:
 * - single file: content injected verbatim
 * - directory listing (path ending with slash): first-level entries listed, no content
 * - directory glob (path ending with slash-star): all readable files, content injected
 *
 * All paths are resolved relative to [configPath] (the namespace base directory).
 * Errors on individual files are logged and skipped: a missing doc never aborts the agent.
 */
@Component
class AgentDocumentResolver {

    /**
     * Builds the formatted block to inject into agent instructions.
     *
     * Returns null when both [mandatoryDocs] and [optionalDocs] are empty/null,
     * or when [configPath] is null (no filesystem base to resolve against).
     */
    fun buildDocsBlock(
        configPath: String?,
        docs: List<String>?,
    ): String? {
        if (configPath == null || docs.isNullOrEmpty()) return null

        val base = Path.of(configPath)
        return buildString {
            appendLine()
            appendLine("Mandatory documents")
            appendLine()
            appendLine("Each of the following files are included entirely as deemed important.")
            docs.forEach { entry ->
                val resolved = resolveMandatoryEntry(base, entry)
                resolved.forEach { (label, content) ->
                    appendLine()
                    appendLine("File: $label")
                    appendLine()
                    appendLine(content)
                }
            }
        }.trimEnd().takeIf { it.isNotBlank() }
    }

    private fun resolveMandatoryEntry(
        base: Path,
        entry: String,
    ): List<Pair<String, String>> =
        when {
            entry.endsWith("/") -> resolveDirectoryListing(base, entry.trimEnd('/'))
            entry.endsWith("/*") -> resolveDirectoryFiles(base, entry.removeSuffix("/*"))
            else -> resolveSingleFile(base, entry)
        }

    private fun resolveDirectoryListing(
        base: Path,
        relDir: String,
    ): List<Pair<String, String>> {
        val dir = base.resolve(relDir)
        if (!dir.isDirectory()) {
            logger.warn { "[AgentDocumentResolver] Directory not found: $dir" }
            return emptyList()
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
        val listing = entries.joinToString("\n")
        return listOf("$relDir/" to listing)
    }

    private fun resolveDirectoryFiles(
        base: Path,
        relDir: String,
    ): List<Pair<String, String>> {
        val dir = base.resolve(relDir)
        if (!dir.isDirectory()) {
            logger.warn { "[AgentDocumentResolver] Directory not found: $dir" }
            return emptyList()
        }
        return Files
            .list(dir)
            .use { stream ->
                stream
                    .filter { it.isRegularFile() }
                    .sorted(Comparator.comparing { p: Path -> p.name })
                    .toList()
            }.mapNotNull { file ->
                val relPath = "$relDir/${file.name}"
                readFileSafe(file, relPath)
            }
    }

    private fun resolveSingleFile(
        base: Path,
        relPath: String,
    ): List<Pair<String, String>> {
        val file = base.resolve(relPath)
        return listOfNotNull(readFileSafe(file, relPath))
    }

    private fun readFileSafe(
        file: Path,
        label: String,
    ): Pair<String, String>? {
        if (!file.isRegularFile()) {
            logger.warn { "[AgentDocumentResolver] Not a regular file: $file" }
            return null
        }
        return runCatching { label to file.readText() }
            .onFailure { logger.warn(it) { "[AgentDocumentResolver] Could not read $file" } }
            .getOrNull()
    }

    companion object : KLogging()
}
