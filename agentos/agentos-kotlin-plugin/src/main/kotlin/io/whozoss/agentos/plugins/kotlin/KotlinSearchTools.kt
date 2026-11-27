package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.plugins.kotlin.KotlinToolTexts.PREFER_OVER_TEXT_SEARCH
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolSchema
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/** Input of `kotlin_lsp_workspace_symbols`. */
internal data class WorkspaceSymbolsInput(
    val query: String? = null,
    val includeLibraries: Boolean? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** Input of the tools working on a whole file. */
internal data class FileInput(
    val file: String? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** The tools that list symbols: of the whole project by name, or declared in one file. */
internal fun searchTools(
    configName: String?,
    run: KotlinLspRun,
): List<StandardTool<*>> =
    listOf(workspaceSymbolsTool(configName = configName, run = run), documentSymbolsTool(configName = configName, run = run))

private fun workspaceSymbolsTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<WorkspaceSymbolsInput> =
    KotlinLspTool<WorkspaceSymbolsInput>(
        configName = configName,
        toolName = "kotlin_lsp_workspace_symbols",
        description =
            describe(
                PREFER_OVER_TEXT_SEARCH,
                "Search the project for Kotlin symbols (classes, functions, interfaces, objects) by name.",
                "Best matches first (exact, prefix, contains); library symbols are excluded unless requested.",
                "Use this to locate a symbol without knowing which file it is in.",
            ),
        inputSchema =
            KotlinSchemas.of(
                required = listOf("query"),
                properties =
                    mapOf(
                        "query" to ToolSchema.string(description = "Symbol name or partial name to search for (case-insensitive)"),
                        "includeLibraries" to
                            KotlinSchemas.boolean(description = "Optional. Also list symbols from libraries. Default false."),
                    ),
            ),
        paramType = WorkspaceSymbolsInput::class.java,
        answer = { input, timeoutSeconds ->
            blankError(value = input.query, name = "query")
                ?: run.execute(
                    timeoutSeconds = timeoutSeconds,
                    work = { lsp ->
                        symbolsMatching(lsp = lsp, query = input.query.orEmpty().trim(), includeLibraries = input.includeLibraries == true)
                    },
                )
        },
    )

private fun symbolsMatching(
    lsp: LspContext,
    query: String,
    includeLibraries: Boolean,
): KotlinToolResult {
    val candidates = lsp.symbolSource().workspaceSymbols(query = query).filter { includeLibraries || lsp.paths.isProjectUri(uri = it.uri) }
    return KotlinToolResult.Success(
        text =
            SymbolMatching.render(
                query = query,
                ranked = SymbolMatching.rank(query = query, candidates = candidates),
                paths = lsp.paths,
            ),
    )
}

private fun documentSymbolsTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<FileInput> =
    KotlinLspTool<FileInput>(
        configName = configName,
        toolName = "kotlin_lsp_document_symbols",
        description =
            describe(
                PREFER_OVER_TEXT_SEARCH,
                "List all symbols (classes, functions, properties, objects) defined in a Kotlin file.",
                "Returns a structured list with name, kind, and 1-based line for each symbol.",
            ),
        inputSchema =
            KotlinSchemas.of(
                required = listOf("file"),
                properties = mapOf("file" to ToolSchema.string(description = KotlinToolTexts.SOURCE_FILE_PARAM)),
            ),
        paramType = FileInput::class.java,
        answer = { input, timeoutSeconds ->
            blankError(value = input.file, name = "file")
                ?: run.execute(
                    timeoutSeconds = timeoutSeconds,
                    files = listOf(input.file.orEmpty()),
                    work = { lsp -> documentSymbolsOf(lsp = lsp, file = input.file.orEmpty()) },
                )
        },
    )

private fun documentSymbolsOf(
    lsp: LspContext,
    file: String,
): KotlinToolResult {
    val uri = lsp.sync(filePath = file)
    val text =
        lsp.textDocument
            .documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri)))
            .awaitResult()
            .orEmpty()
            .joinToString(separator = "\n") { entry ->
                if (entry.isLeft) {
                    formatSymbolInformation(symbol = entry.left, paths = lsp.paths)
                } else {
                    formatDocumentSymbol(symbol = entry.right, indent = "")
                }
            }.ifEmpty { "No symbols found" }
    return KotlinToolResult.Success(text = text)
}
