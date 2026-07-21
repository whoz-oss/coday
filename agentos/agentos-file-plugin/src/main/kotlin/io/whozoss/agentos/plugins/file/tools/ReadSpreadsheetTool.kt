package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.TimeoutCancellationException
import mu.KLogging
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.exceptions.InvalidOperationException
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaError
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.FileSystemException
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
 * via [DataFormatter] (number and date formats applied, honoring the workbook's 1900/1904
 * date system); formula cells yield their cached last-saved result (no evaluation, on
 * purpose: evaluating a large workbook inside a tool call is a latency trap), formula cells
 * saved without a cached result (openpyxl, exceljs and friends write those) render as
 * `=formula` rather than a fabricated zero, and error cells give the Excel error literal
 * (`#DIV/0!`, `#REF!`...).
 * Merged regions keep their value in the top-left cell only, covered cells come out empty.
 * Hidden sheets and rows are included (hidden sheets are marked): this is data extraction,
 * not visual rendering, and skipping rows would break the physical row numbering that
 * `startRow` paging relies on. Row numbers are the sheet's physical 1-based numbers, so they
 * always match what Excel displays: interior empty rows are kept as empty lines, trailing
 * content-free rows and cells are trimmed (a sheet merely formatted down to row 1048576
 * does not blow the output).
 *
 * Budgets per call: [spreadsheetMaxRows] rows or [spreadsheetMaxOutputChars] characters of CSV body
 * (whichever trips first; truncation happens at a row boundary, and a row longer than the
 * remaining budget is itself cut with a `[row truncated]` marker so a single row can never
 * blow the budget), [spreadsheetMaxColumns] columns and [spreadsheetMaxCellChars] characters per cell. A
 * `[truncated]` notice tells the model how to continue. The workbook is opened from the
 * file (lazy random-access zip reading; the InputStream path would buffer the whole
 * decompressed package in memory up front) but sheets are still materialized as XSSF DOM:
 * raising `readMaxSizeMb` raises the parse memory cost, and the timeout cannot interrupt
 * a POI parse mid-flight.
 */
class ReadSpreadsheetTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
    private val ioTimeoutSeconds: Long = IO_TIMEOUT,
    private val spreadsheetMaxRows: Int = MAX_ROWS_PER_CALL,
    private val spreadsheetMaxOutputChars: Int = MAX_OUTPUT_CHARS,
    private val spreadsheetMaxColumns: Int = MAX_COLUMNS,
    private val spreadsheetMaxCellChars: Int = MAX_CELL_CHARS,
) : StandardTool<ReadSpreadsheetTool.Input> {

    override val name: String =
        if (configName != null) "${configName}__readSpreadsheet" else "FILES__readSpreadsheet"

    override val description: String =
        """
        Read an Excel spreadsheet (.xlsx) as text. Cell values are returned as CSV
        (RFC 4180 quoting), one block per sheet, each preceded by a header line giving
        the sheet name and the row window shown. Values appear as displayed in Excel
        (number and date formats applied); formulas yield their last saved result (or
        "=formula" when the file was saved without cached results) and error cells
        yield Excel error literals (#DIV/0!, #REF!...).
        At most $spreadsheetMaxRows rows (or ~$spreadsheetMaxOutputChars characters) per call: for larger
        sheets pass "sheets" (1-based sheet numbers) and "startRow" (1-based row) to page
        through, as instructed by the [truncated] notice. Sheets wider than $spreadsheetMaxColumns
        columns are cut at column $spreadsheetMaxColumns (flagged in the sheet header); oversized cells
        and rows are cut with "[cell truncated]"/"[row truncated]" markers.
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
            val content = runIOWithTimeout(ioTimeoutSeconds) { read(params) }
            ToolExecutionResult.success(content)
        } catch (e: TimeoutCancellationException) {
            ToolExecutionResult.error(
                "Operation timed out after $ioTimeoutSeconds seconds",
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
        } catch (e: FileSystemException) {
            // NoSuchFileException/AccessDeniedException messages are the bare absolute
            // server path, which must not reach the model.
            logger.warn(e) { "readSpreadsheet failed to read '${params.filePath}'" }
            ToolExecutionResult.error(
                "File could not be read: ${params.filePath}",
                errorType = "READ_ERROR",
                errorMessage = e.javaClass.simpleName,
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
        require(size <= readMaxSizeBytes) {
            "File exceeds maximum size of ${readMaxSizeBytes / 1024 / 1024} MB: ${params.filePath}"
        }

        when (val extension = resolved.name.substringAfterLast('.', "").lowercase()) {
            "xlsx" -> Unit
            "csv" -> throw UnsupportedFormatException("'.csv' is plain text: use readFile instead.")
            else -> throw UnsupportedFormatException(
                "Unsupported file type '.$extension'. Supported: .xlsx only. Convert .xls/.xlsm/.ods to .xlsx.",
            )
        }

        try {
            // The workbook is opened from the file, not an InputStream: the stream path
            // buffers every decompressed zip entry in memory up front, while the file path
            // reads entries lazily with random access. OPCPackage/XSSFWorkbook are
            // constructed directly on purpose: WorkbookFactory resolves providers through
            // the thread context classloader, which under PF4J is the application
            // classloader and does not see the bundled POI classes.
            val opcPackage = OPCPackage.open(resolved.toFile(), PackageAccess.READ)
            val workbook = try {
                XSSFWorkbook(opcPackage)
            } catch (e: Exception) {
                opcPackage.revert()
                throw e
            }
            workbook.use {
                return renderWorkbook(it, params, resolved.name)
            }
        } catch (e: EncryptedDocumentException) {
            throw IllegalArgumentException("Excel file is password-protected: ${resolved.name}")
        } catch (e: NotOfficeXmlFileException) {
            // Also covers encrypted xlsx (stored as an OLE2 container) and legacy .xls
            // files renamed to .xlsx.
            throw IllegalArgumentException(
                "Not a valid .xlsx file (corrupt, password-protected, or legacy .xls; convert to .xlsx): ${resolved.name}",
            )
        } catch (e: POIXMLException) {
            // A valid OOXML package that is not a workbook (e.g. a .docx renamed .xlsx).
            throw IllegalArgumentException("Not a valid .xlsx file (the package is not an Excel workbook): ${resolved.name}")
        } catch (e: InvalidFormatException) {
            // A valid zip that is not an OOXML package (no content-types part).
            throw IllegalArgumentException("Not a valid .xlsx file (the package is not an Excel workbook): ${resolved.name}")
        } catch (e: InvalidOperationException) {
            // POI wraps open failures (e.g. permission denied) with the absolute server
            // path in the message; rethrow with the relative path only.
            logger.warn(e) { "readSpreadsheet could not open '${params.filePath}'" }
            throw IllegalArgumentException("File could not be opened: ${params.filePath}")
        }
    }

    private fun renderWorkbook(workbook: XSSFWorkbook, params: Input, fileName: String): String {
        val sheetCount = workbook.numberOfSheets
        require(sheetCount > 0) { "Workbook has no sheet: $fileName" }

        val selection = selectSheets(params.sheets, sheetCount, fileName)
        require(params.startRow == null || params.startRow >= 1) {
            "\"startRow\" must be at least 1 (got ${params.startRow})."
        }
        require(params.startRow == null || selection.size == 1) {
            "\"startRow\" requires targeting exactly one sheet (pass a single entry in \"sheets\")."
        }

        val formatter = DataFormatter(Locale.US)
        val date1904 = workbook.isDate1904
        val out = StringBuilder()
        var rowsEmitted = 0

        for ((position, sheetNumber) in selection.withIndex()) {
            if (rowsEmitted >= spreadsheetMaxRows || out.length >= spreadsheetMaxOutputChars) {
                appendTruncationNotice(out, workbook, continueSheets = selection.drop(position))
                break
            }

            val sheet = workbook.getSheetAt(sheetNumber - 1)
            val range = scanUsedRange(sheet)
            val totalRows = range.lastContentRow + 1
            val startRow = params.startRow ?: 1
            // startRow <= 1: never reject the default start on an empty sheet — the totalRows == 0 path below handles it.
            require(startRow <= 1 || startRow <= totalRows) {
                "startRow $startRow out of range: sheet $sheetNumber \"${sheet.sheetName}\" has $totalRows row(s)."
            }

            if (position > 0) out.append('\n')
            if (totalRows == 0) {
                out.append(sheetTitle(sheetNumber, sheetCount, workbook)).append(": empty\n")
                continue
            }

            val rows = renderSheetRows(sheet, range, startRow, out.length, rowsEmitted, formatter, date1904)
            rowsEmitted += rows.rowsEmitted

            // windowEnd < startRow means no row of this sheet was emitted: skip the header
            // (it would read a nonsense "rows N-(N-1)") and fall through to the notice.
            if (rows.windowEnd >= startRow) {
                out.append(sheetTitle(sheetNumber, sheetCount, workbook))
                    .append(": rows $startRow-${rows.windowEnd} of $totalRows, ${describeColumns(range)}\n")
                    .append(rows.body)
            }

            if (rows.nextStartRow != null && rows.nextStartRow <= totalRows) {
                appendTruncationNotice(
                    out,
                    workbook,
                    continueSheets = listOf(sheetNumber),
                    nextStartRow = rows.nextStartRow,
                    notShown = selection.drop(position + 1),
                )
                break
            }
            // nextStartRow beyond the sheet's rows means the cut row was the sheet's last:
            // nothing further to page here; the top-of-loop budget check hands any
            // remaining sheets to the boundary notice on the next iteration.
        }

        return out.toString()
    }

    /** Rows of one sheet emitted from a paged loop, plus where the render stopped. */
    private data class SheetRows(
        val body: String,
        val rowsEmitted: Int,
        /** Last physical row (1-based) written to [body]; `startRow - 1` when nothing was emitted. */
        val windowEnd: Int,
        /** Row (1-based) where paging must resume within this sheet, or null when the sheet fit. */
        val nextStartRow: Int?,
    )

    /**
     * Emits the CSV rows of [sheet] from [startRow] (1-based) while the shared per-call budget
     * holds. [charsAlreadyUsed] and [rowsAlreadyEmitted] are the call-wide totals at entry (the
     * sheet header is not counted, matching the surrounding accounting). A single row larger than
     * the whole char budget is emitted cut with a marker so the call still makes progress.
     */
    private fun renderSheetRows(
        sheet: Sheet,
        range: UsedRange,
        startRow: Int,
        charsAlreadyUsed: Int,
        rowsAlreadyEmitted: Int,
        formatter: DataFormatter,
        date1904: Boolean,
    ): SheetRows {
        val body = StringBuilder()
        var emitted = 0
        var windowEnd = startRow - 1
        var nextStartRow: Int? = null
        for (rowIndex in (startRow - 1)..range.lastContentRow) {
            if (rowsAlreadyEmitted + emitted >= spreadsheetMaxRows) {
                nextStartRow = rowIndex + 1
                break
            }
            val rowText = renderRow(sheet.getRow(rowIndex), range.columnCount, formatter, date1904)
            val used = charsAlreadyUsed + body.length
            if (used + rowText.length + 1 > spreadsheetMaxOutputChars) {
                if (rowsAlreadyEmitted + emitted == 0) {
                    // A single row larger than the whole budget: emit what fits with a
                    // marker so the call still makes progress; the row's tail is not
                    // reachable by paging.
                    val room = (spreadsheetMaxOutputChars - used - ROW_TRUNCATED_MARKER.length - 1).coerceAtLeast(0)
                    body.append(cutAtCharBoundary(rowText, room)).append(ROW_TRUNCATED_MARKER).append('\n')
                    emitted++
                    windowEnd = rowIndex + 1
                    nextStartRow = rowIndex + 2
                } else {
                    nextStartRow = rowIndex + 1
                }
                break
            }
            body.append(rowText).append('\n')
            emitted++
            windowEnd = rowIndex + 1
        }
        return SheetRows(body.toString(), emitted, windowEnd, nextStartRow)
    }

    private fun selectSheets(sheets: List<Int>?, sheetCount: Int, fileName: String): List<Int> {
        if (sheets == null) return (1..sheetCount).toList()

        val selection = sheets.distinct().sorted()
        require(selection.isNotEmpty()) { "\"sheets\" must not be empty: $fileName" }
        val outOfRange = selection.filter { it !in 1..sheetCount }
        require(outOfRange.isEmpty()) {
            "Sheet(s) $outOfRange out of range: $fileName has $sheetCount sheet(s)."
        }
        return selection
    }

    /**
     * Used range of a sheet, scanning only physically-existing rows (bounded by what POI
     * parsed): trailing style-only rows are excluded from the row total, and the column
     * count covers the whole sheet so it stays stable across paged calls.
     */
    private fun scanUsedRange(sheet: Sheet): UsedRange =
        sheet.rowIterator().asSequence().fold(UsedRange(lastContentRow = -1, columnCount = 0, columnsTruncated = false)) { acc, row ->
            val lastCol = row.cellIterator().asSequence()
                .filter { it.cellType != CellType.BLANK }
                .maxOfOrNull { it.columnIndex }
                ?: return@fold acc // row with no content: leaves the range unchanged
            UsedRange(
                lastContentRow = maxOf(acc.lastContentRow, row.rowNum),
                columnCount = maxOf(acc.columnCount, minOf(lastCol + 1, spreadsheetMaxColumns)),
                columnsTruncated = acc.columnsTruncated || lastCol + 1 > spreadsheetMaxColumns,
            )
        }

    private fun sheetTitle(sheetNumber: Int, sheetCount: Int, workbook: XSSFWorkbook): String {
        val index = sheetNumber - 1
        val hidden = workbook.isSheetHidden(index) || workbook.isSheetVeryHidden(index)
        return "## Sheet $sheetNumber/$sheetCount \"${workbook.getSheetName(index)}\"" +
            if (hidden) " (hidden)" else ""
    }

    private fun describeColumns(range: UsedRange): String =
        "columns A-${columnLetter(range.columnCount - 1)}" +
            if (range.columnsTruncated) " (truncated at $spreadsheetMaxColumns)" else ""

    private fun renderRow(row: Row?, columnCount: Int, formatter: DataFormatter, date1904: Boolean): String {
        if (row == null) return ""
        return (0 until columnCount)
            .map { column ->
                val cell = row.getCell(column)
                if (cell == null) "" else csvField(truncateCell(renderCell(cell, formatter, date1904)))
            }
            .dropLastWhile { it.isEmpty() }
            .joinToString(",")
    }

    private fun renderCell(cell: Cell, formatter: DataFormatter, date1904: Boolean): String =
        when (cell.cellType) {
            CellType.FORMULA -> renderFormulaCell(cell, formatter, date1904)
            CellType.ERROR -> formulaErrorText(cell.errorCellValue)
            else -> formatter.formatCellValue(cell)
        }

    /**
     * Formula cells read the cached last-saved result: without an evaluator,
     * [DataFormatter.formatCellValue] would return the formula text itself. A formula
     * saved without any cached result renders as `=formula` (POI would otherwise report
     * a fabricated numeric 0).
     */
    private fun renderFormulaCell(cell: Cell, formatter: DataFormatter, date1904: Boolean): String {
        if (!hasCachedFormulaResult(cell)) return runCatching { "=${cell.cellFormula}" }.getOrDefault("")
        return when (cell.cachedFormulaResultType) {
            // formatRawCellContents applies the cell's number format and detects
            // date formats internally; the flag keeps 1904-system dates correct.
            CellType.NUMERIC -> formatter.formatRawCellContents(
                cell.numericCellValue,
                cell.cellStyle.dataFormat.toInt(),
                cell.cellStyle.dataFormatString,
                date1904,
            )
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> if (cell.booleanCellValue) "TRUE" else "FALSE"
            CellType.ERROR -> formulaErrorText(cell.errorCellValue)
            else -> ""
        }
    }

    /**
     * A formula cell written without evaluation carries no cached `<v>` element (openpyxl,
     * exceljs and pandas produce such files); POI then reports a numeric 0 that never
     * existed in the sheet.
     */
    private fun hasCachedFormulaResult(cell: Cell): Boolean = (cell as? XSSFCell)?.ctCell?.isSetV ?: true

    private fun formulaErrorText(code: Byte): String =
        runCatching { FormulaError.forInt(code.toInt()).string }.getOrDefault("#ERROR")

    private fun truncateCell(value: String): String =
        if (value.length <= spreadsheetMaxCellChars) value else cutAtCharBoundary(value, spreadsheetMaxCellChars) + CELL_TRUNCATED_MARKER

    /** Cuts to at most [maxLength] UTF-16 units without splitting a surrogate pair. */
    private fun cutAtCharBoundary(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        val cut = if (maxLength > 0 && Character.isHighSurrogate(value[maxLength - 1])) maxLength - 1 else maxLength
        return value.take(cut)
    }

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
        out.append("\n[truncated] Per-call limit reached (max $spreadsheetMaxRows rows / $spreadsheetMaxOutputChars chars). ")
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

        private const val CELL_TRUNCATED_MARKER = "…[cell truncated]"
        private const val ROW_TRUNCATED_MARKER = "…[row truncated]"
    }
}
