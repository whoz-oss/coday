package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import mu.KLogging
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Read an Excel (.xlsx) spreadsheet as CSV text so the model can work on exact cell values.
 *
 * One block per selected sheet: a `## Sheet i/N "name"` header describing the row window,
 * then RFC 4180 CSV (fields quoted when they contain a comma, quote or newline; `\n` record
 * separator instead of CRLF, for cheaper tokens). Values are rendered as displayed in Excel
 * via [DataFormatter] (number and date formats applied); formula cells yield their cached
 * last-saved result (no evaluation, on purpose: evaluating a large workbook inside a tool
 * call is a latency trap) and error cells the Excel error literal (`#DIV/0!`, `#REF!`...).
 * Merged regions keep their value in the top-left cell only, covered cells come out empty.
 * Hidden sheets and rows are included (hidden sheets are marked): this is data extraction,
 * not visual rendering, and skipping rows would break the physical row numbering that
 * `startRow` paging relies on. Row numbers are the sheet's physical 1-based numbers, so they
 * always match what Excel displays: interior empty rows are kept as empty lines, trailing
 * content-free rows and cells are trimmed (a sheet merely formatted down to row 1048576
 * does not blow the output).
 *
 * Budgets per call: [MAX_ROWS_PER_CALL] rows or [MAX_OUTPUT_CHARS] characters (whichever
 * trips first, truncating at a row boundary), [MAX_COLUMNS] columns and [MAX_CELL_CHARS]
 * characters per cell. A `[truncated]` notice tells the model how to continue. The whole
 * workbook is materialized in memory (XSSF DOM): raising `readMaxSizeMb` raises the parse
 * memory cost accordingly.
 */
class ReadSpreadsheetTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<ReadSpreadsheetTool.Input> {
    companion object : KLogging() {
        /** Parsing a whole XSSF DOM is heavy like PDF/PPTX rendering: distinct, larger timeout. */
        private const val IO_TIMEOUT = 60L
        private const val DEFAULT_READ_MAX_SIZE = 10L * 1024 * 1024 // 10 MB

        /** Maximum rows emitted in a single call, across all selected sheets. */
        const val MAX_ROWS_PER_CALL = 1000

        /** Output character budget per call: the real guard for very wide sheets. */
        const val MAX_OUTPUT_CHARS = 100_000

        /** Columns beyond this are not emitted (xlsx allows 16384 columns). */
        const val MAX_COLUMNS = 256

        /** A single cell may hold up to 32767 characters; longer values are cut. */
        const val MAX_CELL_CHARS = 5000
    }

    override val name: String =
        if (configName != null) "${configName}__readSpreadsheet" else "FILES__readSpreadsheet"

