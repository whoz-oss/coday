package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.plugins.kotlin.KotlinToolTexts.PREFER_OVER_TEXT_SEARCH
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolSchema
import org.eclipse.lsp4j.FileRename
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/** Input of `kotlin_lsp_rename`. */
internal data class RenameInput(
    val symbol: String? = null,
    val newName: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val dryRun: Boolean? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** Input of `kotlin_lsp_rename_file`. */
internal data class RenameFileInput(
    val file: String? = null,
    val newPath: String? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/**
 * The tools that change the project on disk, like an IDE refactoring: rename a symbol, move a file. The edits come from
 * the server and are applied by [WorkspaceEditApplier], which refuses any edit outside the project or on a sensitive file.
 */
internal fun refactoringTools(
    configName: String?,
    run: KotlinLspRun,
): List<StandardTool<*>> = listOf(renameTool(configName = configName, run = run), renameFileTool(configName = configName, run = run))

private fun renameTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<RenameInput> =
    KotlinLspTool<RenameInput>(
        configName = configName,
        toolName = "kotlin_lsp_rename",
        description =
            describe(
                PREFER_OVER_TEXT_SEARCH,
                "Rename a Kotlin symbol (class, function, property...) everywhere it is used, like an IDE rename.",
                "APPLIES the changes on disk and returns the modified files. Strings, comments and config files are not touched: " +
                    "use a text search for those.",
                "With dryRun=true nothing is written: returns the edits as \"path:line:col-line:col → newName\" (1-based).",
                "To rename the file holding a class, use kotlin_lsp_rename_file.",
            ),
        inputSchema =
            KotlinSchemas.ofSymbol(
                extraProperties =
                    mapOf(
                        "newName" to ToolSchema.string(description = "New name for the symbol"),
                        "dryRun" to
                            KotlinSchemas.boolean(description = "Optional. true: only list the edits, write nothing. Default false."),
                    ),
                extraRequired = listOf("newName"),
            ),
        paramType = RenameInput::class.java,
        answer = { input, timeoutSeconds ->
            symbolInputError(symbol = input.symbol, file = input.file, line = input.line)
                ?: blankError(value = input.newName, name = "newName")
                ?: run.navigate(
                    timeoutSeconds = timeoutSeconds,
                    symbol = input.symbol.orEmpty(),
                    file = input.file,
                    line = input.line,
                    action = { lsp, target, _ ->
                        renameOf(lsp = lsp, target = target, newName = input.newName.orEmpty().trim(), dryRun = input.dryRun == true)
                    },
                )
        },
    )

private fun renameOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    newName: String,
    dryRun: Boolean,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val edit = lsp.textDocument.rename(RenameParams(TextDocumentIdentifier(uri), target.position, newName)).awaitResult()
    return when {
        edit == null || (edit.changes.isNullOrEmpty() && edit.documentChanges.isNullOrEmpty()) -> {
            KotlinToolResult.Error(text = "The server produced no change for renaming ${target.label}")
        }

        dryRun -> {
            KotlinToolResult.Success(text = describeEdits(edit = edit, paths = lsp.paths))
        }

        else -> {
            val applied = lsp.apply(edit = edit)
            KotlinToolResult.Success(
                text = "Renamed ${target.label} to $newName in:\n${lsp.display(
                    filePaths = applied.modified,
                )}${skippedNote(applied = applied, lsp = lsp)}",
            )
        }
    }
}

private fun renameFileTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<RenameFileInput> =
    KotlinLspTool<RenameFileInput>(
        configName = configName,
        toolName = "kotlin_lsp_rename_file",
        description =
            describe(
                "Move or rename a Kotlin file and fix everything that depends on it, like an IDE: imports, and the class named after the file.",
                "APPLIES the changes on disk and returns the modified files.",
                "Fails, and writes nothing, if the target exists or is outside the project.",
                "Not for a directory or a package.",
            ),
        inputSchema =
            KotlinSchemas.of(
                required = listOf("file", "newPath"),
                properties =
                    mapOf(
                        "file" to ToolSchema.string(description = "Project relative path of the Kotlin file to rename"),
                        "newPath" to
                            ToolSchema.string(description = "Project relative new path of the file, e.g. 'engine/src/.../NewName.kt'"),
                    ),
            ),
        paramType = RenameFileInput::class.java,
        answer = { input, timeoutSeconds ->
            val file = input.file.orEmpty().trim()
            val newPath = input.newPath.orEmpty().trim()
            blankError(value = input.file, name = "file")
                ?: blankError(value = input.newPath, name = "newPath")
                ?: run.refusal(files = listOf(file, newPath))
                ?: renameFileError(paths = run.paths, file = file, newPath = newPath)
                ?: run.execute(
                    timeoutSeconds = timeoutSeconds,
                    files = listOf(file, newPath),
                    work = { lsp -> renameFileOf(lsp = lsp, file = file, newPath = newPath) },
                )
        },
    )

/** Why [file] cannot be moved to [newPath], checked before the server is reached; null when it can. */
private fun renameFileError(
    paths: ProjectPaths,
    file: String,
    newPath: String,
): KotlinToolResult.Error? =
    when {
        !Path
            .of(
                paths.checked(filePath = file),
            ).exists() -> KotlinToolResult.Error(text = "File not found: ${paths.displayPath(filePath = file)}")

        Path
            .of(
                paths.checked(filePath = newPath),
            ).exists() -> KotlinToolResult.Error(text = "Target already exists: ${paths.displayPath(filePath = newPath)}")

        else -> null
    }

private fun renameFileOf(
    lsp: LspContext,
    file: String,
    newPath: String,
): KotlinToolResult {
    val from = Path.of(lsp.paths.checked(filePath = file))
    val to = Path.of(lsp.paths.checked(filePath = newPath))
    val fileRename = FileRename(lsp.paths.uriOf(filePath = file), lsp.paths.uriOf(filePath = newPath))
    val edit = lsp.workspace.willRenameFiles(RenameFilesParams(listOf(fileRename))).awaitResult()
    val applied = edit?.let { lsp.apply(edit = it) }
    val updated = applied?.modified.orEmpty().filter { it != from.toString() }
    // the server may already have moved it through a RenameFile operation
    if (from.exists()) {
        to.parent.createDirectories()
        Files.move(from, to)
    }
    lsp.connection.closeFile(filePath = from.toString())
    lsp.workspace.didRenameFiles(RenameFilesParams(listOf(fileRename)))
    lsp.connection.syncFile(filePath = to.toString())
    return KotlinToolResult.Success(
        text =
            "Renamed ${lsp.paths.displayPath(filePath = file)} to ${lsp.paths.displayPath(filePath = newPath)}" +
                "\nUpdated:\n${lsp.display(filePaths = updated)}${applied?.let { skippedNote(applied = it, lsp = lsp) }.orEmpty()}",
    )
}
