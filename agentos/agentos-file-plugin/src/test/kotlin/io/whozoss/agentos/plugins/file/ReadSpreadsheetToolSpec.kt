package io.whozoss.agentos.plugins.file

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.ReadSpreadsheetTool
import io.whozoss.agentos.sdk.tool.ToolContext
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ReadSpreadsheetToolSpec : StringSpec() {
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())
    private lateinit var tempDir: Path

    private fun tool() = ReadSpreadsheetTool(tempDir)

    private fun writeXlsx(name: String, build: XSSFWorkbook.() -> Unit): Path {
        val file = tempDir.resolve(name)
        XSSFWorkbook().use { workbook ->
            workbook.build()
            Files.newOutputStream(file).use { workbook.write(it) }
        }
        return file
    }

    /** Fills a sheet from a row-major grid; null skips the cell (leaves it missing). */
    private fun XSSFWorkbook.sheetOf(name: String, rows: List<List<Any?>>): XSSFSheet {
        val sheet = createSheet(name)
        rows.forEachIndexed { rowIndex, cells ->
            val row = sheet.createRow(rowIndex)
            cells.forEachIndexed { columnIndex, value ->
                when (value) {
                    null -> Unit
                    is String -> row.createCell(columnIndex).setCellValue(value)
                    is Int -> row.createCell(columnIndex).setCellValue(value.toDouble())
                    is Double -> row.createCell(columnIndex).setCellValue(value)
                    is Boolean -> row.createCell(columnIndex).setCellValue(value)
                    else -> error("Unsupported fixture cell type: $value")
                }
            }
        }
        return sheet
    }

    private fun writeEncryptedXlsx(name: String, password: String): Path {
        val clearBytes = ByteArrayOutputStream().use { buffer ->
            XSSFWorkbook().use { workbook ->
                workbook.createSheet("Data")
                workbook.write(buffer)
            }
            buffer.toByteArray()
        }

        val file = tempDir.resolve(name)
        POIFSFileSystem().use { fs ->
            val encryptor = EncryptionInfo(EncryptionMode.agile).encryptor
            encryptor.confirmPassword(password)
            encryptor.getDataStream(fs).use { it.write(clearBytes) }
            Files.newOutputStream(file).use { fs.writeFilesystem(it) }
        }
        return file
    }

    private suspend fun readOk(input: ReadSpreadsheetTool.Input): String {
        val result = tool().execute(input, ctx)
        withClue(result.output) {
            result.success shouldBe true
        }
        return result.output
    }

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("read-spreadsheet-test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "single sheet renders a header line and CSV rows" {
            writeXlsx("data.xlsx") {
                sheetOf("Data", listOf(listOf("Name", "Amount"), listOf("Alice", 12.5)))
            }

            val output = readOk(ReadSpreadsheetTool.Input("data.xlsx"))

            output shouldContain "## Sheet 1/1 \"Data\": rows 1-2 of 2, columns A-B"
            output shouldContain "Name,Amount"
            output shouldContain "Alice,12.5"
        }

        "multi-sheet workbook renders one block per sheet in workbook order" {
            writeXlsx("multi.xlsx") {
                sheetOf("First", listOf(listOf("a")))
                sheetOf("Second", listOf(listOf("b")))
                sheetOf("Third", listOf(listOf("c")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("multi.xlsx"))

            output shouldContain "## Sheet 1/3 \"First\""
            output shouldContain "## Sheet 2/3 \"Second\""
            output shouldContain "## Sheet 3/3 \"Third\""
            output.indexOf("\"First\"") shouldBeLessThan output.indexOf("\"Second\"")
            output.indexOf("\"Second\"") shouldBeLessThan output.indexOf("\"Third\"")
        }

        "fields containing comma, quote or newline are RFC 4180 quoted" {
            writeXlsx("quoting.xlsx") {
                sheetOf("Q", listOf(listOf("Smith, John", "say \"hi\"", "line1\nline2")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("quoting.xlsx"))

            output shouldContain "\"Smith, John\",\"say \"\"hi\"\"\",\"line1\nline2\""
        }

        "numeric cell renders through its number format" {
            writeXlsx("format.xlsx") {
                val sheet = createSheet("F")
                val style = createCellStyle().apply { dataFormat = createDataFormat().getFormat("0.00") }
                sheet.createRow(0).createCell(0).apply {
                    setCellValue(1234.5)
                    cellStyle = style
                }
            }

            val output = readOk(ReadSpreadsheetTool.Input("format.xlsx"))

            output shouldContain "1234.50"
        }

        "date cell renders the formatted date, not the Excel serial" {
            writeXlsx("date.xlsx") {
                val sheet = createSheet("D")
                val style = createCellStyle().apply { dataFormat = createDataFormat().getFormat("yyyy-mm-dd") }
                sheet.createRow(0).createCell(0).apply {
                    setCellValue(LocalDate.of(2026, 1, 15))
                    cellStyle = style
                }
            }

            val output = readOk(ReadSpreadsheetTool.Input("date.xlsx"))

            output shouldContain "2026-01-15"
            output shouldNotContain "46037"
        }

        "boolean cells render TRUE and FALSE" {
            writeXlsx("bool.xlsx") {
                sheetOf("B", listOf(listOf(true, false)))
            }

            val output = readOk(ReadSpreadsheetTool.Input("bool.xlsx"))

            output shouldContain "TRUE,FALSE"
        }

        "formula cell returns its cached result without re-evaluation" {
            writeXlsx("formula.xlsx") {
                val sheet = createSheet("F")
                val row = sheet.createRow(0)
                row.createCell(0).setCellValue(2.0)
                row.createCell(1).cellFormula = "A1*2"
                creationHelper.createFormulaEvaluator().evaluateAll()
                // Change the input AFTER evaluation: the cached result must stay stale.
                row.getCell(0).setCellValue(10.0)
            }

            val output = readOk(ReadSpreadsheetTool.Input("formula.xlsx"))

            output shouldContain "10,4"
        }

        "formula with a cached string result renders the string" {
            writeXlsx("formula-string.xlsx") {
                val sheet = createSheet("F")
                sheet.createRow(0).createCell(0).cellFormula = "\"a\"&\"b\""
                creationHelper.createFormulaEvaluator().evaluateAll()
            }

            val output = readOk(ReadSpreadsheetTool.Input("formula-string.xlsx"))

            output shouldContain "ab"
        }

        "formula with a cached error renders the Excel error literal" {
            writeXlsx("formula-error.xlsx") {
                val sheet = createSheet("F")
                sheet.createRow(0).createCell(0).cellFormula = "1/0"
                creationHelper.createFormulaEvaluator().evaluateAll()
            }

            val output = readOk(ReadSpreadsheetTool.Input("formula-error.xlsx"))

            output shouldContain "#DIV/0!"
        }

        "merged region keeps its value in the top-left cell only" {
            writeXlsx("merged.xlsx") {
                val sheet = sheetOf(
                    "M",
                    listOf(
                        listOf("Merged", null, "C"),
                        listOf(null, null, "D"),
                    ),
                )
                sheet.addMergedRegion(CellRangeAddress(0, 1, 0, 1))
            }

            val output = readOk(ReadSpreadsheetTool.Input("merged.xlsx"))

            output shouldContain "Merged,,C"
            output shouldContain ",,D"
        }

        "hidden sheet is included and marked" {
            writeXlsx("hidden.xlsx") {
                sheetOf("Visible", listOf(listOf("a")))
                sheetOf("Secret", listOf(listOf("b")))
                setSheetHidden(1, true)
            }

            val output = readOk(ReadSpreadsheetTool.Input("hidden.xlsx"))

            output shouldContain "## Sheet 2/2 \"Secret\" (hidden):"
            output shouldContain "b"
        }

        "interior empty row is preserved as an empty line and counted" {
            writeXlsx("gap.xlsx") {
                val sheet = createSheet("G")
                sheet.createRow(0).createCell(0).setCellValue("a")
                sheet.createRow(2).createCell(0).setCellValue("b")
            }

            val output = readOk(ReadSpreadsheetTool.Input("gap.xlsx"))

            output shouldContain "rows 1-3 of 3"
            output shouldContain "a\n\nb"
        }

        "trailing style-only rows are excluded from totals" {
            writeXlsx("styled.xlsx") {
                val sheet = sheetOf("S", listOf(listOf("a"), listOf("b")))
                // A blank-typed cell with only a style, far below the content.
                val style = createCellStyle().apply { dataFormat = createDataFormat().getFormat("0.00") }
                sheet.createRow(50).createCell(0).cellStyle = style
            }

            val output = readOk(ReadSpreadsheetTool.Input("styled.xlsx"))

            output shouldContain "rows 1-2 of 2"
        }

        "empty sheet renders an empty marker" {
            writeXlsx("empty-sheet.xlsx") {
                sheetOf("Data", listOf(listOf("a")))
                createSheet("Empty")
            }

            val output = readOk(ReadSpreadsheetTool.Input("empty-sheet.xlsx"))

            output shouldContain "## Sheet 2/2 \"Empty\": empty"
        }

        "sheets parameter selects a single sheet" {
            writeXlsx("select.xlsx") {
                sheetOf("First", listOf(listOf("a")))
                sheetOf("Second", listOf(listOf("b")))
                sheetOf("Third", listOf(listOf("c")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("select.xlsx", sheets = listOf(2)))

            output shouldContain "## Sheet 2/3 \"Second\""
            output shouldNotContain "## Sheet 1/3"
            output shouldNotContain "## Sheet 3/3"
        }

        "startRow windows a single sheet" {
            writeXlsx("window.xlsx") {
                sheetOf("W", (1..25).map { listOf("row$it") })
            }

            val output = readOk(ReadSpreadsheetTool.Input("window.xlsx", sheets = listOf(1), startRow = 11))

            output shouldContain "rows 11-25 of 25"
            output shouldContain "row11"
            output shouldNotContain "row10"
        }

        "row cap truncates with a continuation notice" {
            writeXlsx("long.xlsx") {
                sheetOf("L", (1..1050).map { listOf("row$it") })
            }

            val output = readOk(ReadSpreadsheetTool.Input("long.xlsx"))

            output shouldContain "rows 1-1000 of 1050"
            output shouldContain "[truncated]"
            output shouldContain "sheets=[1], startRow=1001"
        }

        "character budget truncates wide sheets before the row cap" {
            val wide = "x".repeat(4500)
            writeXlsx("wide.xlsx") {
                sheetOf("W", (1..30).map { listOf(wide) })
            }

            val output = readOk(ReadSpreadsheetTool.Input("wide.xlsx"))

            output shouldContain "[truncated]"
            output shouldContain "startRow="
            output shouldNotContain "rows 1-30 of 30"
            output.length shouldBeLessThan 101_000
        }

        "truncation at a sheet boundary lists the remaining sheets" {
            writeXlsx("boundary.xlsx") {
                sheetOf("Big", (1..1000).map { listOf("row$it") })
                sheetOf("Q3", listOf(listOf("q3")))
                sheetOf("Q4", listOf(listOf("q4")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("boundary.xlsx"))

            output shouldContain "rows 1-1000 of 1000"
            output shouldContain "Continue with sheets=[2, 3]."
            output shouldNotContain "q3"
        }

        "mid-sheet truncation lists the sheets not shown" {
            writeXlsx("midsheet.xlsx") {
                sheetOf("Big", (1..1500).map { listOf("row$it") })
                sheetOf("Q3", listOf(listOf("q3")))
                sheetOf("Q4", listOf(listOf("q4")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("midsheet.xlsx"))

            output shouldContain "sheets=[1], startRow=1001"
            output shouldContain "Sheet(s) not shown: 2 \"Q3\", 3 \"Q4\"."
        }

        "oversized cell is cut with a marker" {
            writeXlsx("bigcell.xlsx") {
                sheetOf("B", listOf(listOf("y".repeat(30_000))))
            }

            val output = readOk(ReadSpreadsheetTool.Input("bigcell.xlsx"))

            output shouldContain "[cell truncated]"
            output shouldNotContain "y".repeat(6000)
        }

        "startRow with multiple sheets targeted is rejected" {
            writeXlsx("multi-start.xlsx") {
                sheetOf("A", listOf(listOf("a")))
                sheetOf("B", listOf(listOf("b")))
            }

            val result = tool().execute(ReadSpreadsheetTool.Input("multi-start.xlsx", startRow = 2), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "exactly one sheet"
        }

        "startRow beyond the sheet rows is rejected with the actual row count" {
            writeXlsx("short.xlsx") {
                sheetOf("S", listOf(listOf("a"), listOf("b"), listOf("c")))
            }

            val result = tool().execute(ReadSpreadsheetTool.Input("short.xlsx", startRow = 9), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "has 3 row(s)"
        }

        "sheet out of range is rejected with the actual sheet count" {
            writeXlsx("range.xlsx") {
                sheetOf("Only", listOf(listOf("a")))
            }

            val result = tool().execute(ReadSpreadsheetTool.Input("range.xlsx", sheets = listOf(9)), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "has 1 sheet(s)"
        }

        "empty sheets selection is rejected" {
            writeXlsx("empty-selection.xlsx") {
                sheetOf("Only", listOf(listOf("a")))
            }

            val result = tool().execute(ReadSpreadsheetTool.Input("empty-selection.xlsx", sheets = emptyList()), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
        }

        "workbook without any sheet is rejected" {
            writeXlsx("no-sheet.xlsx") {}

            val result = tool().execute(ReadSpreadsheetTool.Input("no-sheet.xlsx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "no sheet"
        }

        "password-protected xlsx is rejected" {
            writeEncryptedXlsx("locked.xlsx", "secret")

            val result = tool().execute(ReadSpreadsheetTool.Input("locked.xlsx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "password-protected"
        }

        "corrupt bytes named .xlsx are rejected as invalid" {
            tempDir.resolve("corrupt.xlsx").writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            val result = tool().execute(ReadSpreadsheetTool.Input("corrupt.xlsx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
        }

        "file exceeding size cap is rejected before parsing" {
            val file = writeXlsx("big.xlsx") { sheetOf("B", listOf(listOf("a"))) }
            val tool = ReadSpreadsheetTool(tempDir, readMaxSizeBytes = Files.size(file) - 1)

            val result = tool.execute(ReadSpreadsheetTool.Input("big.xlsx"), ctx)

            result.success shouldBe false
            result.output shouldContain "exceeds maximum size"
        }

        "sensitive file patterns are enforced" {
            tempDir.resolve("secret.pem").writeText("---KEY---")

            val result = tool().execute(ReadSpreadsheetTool.Input("secret.pem"), ctx)

            result.success shouldBe false
            result.output shouldContain "Access denied"
        }

        "path traversal is rejected" {
            val result = tool().execute(ReadSpreadsheetTool.Input("../outside.xlsx"), ctx)

            result.success shouldBe false
        }

        "missing file returns an error" {
            val result = tool().execute(ReadSpreadsheetTool.Input("absent.xlsx"), ctx)

            result.success shouldBe false
            result.output shouldContain "Path does not exist"
        }

        ".csv is rejected pointing to readFile" {
            tempDir.resolve("data.csv").writeText("a,b\n1,2\n")

            val result = tool().execute(ReadSpreadsheetTool.Input("data.csv"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain "readFile"
        }

        ".xls is rejected with a convert hint" {
            tempDir.resolve("legacy.xls").writeBytes(byteArrayOf(0x00, 0x01))

            val result = tool().execute(ReadSpreadsheetTool.Input("legacy.xls"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain "Convert .xls/.xlsm/.ods to .xlsx"
        }

        ".ods is rejected" {
            tempDir.resolve("calc.ods").writeBytes(byteArrayOf(0x00, 0x01))

            val result = tool().execute(ReadSpreadsheetTool.Input("calc.ods"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
        }

        ".xlsm is rejected with a convert hint" {
            tempDir.resolve("macro.xlsm").writeBytes(byteArrayOf(0x00, 0x01))

            val result = tool().execute(ReadSpreadsheetTool.Input("macro.xlsm"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain "Convert .xls/.xlsm/.ods to .xlsx"
        }

        "formula saved without a cached result renders the formula text, not a fabricated zero" {
            writeXlsx("uncached.xlsx") {
                val sheet = createSheet("F")
                val row = sheet.createRow(0)
                row.createCell(0).setCellValue(2.0)
                // No evaluateAll(): openpyxl/exceljs-style file whose formulas carry no cached value.
                row.createCell(1).cellFormula = "A1*2"
            }

            val output = readOk(ReadSpreadsheetTool.Input("uncached.xlsx"))

            output shouldContain "2,=A1*2"
        }

        "1904 date-system workbook renders formula dates with the right epoch" {
            writeXlsx("date1904.xlsx") {
                val workbookPr = ctWorkbook.workbookPr ?: ctWorkbook.addNewWorkbookPr()
                workbookPr.date1904 = true
                val sheet = createSheet("D")
                val style = createCellStyle().apply { dataFormat = createDataFormat().getFormat("yyyy-mm-dd") }
                val row = sheet.createRow(0)
                row.createCell(0).apply {
                    setCellValue(44575.0)
                    cellStyle = style
                }
                row.createCell(1).apply {
                    cellFormula = "A1"
                    cellStyle = style
                }
                creationHelper.createFormulaEvaluator().evaluateAll()
            }

            val output = readOk(ReadSpreadsheetTool.Input("date1904.xlsx"))

            // Plain cell and formula cell must agree; with a 1900-epoch decode the formula
            // cell would read 2022-01-14.
            output shouldContain "2026-01-15,2026-01-15"
        }

        "cell truncation does not split a surrogate pair" {
            writeXlsx("emoji.xlsx") {
                sheetOf("E", listOf(listOf("x".repeat(4999) + "😀" + "tail")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("emoji.xlsx"))

            output shouldContain "x…[cell truncated]"
            output shouldNotContain "\uD83D…[cell truncated]"
        }

        "a single row larger than the character budget is cut with a row marker and stays bounded" {
            val wide = "z".repeat(1000)
            writeXlsx("monster.xlsx") {
                val sheet = createSheet("M")
                val row = sheet.createRow(0)
                (0 until 256).forEach { column -> row.createCell(column).setCellValue(wide) }
            }

            val output = readOk(ReadSpreadsheetTool.Input("monster.xlsx"))

            output shouldContain "[row truncated]"
            output.length shouldBeLessThan 101_000
        }

        "sheet wider than the column cap is cut and flagged in the header" {
            writeXlsx("columns.xlsx") {
                val sheet = createSheet("W")
                val row = sheet.createRow(0)
                (0 until 300).forEach { column -> row.createCell(column).setCellValue("c$column") }
            }

            val output = readOk(ReadSpreadsheetTool.Input("columns.xlsx"))

            output shouldContain "columns A-IV (truncated at 256)"
            output shouldContain "c255"
            output shouldNotContain "c256"
        }

        "startRow below 1 is rejected" {
            writeXlsx("zero.xlsx") { sheetOf("S", listOf(listOf("a"))) }

            val result = tool().execute(ReadSpreadsheetTool.Input("zero.xlsx", startRow = 0), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "at least 1"
        }

        "duplicate and unordered sheet selection is deduplicated and rendered in workbook order" {
            writeXlsx("dupes.xlsx") {
                sheetOf("First", listOf(listOf("a")))
                sheetOf("Second", listOf(listOf("b")))
                sheetOf("Third", listOf(listOf("c")))
            }

            val output = readOk(ReadSpreadsheetTool.Input("dupes.xlsx", sheets = listOf(3, 1, 1)))

            output shouldContain "## Sheet 1/3 \"First\""
            output shouldContain "## Sheet 3/3 \"Third\""
            output shouldNotContain "## Sheet 2/3"
            output.indexOf("\"First\"") shouldBeLessThan output.indexOf("\"Third\"")
            output.split("## Sheet 1/3").size shouldBe 2
        }

        "trailing empty cells are trimmed without leaving separators" {
            writeXlsx("trailing.xlsx") {
                sheetOf("T", listOf(listOf("a", "b", "c"), listOf("d", null, null)))
            }

            val output = readOk(ReadSpreadsheetTool.Input("trailing.xlsx"))

            output shouldContain "a,b,c\nd\n"
        }

        "a valid zip that is not a workbook is rejected as invalid input" {
            val file = tempDir.resolve("archive.xlsx")
            ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("hello.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }

            val result = tool().execute(ReadSpreadsheetTool.Input("archive.xlsx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "Not a valid .xlsx"
        }

        "open failure does not leak the absolute server path" {
            val file = writeXlsx("locked-perm.xlsx") { sheetOf("S", listOf(listOf("a"))) }
            val restricted = file.toFile().setReadable(false, false)
            // Meaningless when the OS ignores the permission change (e.g. running as root).
            if (restricted && !file.toFile().canRead()) {
                val result = tool().execute(ReadSpreadsheetTool.Input("locked-perm.xlsx"), ctx)

                result.success shouldBe false
                result.output shouldNotContain tempDir.pathString
                result.output shouldContain "locked-perm.xlsx"
            }
        }
    }
}
