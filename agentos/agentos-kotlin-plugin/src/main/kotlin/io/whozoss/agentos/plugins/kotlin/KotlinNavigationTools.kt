package io.whozoss.agentos.plugins.kotlin

import io.whozoss.agentos.plugins.kotlin.KotlinToolTexts.PREFER_OVER_TEXT_SEARCH
import io.whozoss.agentos.sdk.tool.StandardTool
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams
import org.eclipse.lsp4j.CallHierarchyItem
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams
import org.eclipse.lsp4j.CallHierarchyPrepareParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams

/** Input of the tools addressing a symbol by name, with an optional file and 1-based line. */
internal data class SymbolInput(
    val symbol: String? = null,
    val file: String? = null,
    val line: Int? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

/** Input of `kotlin_lsp_references`. */
internal data class ReferencesInput(
    val symbol: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val includeDeclaration: Boolean? = null,
    override val timeoutSeconds: Int? = null,
) : KotlinToolInput

private const val DEFINITION_SNIPPET_LINES = 5

/** Why a symbol input cannot be resolved, checked before the server is reached; null when it can be tried. */
internal fun symbolInputError(
    symbol: String?,
    file: String?,
    line: Int?,
): KotlinToolResult.Error? =
    when {
        symbol.isNullOrBlank() -> KotlinToolResult.Error(text = "`symbol` is required")
        line != null && file == null -> KotlinToolResult.Error(text = "`line` requires `file`")
        else -> null
    }

/**
 * The tools that answer a question about a Kotlin symbol addressed by name: hover, definition, type definition,
 * implementation, call hierarchy, type hierarchy, references. They answer with project relative paths, 1-based lines,
 * the enclosing symbol and the code itself (see [SymbolLocator] and [LocationRenderer]).
 */
internal fun navigationTools(
    configName: String?,
    run: KotlinLspRun,
): List<StandardTool<*>> =
    listOf(
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_hover",
            description =
                describe(
                    PREFER_OVER_TEXT_SEARCH,
                    "Get the type and documentation of a Kotlin symbol, addressed by name.",
                    "Examples: symbol='AgentLoader'; symbol='load', file='engine/.../AgentLoader.kt'; symbol='agents', file=..., line=42.",
                ),
            query = { lsp, target, _ -> hoverOf(lsp = lsp, target = target) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_definition",
            description =
                describe(
                    PREFER_OVER_TEXT_SEARCH,
                    "Find where a Kotlin symbol is defined, with its declaration code.",
                    "Give symbol, plus file and line to follow a usage seen in the code.",
                ),
            query = { lsp, target, renderer -> definitionOf(lsp = lsp, target = target, renderer = renderer) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_type_definition",
            description =
                describe(
                    PREFER_OVER_TEXT_SEARCH,
                    "Find where the type of a Kotlin symbol is defined (e.g. the class of a variable).",
                    "Give symbol, plus file and line to target a variable seen in the code.",
                ),
            query = { lsp, target, renderer -> typeDefinitionOf(lsp = lsp, target = target, renderer = renderer) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_implementation",
            description =
                describe(
                    PREFER_OVER_TEXT_SEARCH,
                    "Find all implementations of a Kotlin interface, abstract class or abstract member (implementers, subclasses).",
                ),
            query = { lsp, target, renderer -> implementationsOf(lsp = lsp, target = target, renderer = renderer) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_call_hierarchy_incoming",
            description =
                describe(PREFER_OVER_TEXT_SEARCH, "Find the functions that call a Kotlin function or method (who calls this symbol)."),
            query = { lsp, target, renderer -> callersOf(lsp = lsp, target = target, renderer = renderer) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_call_hierarchy_outgoing",
            description =
                describe(PREFER_OVER_TEXT_SEARCH, "Find the functions called by a Kotlin function or method (what this symbol calls)."),
            query = { lsp, target, renderer -> calleesOf(lsp = lsp, target = target, renderer = renderer) },
        ),
        symbolTool(
            configName = configName,
            run = run,
            toolName = "kotlin_lsp_type_hierarchy",
            description =
                describe(
                    PREFER_OVER_TEXT_SEARCH,
                    "Get the type hierarchy of a Kotlin class or interface: its supertypes (parent classes, implemented interfaces) " +
                        "and its known subtypes.",
                ),
            query = { lsp, target, _ -> typeHierarchyOf(lsp = lsp, target = target) },
        ),
        referencesTool(configName = configName, run = run),
    )

private fun symbolTool(
    configName: String?,
    run: KotlinLspRun,
    toolName: String,
    description: String,
    query: (lsp: LspContext, target: Resolution.Resolved, renderer: LocationRenderer) -> KotlinToolResult,
): StandardTool<SymbolInput> =
    KotlinLspTool<SymbolInput>(
        configName = configName,
        toolName = toolName,
        description = description,
        inputSchema = KotlinSchemas.ofSymbol(),
        paramType = SymbolInput::class.java,
        answer = { input, timeoutSeconds ->
            symbolInputError(symbol = input.symbol, file = input.file, line = input.line)
                ?: run.navigate(
                    timeoutSeconds = timeoutSeconds,
                    symbol = input.symbol.orEmpty(),
                    file = input.file,
                    line = input.line,
                    action = query,
                )
        },
    )

private fun referencesTool(
    configName: String?,
    run: KotlinLspRun,
): StandardTool<ReferencesInput> =
    KotlinLspTool<ReferencesInput>(
        configName = configName,
        toolName = "kotlin_lsp_references",
        description =
            describe(
                PREFER_OVER_TEXT_SEARCH,
                "Find every usage of a Kotlin symbol across the project, with the code of each usage and its enclosing symbol.",
                "Exact: no homonyms, no comments or strings.",
                "No result, or text outside symbols (strings, comments, config, docs): fall back to a text search.",
            ),
        inputSchema =
            KotlinSchemas.ofSymbol(
                extraProperties =
                    mapOf(
                        "includeDeclaration" to
                            KotlinSchemas.boolean(description = "Optional. Include the declaration itself in results. Default false."),
                    ),
            ),
        paramType = ReferencesInput::class.java,
        answer = { input, timeoutSeconds ->
            symbolInputError(symbol = input.symbol, file = input.file, line = input.line)
                ?: run.navigate(
                    timeoutSeconds = timeoutSeconds,
                    symbol = input.symbol.orEmpty(),
                    file = input.file,
                    line = input.line,
                    action = { lsp, target, renderer ->
                        referencesOf(lsp = lsp, target = target, renderer = renderer, includeDeclaration = input.includeDeclaration == true)
                    },
                )
        },
    )

private fun hoverOf(
    lsp: LspContext,
    target: Resolution.Resolved,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val contents =
        lsp.textDocument
            .hover(HoverParams(TextDocumentIdentifier(uri), target.position))
            .awaitResult()
            ?.contents
    val text =
        when {
            contents == null -> ""
            contents.isRight -> contents.right.value
            else -> contents.left.joinToString(separator = "\n") { markedString(entry = it) }
        }
    return KotlinToolResult.Success(
        text =
            if (text.isBlank()) {
                "No hover information for ${target.label}"
            } else {
                "${target.label} — ${lsp.paths.place(uri = uri, position = target.position)}\n$text"
            },
    )
}

private fun definitionOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val locations =
        lsp.textDocument
            .definition(DefinitionParams(TextDocumentIdentifier(uri), target.position))
            .awaitResult()
            .toLocations()
    return KotlinToolResult.Success(
        text =
            renderer.render(
                subject = "Definition of ${target.label}",
                locations = locations,
                maxSnippetLines = DEFINITION_SNIPPET_LINES,
            ),
    )
}

private fun typeDefinitionOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val locations =
        lsp.textDocument
            .typeDefinition(TypeDefinitionParams(TextDocumentIdentifier(uri), target.position))
            .awaitResult()
            .toLocations()
    return KotlinToolResult.Success(
        text = renderer.render(subject = "Type of ${target.label}", locations = locations, maxSnippetLines = DEFINITION_SNIPPET_LINES),
    )
}

private fun implementationsOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val locations =
        lsp.textDocument
            .implementation(ImplementationParams(TextDocumentIdentifier(uri), target.position))
            .awaitResult()
            .toLocations()
    return KotlinToolResult.Success(text = renderer.render(subject = "Implementations of ${target.label}", locations = locations))
}

private fun referencesOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
    includeDeclaration: Boolean,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val params = ReferenceParams(TextDocumentIdentifier(uri), target.position, ReferenceContext(includeDeclaration))
    val locations =
        lsp.textDocument
            .references(params)
            .awaitResult()
            .orEmpty()
    return KotlinToolResult.Success(text = renderer.render(subject = "References to ${target.label}", locations = locations))
}

private fun callHierarchyItemOf(
    lsp: LspContext,
    target: Resolution.Resolved,
): CallHierarchyItem? {
    val uri = lsp.sync(filePath = target.filePath)
    return lsp.textDocument
        .prepareCallHierarchy(CallHierarchyPrepareParams(TextDocumentIdentifier(uri), target.position))
        .awaitResult()
        ?.firstOrNull()
}

private fun notCallable(target: Resolution.Resolved): KotlinToolResult =
    KotlinToolResult.Error(text = "${target.label} is not a function or method: no call hierarchy")

private fun callersOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
): KotlinToolResult =
    callHierarchyItemOf(lsp = lsp, target = target)
        ?.let { item ->
            val callers =
                lsp.textDocument
                    .callHierarchyIncomingCalls(CallHierarchyIncomingCallsParams(item))
                    .awaitResult()
                    .orEmpty()
            KotlinToolResult.Success(
                text =
                    renderer.render(
                        subject = "Callers of ${target.label}",
                        locations = callers.map { Location(it.from.uri, it.from.selectionRange) },
                    ),
            )
        }
        ?: notCallable(target = target)

