package io.whozoss.factory.forge.domain

import java.nio.file.Files
import java.nio.file.Path

/** A validated analyst plan. */
data class ForgePlan(val files: List<String>, val doneWhen: String, val steps: List<String>?)

/** Port of `factory/lib/plan.mjs` (plan parsing and the plan-gate). */
object ForgePlanParser {

    /**
     * Extract the first JSON block from a markdown text (fenced ```json,
     * fenced ```, or the first balanced brace run).
     */
    fun extractJsonFragment(text: String): String? {
        val jsonFence = Regex("```json\\s*([\\s\\S]*?)```").find(text)
        if (jsonFence != null) return jsonFence.groupValues[1].trim()
        val plainFence = Regex("```\\s*([\\s\\S]*?)```").find(text)
        if (plainFence != null) return plainFence.groupValues[1].trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** Rejects absolute paths and paths escaping the repo root. */
    fun isSafePath(path: Any?): Boolean {
        if (path !is String) return false
        if (Path.of(path).isAbsolute) return false
        if (path.split("/").contains("..")) return false
        return true
    }

    /** Parse and validate an analyst plan from the raw markdown response. */
    fun parsePlan(agentMessage: String): Result<ForgePlan> {
        val fragment = extractJsonFragment(agentMessage)
            ?: return Result.failure(ForgeCodedException("PLAN_MISSING", "Aucun bloc JSON trouvé dans la réponse de l'analyste."))
        val raw = try {
            ForgeJson.parseObject(fragment)
        } catch (error: Exception) {
            return Result.failure(ForgeCodedException("PLAN_INVALID_JSON", "JSON invalide : ${error.message}"))
        }
        val files = raw["files"]
        if (files !is List<*> || files.isEmpty()) {
            return Result.failure(ForgeCodedException("PLAN_INVALID", "Le plan doit contenir un champ \"files\" non vide."))
        }
        for (file in files) {
            if (!isSafePath(file)) {
                return Result.failure(
                    ForgeCodedException(
                        "PLAN_INVALID_PATH",
                        "Chemin invalide dans \"files\" : \"$file\". " +
                            "Les chemins doivent être relatifs à la racine du dépôt (pas de chemin absolu, pas de \"..\")",
                    ),
                )
            }
        }
        val doneWhen = raw["doneWhen"]
        if (doneWhen !is String || doneWhen.trim().isEmpty()) {
            return Result.failure(ForgeCodedException("PLAN_INVALID", "Le plan doit contenir un champ \"doneWhen\" non vide."))
        }
        val steps = raw["steps"]
        return Result.success(
            ForgePlan(
                files = files.map { it.toString() },
                doneWhen = doneWhen,
                steps = if (steps is List<*>) steps.map { it.toString() } else null,
            ),
        )
    }

    /** The plan-gate: verify each declared file already exists on disk. */
    fun checkPlanFiles(files: List<String>, repoRoot: String): Map<String, Any?> {
        val missing = files.filter { !Files.exists(Path.of(repoRoot, it)) }
        return linkedMapOf(
            "plannedFiles" to files,
            "missingFiles" to missing,
            "fileCount" to files.size,
        )
    }

    /** Claims-gate: compare the announced plan to the files actually changed. */
    fun compareClaims(
        plannedFiles: List<String>,
        actualModified: List<String>,
        actualUntracked: List<String>,
    ): Map<String, Any?> {
        val actualFiles = actualModified + actualUntracked
        val plannedSet = plannedFiles.toSet()
        val actualSet = actualFiles.toSet()
        val unplannedFiles = actualFiles.filter { it !in plannedSet }
        val untouchedPlannedFiles = plannedFiles.filter { it !in actualSet }
        return linkedMapOf(
            "plannedFiles" to plannedFiles,
            "actualFiles" to actualFiles,
            "unplannedFiles" to unplannedFiles,
            "untouchedPlannedFiles" to untouchedPlannedFiles,
            "claimsMatch" to (unplannedFiles.isEmpty() && untouchedPlannedFiles.isEmpty()),
        )
    }
}
