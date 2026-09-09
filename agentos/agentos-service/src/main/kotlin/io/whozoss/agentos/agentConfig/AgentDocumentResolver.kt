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
 *
 * Security: each resolved path is validated against SENSITIVE_FILE_PATTERNS before
 * reading. YAML is admin-authored, but this deny-list prevents accidental injection
 * of secrets when a slash-star glob covers a directory that happens to contain a .env
 * or a private key.
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
            if (isSensitiveFile(file.name)) {
                logger.warn { "[AgentDocumentResolver] Skipping sensitive file: $file" }
                return@withContext null
            }
            runCatching { file.name to file.readText() }
                .onFailure { logger.warn(it) { "[AgentDocumentResolver] Could not read $file" } }
                .getOrNull()
        }

    companion object : KLogging() {
        private val SENSITIVE_FILE_PATTERNS =
            listOf(
                ".env",
                ".env.*",
                "credentials.json",
                "*.key",
                "*.pem",
                "token.json",
                "auth-profiles.json",
                "*.p12",
                "*.pfx",
                "id_rsa",
                "id_dsa",
                "id_ecdsa",
                "id_ed25519",
            )

        private fun isSensitiveFile(fileName: String): Boolean =
            SENSITIVE_FILE_PATTERNS.any { pattern -> matchesGlob(fileName, pattern) }

        private fun matchesGlob(
            name: String,
            pattern: String,
        ): Boolean {
            val regex =
                Regex(
                    pattern
                        .split("*")
                        .joinToString(".*") { Regex.escape(it) },
                    RegexOption.IGNORE_CASE,
                )
            return regex.matches(name)
        }
    }
}
