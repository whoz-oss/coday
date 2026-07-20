package io.whozoss.agentos.plugins.file

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.file.tools.ReadDocumentTool
import io.whozoss.agentos.sdk.tool.ToolContext
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ReadDocumentToolSpec : StringSpec() {
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())
    private lateinit var tempDir: Path

    private fun writeDocx(name: String, build: XWPFDocument.() -> Unit): Path {
        val file = tempDir.resolve(name)
        XWPFDocument().use { document ->
            document.build()
            Files.newOutputStream(file).use { document.write(it) }
        }
        return file
    }

    private fun XWPFDocument.paragraph(text: String, style: String? = null, numId: Int? = null) {
        val paragraph = createParagraph()
        style?.let { paragraph.style = it }
        numId?.let { paragraph.numID = BigInteger.valueOf(it.toLong()) }
        paragraph.createRun().setText(text)
    }

    /** High-entropy text so POI's ZipSecureFile does not reject the fixture as a zip bomb. */
    private fun randomText(length: Int, seed: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.util.Random(seed.toLong())
        return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun pngBytes(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    /** Minimal valid PNG header (signature + IHDR with correct CRC, no pixel data). */
    private fun pngHeaderBytes(width: Int, height: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13)
            .putInt(width).putInt(height)
            .put(8).put(2).put(0).put(0).put(0)
            .array()
        val crc = CRC32().apply { update("IHDR".toByteArray(Charsets.US_ASCII)); update(ihdr) }
        return ByteBuffer.allocate(33)
            .put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            .putInt(13).put("IHDR".toByteArray(Charsets.US_ASCII)).put(ihdr).putInt(crc.value.toInt())
            .array()
    }

    private fun writeProtectedDocx(name: String, password: String): Path {
        val clearBytes = ByteArrayOutputStream().use { buffer ->
            XWPFDocument().use { it.paragraph("secret"); it.write(buffer) }
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

    init {
        beforeEach { tempDir = Files.createTempDirectory("read-document-test") }
        afterEach { tempDir.toFile().deleteRecursively() }

        "headings, paragraphs and list items render as Markdown" {
            writeDocx("doc.docx") {
                paragraph("Title", style = "Heading1")
                paragraph("Section", style = "Heading2")
                paragraph("A plain paragraph.")
                paragraph("First bullet", numId = 1)
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("doc.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "# Title"
            result.output shouldContain "## Section"
            result.output shouldContain "A plain paragraph."
            result.output shouldContain "- First bullet"
        }

        "table renders as a Markdown table with escaped pipes" {
            writeDocx("table.docx") {
                val table = createTable(2, 2)
                table.getRow(0).getCell(0).setText("H1")
                table.getRow(0).getCell(1).setText("H2")
                table.getRow(1).getCell(0).setText("a|b")
                table.getRow(1).getCell(1).setText("d")
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("table.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "| H1 | H2 |"
            result.output shouldContain "| --- | --- |"
            result.output shouldContain "a\\|b" // pipe escaped so it does not break the column
        }

        "embedded picture is attached as an image" {
            val file = writeDocx("with-image.docx") { paragraph("See the diagram:") }
            // Re-open to add a real picture inside a run, then re-save.
            XWPFDocument(Files.newInputStream(file)).use { document ->
                document.createParagraph().createRun()
                    .addPicture(ByteArrayInputStream(pngBytes(60, 40)), Document.PICTURE_TYPE_PNG, "diagram.png", 60, 40)
                Files.newOutputStream(file).use { document.write(it) }
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("with-image.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.images shouldHaveSize 1
            result.images.single().mimeType shouldBe "image/jpeg"
            result.output shouldContain "1 embedded image(s) attached"
        }

        "oversized embedded picture is skipped, the text still returns" {
            val file = writeDocx("bomb.docx") { paragraph("text before the bomb") }
            XWPFDocument(Files.newInputStream(file)).use { document ->
                // 30000x30000 declared in a 33-byte header: over the decode cap, must be skipped.
                document.createParagraph().createRun()
                    .addPicture(ByteArrayInputStream(pngHeaderBytes(30_000, 30_000)), Document.PICTURE_TYPE_PNG, "huge.png", 10, 10)
                Files.newOutputStream(file).use { document.write(it) }
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("bomb.docx"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 0
            result.output shouldContain "text before the bomb"
        }

        "legacy .doc is rejected with UNSUPPORTED_FORMAT" {
            tempDir.resolve("old.doc").writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()))

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("old.doc"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain ".docx"
        }

        "other extensions are rejected with UNSUPPORTED_FORMAT" {
            tempDir.resolve("notes.odt").writeText("open document")

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("notes.odt"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
        }

        "password-protected docx is rejected" {
            writeProtectedDocx("locked.docx", "secret")

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("locked.docx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "password-protected"
        }

        "corrupt bytes named .docx are rejected as invalid" {
            tempDir.resolve("corrupt.docx").writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("corrupt.docx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
        }

        "missing file returns an error" {
            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("absent.docx"), ctx)

            result.success shouldBe false
            result.output shouldContain "Path does not exist"
        }

        "file exceeding the size cap is rejected before parsing" {
            val file = writeDocx("big.docx") { paragraph("small doc") }
            val tool = ReadDocumentTool(tempDir, readMaxSizeBytes = Files.size(file) - 1)

            val result = tool.execute(ReadDocumentTool.Input("big.docx"), ctx)

            result.success shouldBe false
            result.output shouldContain "exceeds maximum size"
        }

        "sensitive file patterns are enforced" {
            tempDir.resolve("secret.pem").writeText("---KEY---")

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("secret.pem"), ctx)

            result.success shouldBe false
            result.output shouldContain "Access denied"
        }

        "path traversal is rejected" {
            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("../outside.docx"), ctx)

            result.success shouldBe false
        }

        "startElement pages through a document and reports continuation" {
            writeDocx("long.docx") { repeat(5) { paragraph("Paragraph ${it + 1}") } }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("long.docx", startElement = 4), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "Paragraph 4"
            result.output shouldContain "Paragraph 5"
        }

        "block content control text is included, not silently dropped" {
            writeDocx("sdt.docx") {
                paragraph("Before control")
                // Block-level structured document tag (content control): its paragraph lives
                // under w:sdtContent, so getBodyElements() surfaces it only as an XWPFSDT.
                val content = document.body.addNewSdt().addNewSdtContent()
                content.addNewP().addNewR().addNewT().stringValue = "Inside a content control"
                paragraph("After control")
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("sdt.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "Before control"
            result.output shouldContain "Inside a content control"
            result.output shouldContain "After control"
        }

        "a single oversized table is truncated in place instead of blowing the budget" {
            writeDocx("huge-table.docx") {
                val table = createTable(200, 1)
                // Distinct high-entropy text per row: a repeated filler would trip POI's zip-bomb guard.
                table.rows.forEachIndexed { index, row -> row.getCell(0).setText(randomText(1000, index)) }
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("huge-table.docx"), ctx)

            withClue(result.output.take(200)) { result.success shouldBe true }
            result.output shouldContain "table truncated"
            // The whole 200x1 table would be ~200 KB; the per-row budget caps it near 100 KB.
            withClue("output length ${result.output.length}") {
                (result.output.length < ReadDocumentTool.MAX_OUTPUT_CHARS + 5000) shouldBe true
            }
        }

        "a single oversized paragraph is capped to the budget" {
            writeDocx("huge-paragraph.docx") {
                paragraph(randomText(ReadDocumentTool.MAX_OUTPUT_CHARS * 2, seed = 7))
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("huge-paragraph.docx"), ctx)

            withClue(result.output.take(200)) { result.success shouldBe true }
            result.output shouldContain "truncated"
            withClue("output length ${result.output.length}") {
                (result.output.length < ReadDocumentTool.MAX_OUTPUT_CHARS + 5000) shouldBe true
            }
        }

        "embedded images are attached on the first page only, not on paged continuations" {
            val file = writeDocx("paged-image.docx") { repeat(3) { paragraph("Paragraph ${it + 1}") } }
            XWPFDocument(Files.newInputStream(file)).use { document ->
                document.createParagraph().createRun()
                    .addPicture(ByteArrayInputStream(pngBytes(60, 40)), Document.PICTURE_TYPE_PNG, "diagram.png", 60, 40)
                Files.newOutputStream(file).use { document.write(it) }
            }

            val firstPage = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("paged-image.docx"), ctx)
            firstPage.images shouldHaveSize 1

            val secondPage =
                ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("paged-image.docx", startElement = 2), ctx)
            secondPage.success shouldBe true
            secondPage.images shouldHaveSize 0
        }

        "more pictures than the cap: only the cap is attached and the notice reports the rest" {
            val file = writeDocx("gallery.docx") { paragraph("gallery") }
            XWPFDocument(Files.newInputStream(file)).use { document ->
                // Distinct dimensions per picture so POI does not deduplicate identical bytes.
                repeat(ReadDocumentTool.MAX_ATTACHED_IMAGES + 1) { i ->
                    document.createParagraph().createRun()
                        .addPicture(ByteArrayInputStream(pngBytes(60 + i, 40)), Document.PICTURE_TYPE_PNG, "img$i.png", 60, 40)
                }
                Files.newOutputStream(file).use { document.write(it) }
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("gallery.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.images shouldHaveSize ReadDocumentTool.MAX_ATTACHED_IMAGES
            result.output shouldContain "(1 of ${ReadDocumentTool.MAX_ATTACHED_IMAGES + 1} not shown)"
        }

        "a document whose pictures are all oversized reports them present but not shown" {
            val file = writeDocx("all-oversized.docx") { paragraph("text") }
            XWPFDocument(Files.newInputStream(file)).use { document ->
                document.createParagraph().createRun()
                    .addPicture(ByteArrayInputStream(pngHeaderBytes(30_000, 30_000)), Document.PICTURE_TYPE_PNG, "huge.png", 10, 10)
                Files.newOutputStream(file).use { document.write(it) }
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("all-oversized.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.images shouldHaveSize 0
            result.output shouldContain "none could be shown"
        }

        "a table cell with a backslash before a pipe is fully escaped, not corrupted" {
            val raw = "a\\|b" // a, backslash, pipe, b
            writeDocx("backslash.docx") {
                val table = createTable(2, 1)
                table.getRow(0).getCell(0).setText("H")
                table.getRow(1).getCell(0).setText(raw)
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("backslash.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            // Backslash escaped first, then the pipe: round-trips to the literal "a\|b".
            result.output shouldContain raw.replace("\\", "\\\\").replace("|", "\\|")
        }

        "a multi-paragraph table cell is joined, not concatenated without a separator" {
            writeDocx("multipara.docx") {
                val table = createTable(2, 1)
                table.getRow(0).getCell(0).setText("Header")
                val cell = table.getRow(1).getCell(0)
                cell.paragraphs[0].createRun().setText("Total")
                cell.addParagraph().createRun().setText("100")
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("multipara.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "Total<br>100"
            result.output shouldNotContain "Total100"
        }

        "a heading level deeper than 6 is clamped to valid CommonMark" {
            writeDocx("deep-heading.docx") { paragraph("Deep", style = "Heading7") }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("deep-heading.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "###### Deep"
            result.output shouldNotContain "####### Deep"
        }

        "content exceeding the budget across elements reports [truncated] with a continuation startElement" {
            writeDocx("huge-multi.docx") { repeat(130) { paragraph(randomText(1000, it)) } }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("huge-multi.docx"), ctx)

            withClue(result.output.take(200)) { result.success shouldBe true }
            result.output shouldContain "[truncated]"
            result.output shouldContain "startElement="
        }

        "a ragged table (rows with unequal cell counts) is padded to a consistent column count" {
            writeDocx("ragged.docx") {
                val table = createTable(2, 2)
                table.getRow(0).getCell(0).setText("A")
                table.getRow(0).getCell(1).setText("B")
                table.getRow(0).createCell().setText("C") // row 0 widened to 3 cells
                table.getRow(1).getCell(0).setText("D")
                table.getRow(1).getCell(1).setText("E") // row 1 stays at 2 cells
            }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("ragged.docx"), ctx)

            withClue(result.output) { result.success shouldBe true }
            result.output shouldContain "| --- | --- | --- |" // separator widened to the max column count
            result.output shouldContain "| D | E |  |" // short row padded to 3 columns
        }

        "startElement out of range is rejected with the element count" {
            writeDocx("short.docx") { paragraph("only one") }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("short.docx", startElement = 99), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "out of range"
        }
    }
}
