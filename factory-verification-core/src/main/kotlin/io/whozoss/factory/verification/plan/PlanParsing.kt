package io.whozoss.factory.verification.plan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Analyst plan parsing and comparison — ported from `factory/lib/plan.mjs`.
 *
 * No network call, no mutable state. Only [checkPlanFiles] touches the filesystem,
 * read-only (`Files.exists`).
 *
 * A plan is the JSON object an analyst returns inside its markdown answer:
 * ```
 * { "files": ["relative/path.ts"], "doneWhen": "verifiable criterion", "steps": ["..."] }
 * ```
 * `files` is required and non-empty; absolute paths and `..` components are
 * rejected. `doneWhen` is required and non-empty. `steps` is optional.
 */
object PlanParsing {

    private val MAPPER = ObjectMapper()

    data class Plan(
        val files: List<String>,
        val doneWhen: String,
        val steps: List<String>? = null,
    )

    /** Result of parsing an analyst answer. */
    sealed interface ParseResult {
        data class Ok(val plan: Plan) : ParseResult
        data class Failure(val error: String) : ParseResult
    }

    /** Result of the plan-gate file existence check. */
    data class PlanCheck(
        val plannedFiles: List<String>,
        val missingFiles: List<String>,
        val fileCount: Int,
    )

    /** Result of the claims-gate comparison between plan and reality. */
    data class ClaimsComparison(
        val plannedFiles: List<String>,
        val actualFiles: List<String>,
        val unplannedFiles: List<String>,
        val untouchedPlannedFiles: List<String>,
        val claimsMatch: Boolean,
    )

    /**
     * Extracts the first JSON block of a markdown text.
     *
     * Three-pass strategy:
     *   1. ```` ```json ... ``` ```` block (fenced with language)
     *   2. ```` ``` ... ``` ```` block (fenced without language)
     *   3. first balanced brace in the raw text
     *
     * @return the raw JSON fragment, or `null` if nothing was found.
     */
    fun extractJsonFragment(text: String): String? {
        // Pass 1: ```json block
        val jsonFence = Regex("```json\\s*([\\s\\S]*?)```").find(text)
        if (jsonFence != null) return jsonFence.groupValues[1].trim()

        // Pass 2: fence without language
        val plainFence = Regex("```\\s*([\\s\\S]*?)```").find(text)
        if (plainFence != null) return plainFence.groupValues[1].trim()

        // Pass 3: first balanced brace
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Validates that a file path is safe for the plan-gate. A path is rejected if it
     * is absolute or contains a `..` component: a plan never leaves the repository.
     */
    fun isSafePath(p: String?): Boolean {
        if (p == null) return false
        val absolute = try {
            Path.of(p).isAbsolute
        } catch (_: InvalidPathException) {
            return false
        }
        if (absolute) return false
        if (p.split("/").contains("..")) return false
        return true
    }

    /** Parses and validates a plan from the raw text of the analyst answer. */
    fun parsePlan(agentMessage: String): ParseResult {
        val fragment = extractJsonFragment(agentMessage)
            ?: return ParseResult.Failure("Aucun bloc JSON trouvé dans la réponse de l'analyste.")

        val raw: JsonNode = try {
            MAPPER.readTree(fragment)
        } catch (e: Exception) {
            return ParseResult.Failure("JSON invalide : ${e.message}")
        }

        val filesNode = raw.get("files")
        if (filesNode == null || !filesNode.isArray || filesNode.isEmpty) {
            return ParseResult.Failure("Le plan doit contenir un champ \"files\" non vide.")
        }

        val files = filesNode.map { if (it.isTextual) it.asText() else it.toString() }
        for (f in files) {
            if (!isSafePath(f)) {
                return ParseResult.Failure(
                    "Chemin invalide dans \"files\" : \"$f\". " +
                        "Les chemins doivent être relatifs à la racine du dépôt " +
                        "(pas de chemin absolu, pas de \"..\")",
                )
            }
        }

        val doneWhenNode = raw.get("doneWhen")
        if (doneWhenNode == null || !doneWhenNode.isTextual || doneWhenNode.asText().trim().isEmpty()) {
            return ParseResult.Failure("Le plan doit contenir un champ \"doneWhen\" non vide.")
        }

        val stepsNode = raw.get("steps")
        val steps = if (stepsNode != null && stepsNode.isArray) {
            stepsNode.map { if (it.isTextual) it.asText() else it.toString() }
        } else {
            null
        }

        return ParseResult.Ok(
            Plan(files = files, doneWhen = doneWhenNode.asText(), steps = steps),
        )
    }

    /**
     * Verifies that every file declared in the plan already exists on disk.
     *
     * Assumed limitation: this gate does not cover new-file creation. `Files.exists`
     * returns `false` on a non-existent path even when its creation is intentional.
     */
    fun checkPlanFiles(files: List<String>, repoRoot: Path): PlanCheck {
        val missing = files.filter { !Files.exists(repoRoot.resolve(it)) }
        return PlanCheck(plannedFiles = files, missingFiles = missing, fileCount = files.size)
    }

    /**
     * Compares the files actually modified to the files announced by the plan.
     *
     * A mismatch does NOT fail the run — it is a fact to record, not a fault. An
     * editor may legitimately have to touch a neighbouring file it did not announce.
     */
    fun compareClaims(
        plannedFiles: List<String>,
        actualModified: List<String>,
        actualUntracked: List<String>,
    ): ClaimsComparison {
        val actualFiles = actualModified + actualUntracked
        val plannedSet = plannedFiles.toSet()
        val actualSet = actualFiles.toSet()

        val unplannedFiles = actualFiles.filter { it !in plannedSet }
        val untouchedPlannedFiles = plannedFiles.filter { it !in actualSet }
        val claimsMatch = unplannedFiles.isEmpty() && untouchedPlannedFiles.isEmpty()

        return ClaimsComparison(
            plannedFiles = plannedFiles,
            actualFiles = actualFiles,
            unplannedFiles = unplannedFiles,
            untouchedPlannedFiles = untouchedPlannedFiles,
            claimsMatch = claimsMatch,
        )
    }
}
