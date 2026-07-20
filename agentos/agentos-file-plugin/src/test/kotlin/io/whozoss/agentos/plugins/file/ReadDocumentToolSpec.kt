package io.whozoss.agentos.plugins.file

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        "startElement out of range is rejected with the element count" {
            writeDocx("short.docx") { paragraph("only one") }

            val result = ReadDocumentTool(tempDir).execute(ReadDocumentTool.Input("short.docx", startElement = 99), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "out of range"
        }
    }
}
