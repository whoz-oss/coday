package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolSchema
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/** Input of `kotlin_lsp_formatting`. */
internal data class FormattingInput(
    val file: String? = null,
    val tabSize: Int? = null,
    val insertSpaces: Boolean? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** Input of the tools working on a position, 0-based as the LSP protocol. */
internal data class PositionInput(
    val file: String? = null,
    val line: Int? = null,
    val character: Int? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** Input of the tools working on a range, 0-based as the LSP protocol. */
internal data class RangeInput(
    val file: String? = null,
    val startLine: Int? = null,
    val startCharacter: Int? = null,
    val endLine: Int? = null,
    val endCharacter: Int? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

private const val MAX_COMPLETIONS = 20
private const val DEFAULT_TAB_SIZE = 4
private const val POSITION_REQUIRED = "`line` and `character` are required, 0-based (0 or more)"
private const val RANGE_REQUIRED = "`startLine`, `startCharacter`, `endLine` and `endCharacter` are required, 0-based (0 or more)"

/**
 * The tools that work on a file or a position, as an editor does: completion, signature help, diagnostics, code actions,
 * formatting, inlay hints. Positions are 0-based, as the LSP protocol. No text search competes with them.
 */
internal fun editorTools(
    configName: String?,
    run: KotlinLspRun,
): List<StandardTool<*>> =
    listOf(
        completionTool(configName = configName, run = run),
        signatureHelpTool(configName = configName, run = run),
        diagnosticsTool(configName = configName, run = run),
        codeActionsTool(configName = configName, run = run),
        formattingTool(configName = configName, run = run),
        inlayHintsTool(configName = configName, run = run),
    )

private fun positionSchema(): String =
    KotlinSchemas.of(
        required = listOf("file", "line", "character"),
        properties =
            mapOf(
                "file" to ToolSchema.string(description = KotlinToolTexts.SOURCE_FILE_PARAM),
                "line" to ToolSchema.integer(description = "Line number, ${KotlinToolTexts.ZERO_BASED}"),
                "character" to ToolSchema.integer(description = "Character offset on the line, ${KotlinToolTexts.ZERO_BASED}"),
            ),
    )

private fun rangeSchema(): String =
    KotlinSchemas.of(
        required = listOf("file", "startLine", "startCharacter", "endLine", "endCharacter"),
        properties =
            mapOf(
                "file" to ToolSchema.string(description = KotlinToolTexts.SOURCE_FILE_PARAM),
                "startLine" to ToolSchema.integer(description = "Start line of the range, ${KotlinToolTexts.ZERO_BASED}"),
                "startCharacter" to ToolSchema.integer(description = "Start character of the range, ${KotlinToolTexts.ZERO_BASED}"),
                "endLine" to ToolSchema.integer(description = "End line of the range, ${KotlinToolTexts.ZERO_BASED}"),
                "endCharacter" to ToolSchema.integer(description = "End character of the range, ${KotlinToolTexts.ZERO_BASED}"),
            ),
    )

private fun fileSchema(): String =
    KotlinSchemas.of(
        required = listOf("file"),
        properties = mapOf("file" to ToolSchema.string(description = KotlinToolTexts.SOURCE_FILE_PARAM)),
    )

/** The 0-based position of [line] and [character], null when one is missing or negative. */
private fun positionOf(
    line: Int?,
    character: Int?,
): Position? = if (line != null && character != null && line >= 0 && character >= 0) Position(line, character) else null

/** The range of [input], null when one of its bounds is missing or negative. */
private fun rangeOf(input: RangeInput): Range? {
    val start = positionOf(line = input.startLine, character = input.startCharacter)
    val end = positionOf(line = input.endLine, character = input.endCharacter)
    return if (start != null && end != null) Range(start, end) else null
}

/** Runs [work] on [file] at [position], once both are valid; the reason otherwise. */
private fun atPosition(
    run: KotlinLspRun,
    input: PositionInput,
    timeoutSeconds: Int,
    work: (lsp: LspContext, uri: String, position: Position) -> KotlinToolResult,
): KotlinToolResult =
    blankError(value = input.file, name = "file")
        ?: positionOf(line = input.line, character = input.character)?.let { position ->
            run.execute(
                timeoutSeconds = timeoutSeconds,
                files = listOf(input.file.orEmpty()),
                work = { lsp -> work(lsp, lsp.sync(filePath = input.file.orEmpty()), position) },
            )
        }
        ?: KotlinToolResult.Error(text = POSITION_REQUIRED)

/** Runs [work] on the file and range of [input], once both are valid; the reason otherwise. */
private fun inRange(
    run: KotlinLspRun,
    input: RangeInput,
    timeoutSeconds: Int,
    work: (lsp: LspContext, uri: String, range: Range) -> KotlinToolResult,
): KotlinToolResult =
    blankError(value = input.file, name = "file")
        ?: rangeOf(input = input)?.let { range ->
            run.execute(
                timeoutSeconds = timeoutSeconds,
                files = listOf(input.file.orEmpty()),
                work = { lsp -> work(lsp, lsp.sync(filePath = input.file.orEmpty()), range) },
            )
        }
        ?: KotlinToolResult.Error(text = RANGE_REQUIRED)

private fun completionTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<PositionInput> =
    KotlinLspTool<PositionInput>(
        configName = configName,
        toolName = "kotlin_lsp_completion",
        description =
            describe(
                "Get completion suggestions at a given position in a Kotlin file.",
                "Returns up to $MAX_COMPLETIONS completion items with their label, kind, and detail.",
            ),
        inputSchema = positionSchema(),
        paramType = PositionInput::class.java,
        answer = { input, timeoutSeconds ->
            atPosition(run = run, input = input, timeoutSeconds = timeoutSeconds, work = { lsp, uri, position ->
                val result = lsp.textDocument.completion(CompletionParams(TextDocumentIdentifier(uri), position)).awaitResult()
                val items =
                    when {
                        result == null -> emptyList()
                        result.isLeft -> result.left
                        else -> result.right.items
                    }
                KotlinToolResult.Success(
                    text =
                        items
                            .take(n = MAX_COMPLETIONS)
                            .joinToString(
                                separator = "\n",
                            ) { item -> "[${item.kind}] ${item.label}${item.detail?.let { " — $it" }.orEmpty()}" }
                            .ifEmpty { "No completions" },
                )
            })
        },
    )

private fun signatureHelpTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<PositionInput> =
    KotlinLspTool<PositionInput>(
        configName = configName,
        toolName = "kotlin_lsp_signature_help",
        description =
            describe(
                "Get the signature(s) of the function being called at a given position.",
                "Useful to understand expected parameters when writing a function call.",
            ),
        inputSchema = positionSchema(),
        paramType = PositionInput::class.java,
        answer = { input, timeoutSeconds ->
            atPosition(run = run, input = input, timeoutSeconds = timeoutSeconds, work = { lsp, uri, position ->
                val help = lsp.textDocument.signatureHelp(SignatureHelpParams(TextDocumentIdentifier(uri), position)).awaitResult()
                KotlinToolResult.Success(
                    text =
                        help
                            ?.signatures
                            .orEmpty()
                            .joinToString(separator = "\n\n") { signature ->
                                signature.label +
                                    signature.documentation?.let { doc -> "\n" + if (doc.isLeft) doc.left else doc.right.value }.orEmpty()
                            }.ifEmpty { "No signature information" },
                )
            })
        },
    )

private fun diagnosticsTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<FileInput> =
    KotlinLspTool<FileInput>(
        configName = configName,
        toolName = "kotlin_lsp_diagnostics",
        description =
            describe(
                "Get all diagnostics (errors, warnings, hints) for a Kotlin file.",
                "Returns a list of issues with severity, 1-based line:column, and message.",
                "A file with errors is answered as a KOTLIN_FAILURE whose text is the list; KOTLIN_ERROR means the check itself could not run.",
            ),
        inputSchema = fileSchema(),
        paramType = FileInput::class.java,
        answer = { input, timeoutSeconds ->
            blankError(value = input.file, name = "file")
                ?: run.execute(
                    timeoutSeconds = timeoutSeconds,
                    files = listOf(input.file.orEmpty()),
                    work = { lsp -> diagnosticsOf(lsp = lsp, file = input.file.orEmpty()) },
                )
        },
    )

private fun diagnosticsOf(
    lsp: LspContext,
    file: String,
): KotlinToolResult {
    val uri = lsp.sync(filePath = file)
    val report =
        lsp.textDocument
            .diagnostic(
                DocumentDiagnosticParams(TextDocumentIdentifier(uri)),
            ).awaitResult()
            ?.relatedFullDocumentDiagnosticReport
    val items = report?.items.orEmpty()
    val text = items.joinToString(separator = "\n") { formatDiagnostic(diagnostic = it) }
    return when {
        report == null -> KotlinToolResult.Success(text = "No diagnostics report from the server")
        items.isEmpty() -> KotlinToolResult.Success(text = "No diagnostics — file is clean")
        hasError(diagnostics = items) -> KotlinToolResult.Failure(text = text)
        else -> KotlinToolResult.Success(text = text)
    }
}

private fun codeActionsTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<RangeInput> =
    KotlinLspTool<RangeInput>(
        configName = configName,
        toolName = "kotlin_lsp_code_actions",
        description =
            describe(
                "Get available code actions (quick fixes, refactorings) for a range in a Kotlin file.",
                "Returns a list of action titles.",
            ),
        inputSchema = rangeSchema(),
        paramType = RangeInput::class.java,
        answer = { input, timeoutSeconds ->
            inRange(run = run, input = input, timeoutSeconds = timeoutSeconds, work = { lsp, uri, range ->
                val actions =
                    lsp.textDocument
                        .codeAction(CodeActionParams(TextDocumentIdentifier(uri), range, CodeActionContext(emptyList())))
                        .awaitResult()
                        .orEmpty()
                KotlinToolResult.Success(
                    text =
                        actions
                            .joinToString(separator = "\n") { entry ->
                                if (entry.isLeft) {
                                    "Command: ${entry.left.title}"
                                } else {
                                    "Action: ${entry.right.title}${entry.right.kind?.let { " [$it]" }.orEmpty()}"
                                }
                            }.ifEmpty { "No code actions available" },
                )
            })
        },
    )

private fun formattingTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<FormattingInput> =
    KotlinLspTool<FormattingInput>(
        configName = configName,
        toolName = "kotlin_lsp_formatting",
        description =
            describe(
                "Get the formatting edits for a whole Kotlin file.",
                "Returns a list of text edits as \"startLine:startChar-endLine:endChar → newText\" (0-based).",
                "Does NOT apply the changes: apply them with the file tools after reviewing.",
            ),
        inputSchema =
            KotlinSchemas.of(
                required = listOf("file"),
                properties =
                    mapOf(
                        "file" to ToolSchema.string(description = KotlinToolTexts.SOURCE_FILE_PARAM),
                        "tabSize" to ToolSchema.integer(description = "Optional. Tab size for formatting. Default $DEFAULT_TAB_SIZE."),
                        "insertSpaces" to KotlinSchemas.boolean(description = "Optional. Use spaces instead of tabs. Default true."),
                    ),
            ),
        paramType = FormattingInput::class.java,
        answer = { input, timeoutSeconds ->
            blankError(value = input.file, name = "file")
                ?: run.execute(
                    timeoutSeconds = timeoutSeconds,
                    files = listOf(input.file.orEmpty()),
                    work = { lsp ->
                        val uri = lsp.sync(filePath = input.file.orEmpty())
                        val options = FormattingOptions(input.tabSize ?: DEFAULT_TAB_SIZE, input.insertSpaces ?: true)
                        val edits =
                            lsp.textDocument
                                .formatting(
                                    DocumentFormattingParams(TextDocumentIdentifier(uri), options),
                                ).awaitResult()
                                .orEmpty()
                        KotlinToolResult.Success(
                            text =
                                edits
                                    .joinToString(
                                        separator = "\n",
                                    ) { formatTextEdit(uri = uri, edit = it) }
                                    .ifEmpty { "File is already correctly formatted" },
                        )
                    },
                )
        },
    )

private fun inlayHintsTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<RangeInput> =
    KotlinLspTool<RangeInput>(
        configName = configName,
        toolName = "kotlin_lsp_inlay_hints",
        description =
            describe(
                "Get inlay hints for a range in a Kotlin file: inferred types, parameter names, return types.",
                "Useful to understand implicit types in code with heavy type inference.",
                "Returns a list of hints with their position and label.",
            ),
        inputSchema = rangeSchema(),
        paramType = RangeInput::class.java,
        answer = { input, timeoutSeconds ->
            inRange(run = run, input = input, timeoutSeconds = timeoutSeconds, work = { lsp, uri, range ->
                val hints =
                    lsp.textDocument
                        .inlayHint(InlayHintParams(TextDocumentIdentifier(uri), range))
                        .awaitResult()
                        .orEmpty()
                KotlinToolResult.Success(
                    text =
                        hints
                            .joinToString(separator = "\n") { hint ->
                                val label =
                                    if (hint.label.isLeft) {
                                        hint.label.left
                                    } else {
                                        hint.label.right.joinToString(
                                            separator = "",
                                        ) { it.value }
                                    }
                                "${hint.position.line}:${hint.position.character} $label"
                            }.ifEmpty { "No inlay hints in range" },
                )
            })
        },
    )