    override val description: String =
        """
        Read an Excel spreadsheet (.xlsx) as text. Cell values are returned as CSV
        (RFC 4180 quoting), one block per sheet, each preceded by a header line giving
        the sheet name and the row window shown. Values appear as displayed in Excel
        (number and date formats applied); formulas yield their last saved result and
        error cells yield Excel error literals (#DIV/0!, #REF!...).
        At most $MAX_ROWS_PER_CALL rows (or $MAX_OUTPUT_CHARS characters) per call: for larger
        sheets pass "sheets" (1-based sheet numbers) and "startRow" (1-based row) to page
        through, as instructed by the [truncated] notice.
        .xlsx only. For .csv and other text files use readFile. Legacy .xls, .xlsm and
        .ods are not supported: convert to .xlsx first.
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "filePath": {
                    "type": "string",
                    "description": "Relative file path (e.g. \"data/report.xlsx\")"
                },
                "sheets": {
                    "type": "array",
                    "items": { "type": "integer", "minimum": 1 },
                    "description": "1-based sheet numbers, in workbook order. Omit to read all sheets."
                },
                "startRow": {
                    "type": "integer",
                    "minimum": 1,
                    "description": "1-based row to start from, to page through a large sheet; requires targeting exactly one sheet. Default 1."
                }
            },
            "required": ["filePath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val filePath: String = "",
        val sheets: List<Int>? = null,
        val startRow: Int? = null,
    )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val content = runIOWithTimeout(IO_TIMEOUT) { read(params) }
            ToolExecutionResult.success(content)
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult.error(
                "Operation timed out after $IO_TIMEOUT seconds",
                errorType = "TIMEOUT",
                errorMessage = e.message,
            )
        } catch (e: UnsupportedFormatException) {
            ToolExecutionResult.error(
                e.message ?: "Unsupported file format",
                errorType = "UNSUPPORTED_FORMAT",
                errorMessage = e.message,
            )
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.error(
                e.message ?: "Invalid input",
                errorType = "INVALID_INPUT",
                errorMessage = e.message,
            )
        } catch (e: Exception) {
            logger.warn(e) { "readSpreadsheet failed to read '${params.filePath}'" }
            ToolExecutionResult.error(
                "Error reading spreadsheet: ${e.message}",
                errorType = "READ_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun read(params: Input): String {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(params.filePath, createIntent = false)

        // Size guard before anything is parsed. Also the first line of defense against
        // decompression bombs; POI's built-in ZipSecureFile checks (inflate ratio cap,
        // max entry size) cover the xlsx zip itself.
        val size = resolved.fileSize()
        if (size > readMaxSizeBytes) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${readMaxSizeBytes / 1024 / 1024} MB: ${params.filePath}",
            )
        }

        when (val extension = resolved.name.substringAfterLast('.', "").lowercase()) {
            "xlsx" -> Unit
            "csv" -> throw UnsupportedFormatException("'.csv' is plain text: use readFile instead.")
            else -> throw UnsupportedFormatException(
                "Unsupported file type '.$extension'. Supported: .xlsx only. " +
                    "Convert .xls/.xlsm/.ods to .xlsx; for .csv use readFile.",
            )
        }

        try {
            // XSSFWorkbook is constructed directly on purpose: WorkbookFactory resolves
            // providers through the thread context classloader, which under PF4J is the
            // application classloader and does not see the bundled POI classes.
            Files.newInputStream(resolved).use { input ->
                XSSFWorkbook(input).use { workbook ->
                    return renderWorkbook(workbook, params, resolved.name)
                }
            }
        } catch (e: EncryptedDocumentException) {
            throw IllegalArgumentException("Excel file is password-protected: ${resolved.name}")
        } catch (e: NotOfficeXmlFileException) {
            // Also covers encrypted xlsx (stored as an OLE2 container) and legacy .xls
            // files renamed to .xlsx.
            throw IllegalArgumentException(
                "Not a valid .xlsx file (corrupt, password-protected, or legacy .xls; convert to .xlsx): ${resolved.name}",
            )
        }
    }

    private fun renderWorkbook(workbook: XSSFWorkbook, params: Input, fileName: String): String {
        val sheetCount = workbook.numberOfSheets
        if (sheetCount == 0) {
            throw IllegalArgumentException("Workbook has no sheet: $fileName")
        }

        val selection = selectSheets(params.sheets, sheetCount, fileName)
        if (params.startRow != null && selection.size != 1) {
            throw IllegalArgumentException(
                "\"startRow\" requires targeting exactly one sheet (pass a single entry in \"sheets\").",
            )
        }

        val formatter = DataFormatter(Locale.US)
        val out = StringBuilder()
        var rowsEmitted = 0

        for ((position, sheetNumber) in selection.withIndex()) {
            if (rowsEmitted >= MAX_ROWS_PER_CALL || out.length >= MAX_OUTPUT_CHARS) {
                appendTruncationNotice(out, workbook, continueSheets = selection.drop(position))
                break
            }

            val sheet = workbook.getSheetAt(sheetNumber - 1)
            val range = scanUsedRange(sheet)
            val totalRows = range.lastContentRow + 1
            val startRow = params.startRow ?: 1
            if (startRow > 1 && startRow > totalRows) {
                throw IllegalArgumentException(
                    "startRow $startRow out of range: sheet $sheetNumber \"${sheet.sheetName}\" has $totalRows row(s).",
                )
            }

            if (position > 0) out.append('\n')
            if (totalRows == 0) {
                out.append(sheetTitle(sheetNumber, sheetCount, workbook)).append(": empty\n")
                continue
            }

            val body = StringBuilder()
            var windowEnd = startRow - 1
            var nextStartRow: Int? = null
            for (rowIndex in (startRow - 1)..range.lastContentRow) {
                if (rowsEmitted >= MAX_ROWS_PER_CALL || out.length + body.length >= MAX_OUTPUT_CHARS) {
                    nextStartRow = rowIndex + 1
                    break
                }
                body.append(renderRow(sheet.getRow(rowIndex), range.columnCount, formatter)).append('\n')
                rowsEmitted++
                windowEnd = rowIndex + 1
            }

            out.append(sheetTitle(sheetNumber, sheetCount, workbook))
                .append(": rows $startRow-$windowEnd of $totalRows, ${describeColumns(range)}\n")
                .append(body)

            if (nextStartRow != null) {
                appendTruncationNotice(
                    out,
                    workbook,
                    continueSheets = listOf(sheetNumber),
                    nextStartRow = nextStartRow,
                    notShown = selection.drop(position + 1),
                )
                break
            }
        }

        return out.toString()
    }

    private fun selectSheets(sheets: List<Int>?, sheetCount: Int, fileName: String): List<Int> {
        if (sheets == null) return (1..sheetCount).toList()

        val selection = sheets.distinct().sorted()
        if (selection.isEmpty()) {
            throw IllegalArgumentException("\"sheets\" must not be empty: $fileName")
        }
        val outOfRange = selection.filter { it !in 1..sheetCount }
        if (outOfRange.isNotEmpty()) {
            throw IllegalArgumentException(
                "Sheet(s) $outOfRange out of range: $fileName has $sheetCount sheet(s).",
            )
        }
        return selection
    }

    /**
     * Used range of a sheet, scanning only physically-existing rows (bounded by what POI
     * parsed): trailing style-only rows are excluded from the row total, and the column
     * count covers the whole sheet so it stays stable across paged calls.
     */
    private fun scanUsedRange(sheet: Sheet): UsedRange {
        var lastContentRow = -1
        var columnCount = 0
        var columnsTruncated = false
        sheet.rowIterator().forEach { row ->
            var lastContentCell = -1
            row.cellIterator().forEach { cell ->
                if (cell.cellType != CellType.BLANK) lastContentCell = maxOf(lastContentCell, cell.columnIndex)
            }
            if (lastContentCell >= 0) {
                lastContentRow = maxOf(lastContentRow, row.rowNum)
                if (lastContentCell + 1 > MAX_COLUMNS) columnsTruncated = true
                columnCount = maxOf(columnCount, minOf(lastContentCell + 1, MAX_COLUMNS))
            }
        }
        return UsedRange(lastContentRow, columnCount, columnsTruncated)
    }

    private fun sheetTitle(sheetNumber: Int, sheetCount: Int, workbook: XSSFWorkbook): String {
        val index = sheetNumber - 1
        val hidden = workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)
        return "## Sheet $sheetNumber/$sheetCount \"${workbook.getSheetName(index)}\"" +
            if (hidden) " (hidden)" else ""
    }

    private fun describeColumns(range: UsedRange): String =
        "columns A-${columnLetter(range.columnCount - 1)}" +
            if (range.columnsTruncated) " (truncated at $MAX_COLUMNS)" else ""

    private fun renderRow(row: Row?, columnCount: Int, formatter: DataFormatter): String {
        if (row == null) return ""
        return (0 until columnCount)
            .map { column ->
                val cell = row.getCell(column)
                if (cell == null) "" else csvField(truncateCell(renderCell(cell, formatter)))
            }
            .dropLastWhile { it.isEmpty() }
            .joinToString(",")
    }

    /**
     * Formula cells read the cached last-saved result: without an evaluator,
     * [DataFormatter.formatCellValue] would return the formula text itself.
     */
    private fun renderCell(cell: Cell, formatter: DataFormatter): String =
        when (cell.cellType) {
            CellType.FORMULA -> when (cell.cachedFormulaResultType) {
                // formatRawCellContents applies the cell's number format and detects
                // date formats internally.
                CellType.NUMERIC -> formatter.formatRawCellContents(
                    cell.numericCellValue,
                    cell.cellStyle.dataFormat.toInt(),
                    cell.cellStyle.dataFormatString,
                )
                CellType.STRING -> cell.stringCellValue
                CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
                CellType.ERROR -> formulaErrorText(cell.errorCellValue)
                else -> ""
            }
            CellType.ERROR -> formulaErrorText(cell.errorCellValue)
            else -> formatter.formatCellValue(cell)
        }

    private fun formulaErrorText(code: Byte): String =
        runCatching { FormulaError.forInt(code.toInt()).string }.getOrDefault("#ERROR")

    private fun truncateCell(value: String): String =
        if (value.length <= MAX_CELL_CHARS) value else value.take(MAX_CELL_CHARS) + "…[cell truncated]"

    /** RFC 4180: quote fields containing a comma, quote or line break, doubling inner quotes. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    private fun appendTruncationNotice(
        out: StringBuilder,
        workbook: XSSFWorkbook,
        continueSheets: List<Int>,
        nextStartRow: Int? = null,
        notShown: List<Int> = emptyList(),
    ) {
        out.append("\n[truncated] Per-call limit reached (max $MAX_ROWS_PER_CALL rows / $MAX_OUTPUT_CHARS chars). ")
        out.append("Continue with sheets=[${continueSheets.joinToString(", ")}]")
        out.append(if (nextStartRow != null) ", startRow=$nextStartRow." else ".")
        if (notShown.isNotEmpty()) {
            val labels = notShown.joinToString(", ") { "$it \"${workbook.getSheetName(it - 1)}\"" }
            out.append(" Sheet(s) not shown: $labels.")
        }
        out.append('\n')
    }

    /** 0-based column index to Excel letters (0 = A, 26 = AA, 255 = IV). */
    private fun columnLetter(index: Int): String {
        var remaining = index
        val letters = StringBuilder()
        while (remaining >= 0) {
            letters.insert(0, 'A' + remaining % 26)
            remaining = remaining / 26 - 1
        }
        return letters.toString()
    }

    private data class UsedRange(
        val lastContentRow: Int,
        val columnCount: Int,
        val columnsTruncated: Boolean,
    )

    private class UnsupportedFormatException(message: String) : IllegalArgumentException(message)
}