private fun calleesOf(
    lsp: LspContext,
    target: Resolution.Resolved,
    renderer: LocationRenderer,
): KotlinToolResult =
    callHierarchyItemOf(lsp = lsp, target = target)
        ?.let { item ->
            val callees =
                lsp.textDocument
                    .callHierarchyOutgoingCalls(CallHierarchyOutgoingCallsParams(item))
                    .awaitResult()
                    .orEmpty()
            KotlinToolResult.Success(
                text =
                    renderer.render(
                        subject = "Callees of ${target.label}",
                        locations = callees.map { Location(it.to.uri, it.to.selectionRange) },
                    ),
            )
        }
        ?: notCallable(target = target)

private fun typeHierarchyOf(
    lsp: LspContext,
    target: Resolution.Resolved,
): KotlinToolResult {
    val uri = lsp.sync(filePath = target.filePath)
    val item =
        lsp.textDocument
            .prepareTypeHierarchy(TypeHierarchyPrepareParams(TextDocumentIdentifier(uri), target.position))
            .awaitResult()
            ?.firstOrNull()
    return item
        ?.let { prepared ->
            val supertypes =
                lsp.textDocument
                    .typeHierarchySupertypes(TypeHierarchySupertypesParams(prepared))
                    .awaitResult()
                    .orEmpty()
            val subtypes =
                lsp.textDocument
                    .typeHierarchySubtypes(TypeHierarchySubtypesParams(prepared))
                    .awaitResult()
                    .orEmpty()
            KotlinToolResult.Success(
                text =
                    "SUPERTYPES of ${target.label}:\n${formatTypes(types = supertypes, paths = lsp.paths)}" +
                        "\n\nSUBTYPES of ${target.label}:\n${formatTypes(types = subtypes, paths = lsp.paths)}",
            )
        }
        ?: KotlinToolResult.Error(text = "${target.label} is not a class or interface: no type hierarchy")
}
