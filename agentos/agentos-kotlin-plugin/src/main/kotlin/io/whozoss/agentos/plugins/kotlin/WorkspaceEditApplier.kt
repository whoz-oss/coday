package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.DeleteFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Files touched by [WorkspaceEditApplier.apply], as absolute paths: what the server must be told about. */
internal data class AppliedEdit(
    /** Files that exist and whose content is new. */
    val modified: List<String>,
    /** Files that no longer exist at that path. */
    val vacated: List<String>,
    /** Files the server wanted edited but that do not exist: its index is stale. */
    val skipped: List<String>,
)

/**
 * Applies a server [WorkspaceEdit] (rename, file move) on disk.
 *
 * Every URI goes through [ProjectPaths.checked] before anything is written: inside the project, not through a
 * link that leads out of it, and never a sensitive file. The server is a third-party binary, its edits are not trusted.
 * shortcut: no rollback, an I/O failure half way leaves the edits already applied, upgrade when a real failure is seen.
 */
internal class WorkspaceEditApplier(
    private val paths: ProjectPaths,
) {
    /**
     * Applies [edit] and reports the files it touched.
     *
     * @throws IllegalArgumentException when an edit leaves the project, or targets a sensitive file or an existing target
     */
    fun apply(edit: WorkspaceEdit): AppliedEdit {
        val operations = operationsOf(edit)
        operations.forEach { op -> op.uris.forEach { requireInProject(it) } }
        val modified = linkedSetOf<String>()
        val vacated = linkedSetOf<String>()
        val skipped = linkedSetOf<String>()
        operations.forEach { it.run(modified, vacated, skipped) }
        return AppliedEdit(modified = modified.filter { Path.of(it).exists() }, vacated = vacated.toList(), skipped = skipped.toList())
    }

    private class Operation(
        val uris: List<String>,
        val run: (modified: MutableSet<String>, vacated: MutableSet<String>, skipped: MutableSet<String>) -> Unit,
    )

    private fun operationsOf(edit: WorkspaceEdit): List<Operation> {
        val fromDocumentChanges =
            edit.documentChanges.orEmpty().map { change ->
                if (change.isLeft) {
                    val docEdit = change.left
                    editOperation(uri = docEdit.textDocument.uri, edits = docEdit.edits.mapNotNull { it.left })
                } else {
                    resourceOperation(change.right)
                }
            }
        val fromChanges = edit.changes.orEmpty().map { (uri, edits) -> editOperation(uri = uri, edits = edits) }
        return fromDocumentChanges + fromChanges
    }

    private fun editOperation(
        uri: String,
        edits: List<TextEdit>,
    ) = Operation(listOf(uri)) { modified, _, skipped ->
        val file = fileOf(uri)
        if (file.exists()) {
            file.writeText(applyTextEdits(text = file.readText(), edits = edits))
            modified += file.toString()
        } else {
            skipped += file.toString()
        }
    }

    private fun resourceOperation(op: ResourceOperation): Operation =
        when (op) {
            is RenameFile -> {
                Operation(listOf(op.oldUri, op.newUri)) { modified, vacated, _ ->
                    val from = fileOf(op.oldUri)
                    val to = fileOf(op.newUri)
                    val overwrite = op.options?.overwrite == true
                    require(overwrite || !to.exists()) { "Target already exists: ${paths.displayPath(to.toString())}" }
                    to.parent.createDirectories()
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
                    modified.remove(from.toString())
                    vacated += from.toString()
                    modified += to.toString()
                }
            }

            is CreateFile -> {
                Operation(listOf(op.uri)) { modified, _, _ ->
                    val file = fileOf(op.uri)
                    require(!file.exists() || op.options?.overwrite == true || op.options?.ignoreIfExists == true) {
                        "File already exists: ${paths.displayPath(file.toString())}"
                    }
                    if (!file.exists()) {
                        file.parent.createDirectories()
                        Files.createFile(file)
                        modified += file.toString()
                    }
                }
            }

            is DeleteFile -> {
                Operation(listOf(op.uri)) { modified, vacated, _ ->
                    val file = fileOf(op.uri)
                    file.toFile().deleteRecursively()
                    modified.remove(file.toString())
                    vacated += file.toString()
                }
            }

            else -> {
                error("Unsupported workspace operation: ${op.kind}")
            }
        }

    private fun requireInProject(uri: String) {
        val filePath = requireNotNull(paths.filePathOf(uri = uri)) { "Edit outside the project refused: $uri" }
        paths.checked(filePath = filePath)
    }

    private fun fileOf(uri: String): Path = Path.of(checkNotNull(paths.filePathOf(uri = uri)) { "Not a file uri: $uri" })

    private fun applyTextEdits(
        text: String,
        edits: List<TextEdit>,
    ): String {
        val lineStarts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

        fun offsetOf(position: Position): Int =
            if (position.line >= lineStarts.size) {
                text.length
            } else {
                val lineEnd = lineStarts.getOrNull(position.line + 1) ?: text.length
                minOf(lineStarts[position.line] + position.character, lineEnd)
            }
        // From the end, so earlier offsets stay valid; reversed first so equal starts keep the edit list order.
        val fromEnd =
            edits
                .reversed()
                .sortedWith(compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })
        return StringBuilder(text)
            .apply { fromEnd.forEach { replace(offsetOf(it.range.start), offsetOf(it.range.end), it.newText) } }
            .toString()
    }
}
