package io.whozoss.agentos.plugins.kotlin

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MarkedString
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Locations of a definition-like answer, which a server gives as locations or as location links. */
internal fun <L : List<Location>, R : List<LocationLink>> Either<L, R>?.toLocations(): List<Location> =
    when {
        this == null -> emptyList()
        isLeft -> left
        else -> right.map { Location(it.targetUri, it.targetRange) }
    }

/** One entry of a hover, which a server gives as a string or as a marked string. */
internal fun markedString(entry: Either<String, MarkedString>): String =
    when {
        entry.isLeft -> entry.left
        entry.isRight -> entry.right.value
        else -> ""
    }

/** Supertypes or subtypes, one per line. */
internal fun formatTypes(
    types: List<TypeHierarchyItem>,
    paths: ProjectPaths,
): String =
    types
        .joinToString(separator = "\n") { "  ${it.kind} ${it.name} — ${paths.place(uri = it.uri, position = it.selectionRange.start)}" }
        .ifEmpty { "  (none)" }

/** A note listing what the server wanted to edit but could not, empty when nothing was skipped. */
internal fun skippedNote(
    applied: AppliedEdit,
    lsp: LspContext,
): String = if (applied.skipped.isEmpty()) "" else "\nSkipped (file missing, stale index):\n" + lsp.display(filePaths = applied.skipped)

/** The edits of [edit] as `path:line:col-line:col → text`, 1-based, for a rename that writes nothing. */
internal fun describeEdits(
    edit: WorkspaceEdit,
    paths: ProjectPaths,
): String {
    val simple = edit.changes.orEmpty().flatMap { (uri, edits) -> edits.map { formatRenameEdit(uri = uri, edit = it, paths = paths) } }
    val documents =
        edit.documentChanges.orEmpty().mapNotNull { it.left }.flatMap { docEdit ->
            docEdit.edits.map { editEither ->
                editEither.left?.let { formatRenameEdit(uri = docEdit.textDocument.uri, edit = it, paths = paths) }
                    ?: "[SnippetTextEdit not rendered]"
            }
        }
    return (simple + documents).joinToString(separator = "\n")
}

private fun formatRenameEdit(
    uri: String,
    edit: TextEdit,
    paths: ProjectPaths,
): String {
    val start = edit.range.start
    val end = edit.range.end
    return "${paths.place(uri = uri, position = start)}:${start.character + 1}-${end.line + 1}:${end.character + 1} → " +
        edit.newText.take(MAX_TEXT)
}

/** Whether one of [diagnostics] is an error, i.e. the code does not compile. */
internal fun hasError(diagnostics: List<Diagnostic>): Boolean = diagnostics.any { it.severity == DiagnosticSeverity.Error }

/** `[SEVERITY] line:column — message`, 1-based. The message is plain text or Markdown since lsp4j 1.0. */
internal fun formatDiagnostic(diagnostic: Diagnostic): String {
    val start = diagnostic.range.start
    val message = diagnostic.message?.let { if (it.isLeft) it.left else it.right.value }.orEmpty()
    return "[${diagnostic.severity}] ${start.line + 1}:${start.character + 1} — $message"
}

/** Raw 0-based edit, as the formatting tool reports it. */
internal fun formatTextEdit(
    uri: String,
    edit: TextEdit,
): String {
    val start = edit.range.start
    val end = edit.range.end
    return "$uri:${start.line}:${start.character}-${end.line}:${end.character} → ${edit.newText.take(MAX_TEXT)}"
}

/** A flat symbol as `Kind name at path:line`. */
internal fun formatSymbolInformation(
    symbol: SymbolInformation,
    paths: ProjectPaths,
): String = "${symbol.kind} ${symbol.name} at ${paths.place(uri = symbol.location.uri, position = symbol.location.range.start)}"

/** A nested symbol as `Kind name at line N`, its children indented below. */
internal fun formatDocumentSymbol(
    symbol: DocumentSymbol,
    indent: String,
): String =
    buildString {
        append("$indent${symbol.kind} ${symbol.name} at line ${symbol.range.start.line + 1}")
        symbol.children?.forEach { child ->
            append("\n")
            append(formatDocumentSymbol(symbol = child, indent = "$indent  "))
        }
    }

private const val MAX_TEXT = 120
