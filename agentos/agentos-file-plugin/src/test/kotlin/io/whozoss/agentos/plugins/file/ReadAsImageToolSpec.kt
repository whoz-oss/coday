package io.whozoss.agentos.plugins.file

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.plugins.file.tools.ReadAsImageTool
import io.whozoss.agentos.sdk.tool.ToolContext
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ReadAsImageToolSpec : StringSpec() {
    private val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())
    private lateinit var tempDir: Path

    private fun writePng(name: String, width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): Path {
        val file = tempDir.resolve(name)
        ImageIO.write(BufferedImage(width, height, type), "png", file.toFile())
        return file
    }

    private fun writePdf(name: String, pageCount: Int, protection: StandardProtectionPolicy? = null): Path {
        val file = tempDir.resolve(name)
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage()) }
            protection?.let { document.protect(it) }
            document.save(file.toFile())
        }
        return file
    }

    private fun writePptx(name: String, slideCount: Int): Path {
        val file = tempDir.resolve(name)
        XMLSlideShow().use { presentation ->
            repeat(slideCount) { index ->
                val box = presentation.createSlide().createTextBox()
                box.setAnchor(Rectangle(50, 50, 400, 100))
                box.text = "Slide ${index + 1}"
            }
            Files.newOutputStream(file).use { presentation.write(it) }
        }
        return file
    }

    /**
     * Minimal valid PNG header (signature + IHDR with correct CRC, no pixel data):
     * enough for the header sniff to report the declared dimensions, tiny on disk.
     */
    private fun pngHeaderBytes(width: Int, height: Int): ByteArray {
        val ihdr = ByteBuffer.allocate(13)
            .putInt(width)
            .putInt(height)
            .put(8.toByte()) // bit depth
            .put(2.toByte()) // color type: truecolor
            .put(0.toByte()) // compression
            .put(0.toByte()) // filter
            .put(0.toByte()) // interlace
            .array()
        val crc = CRC32().apply {
            update("IHDR".toByteArray(Charsets.US_ASCII))
            update(ihdr)
        }
        return ByteBuffer.allocate(33)
            .put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            .putInt(13)
            .put("IHDR".toByteArray(Charsets.US_ASCII))
            .put(ihdr)
            .putInt(crc.value.toInt())
            .array()
    }

    /**
     * PDF whose single page draws an image XObject DECLARING huge dimensions (the Flate
     * payload is garbage: the pre-scan must reject on the declared header, before any
     * decode is attempted).
     */
    private fun writePdfWithHugeEmbeddedImage(name: String): Path {
        val file = tempDir.resolve(name)
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val deflated = ByteArrayOutputStream().also { buffer ->
                DeflaterOutputStream(buffer).use { it.write(ByteArray(64)) }
            }.toByteArray()
            val stream = PDStream(document, ByteArrayInputStream(deflated), COSName.FLATE_DECODE)
            val dictionary = stream.cosObject
            dictionary.setItem(COSName.TYPE, COSName.XOBJECT)
            dictionary.setItem(COSName.SUBTYPE, COSName.IMAGE)
            dictionary.setInt(COSName.WIDTH, 30_000)
            dictionary.setInt(COSName.HEIGHT, 30_000)
            dictionary.setInt(COSName.BITS_PER_COMPONENT, 8)
            dictionary.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
            val image = PDImageXObject(stream, null)
            PDPageContentStream(document, page).use { it.drawImage(image, 50f, 50f, 100f, 100f) }
            document.save(file.toFile())
        }
        return file
    }

    private fun writePptxWithPicture(name: String, pictureBytes: ByteArray): Path {
        val file = tempDir.resolve(name)
        XMLSlideShow().use { presentation ->
            val picture = presentation.addPicture(pictureBytes, PictureData.PictureType.PNG)
            presentation.createSlide().createPicture(picture)
            Files.newOutputStream(file).use { presentation.write(it) }
        }
        return file
    }

    /**
     * Writes a deck whose slide holds a table referencing a table style by id while the
     * package ships no `ppt/tableStyles.xml` — the (PowerPoint-tolerated) shape produced
     * by some third-party generators. POI's empty template always includes the part, so
     * it is stripped from the zip after writing, along with its relationship and
     * content-type override.
     */
    private fun writePptxWithDanglingTableStyle(name: String): Path {
        val file = tempDir.resolve(name)
        XMLSlideShow().use { presentation ->
            val table = presentation.createSlide().createTable(2, 2)
            table.setAnchor(Rectangle(50, 50, 400, 200))
            table.setColumnWidth(0, 150.0)
            table.setColumnWidth(1, 150.0)
            (0..1).forEach { row -> (0..1).forEach { col -> table.getCell(row, col).text = "R${row}C$col" } }
            val tblPr = table.ctTable.let { if (it.isSetTblPr) it.tblPr else it.addNewTblPr() }
            tblPr.tableStyleId = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"
            Files.newOutputStream(file).use { presentation.write(it) }
        }
        FileSystems.newFileSystem(file).use { zip ->
            Files.delete(zip.getPath("ppt", "tableStyles.xml"))
            stripZipEntryTag(zip.getPath("ppt", "_rels", "presentation.xml.rels"), Regex("<Relationship[^>]*tableStyles\\.xml[^>]*/>"))
            stripZipEntryTag(zip.getPath("[Content_Types].xml"), Regex("<Override[^>]*tableStyles[^>]*/>"))
        }
        return file
    }

    private fun stripZipEntryTag(entry: Path, tag: Regex) {
        Files.writeString(entry, Files.readString(entry).replace(tag, ""))
    }

    private fun writeProtectedPptx(name: String, password: String): Path {
        val clearBytes = ByteArrayOutputStream().use { buffer ->
            XMLSlideShow().use { presentation ->
                presentation.createSlide()
                presentation.write(buffer)
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

    init {
        beforeEach {
            tempDir = Files.createTempDirectory("read-as-image-test")
        }

        afterEach {
            tempDir.toFile().deleteRecursively()
        }

        "small PNG passes through untouched with original mime type" {
            val file = writePng("small.png", 100, 80)
            val originalBytes = Files.readAllBytes(file)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("small.png"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 1
            val image = result.images.single()
            image.mimeType shouldBe "image/png"
            image.width shouldBe 100
            image.height shouldBe 80
            Base64.getDecoder().decode(image.content) shouldBe originalBytes
            result.output shouldContain "small.png"
        }

        "large PNG is resized to fit max dimension and re-encoded as JPEG" {
            writePng("large.png", 2200, 1600)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("large.png"), ctx)

            result.success shouldBe true
            val image = result.images.single()
            image.mimeType shouldBe "image/jpeg"
            image.width shouldBe 1024
            image.height shouldBe 745

            val decoded = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.content)))
            decoded.width shouldBe 1024
            decoded.height shouldBe 745
        }

        "large PNG with alpha is flattened and encoded as JPEG" {
            writePng("alpha.png", 1500, 1200, BufferedImage.TYPE_INT_ARGB)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("alpha.png"), ctx)

            result.success shouldBe true
            result.images.single().mimeType shouldBe "image/jpeg"
        }

        "small BMP is re-encoded as JPEG, never passed through as image/bmp" {
            // Vision providers reject the image/bmp media type, so bmp must not use
            // the pass-through path even when small.
            val file = tempDir.resolve("scan.bmp")
            ImageIO.write(BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "bmp", file.toFile())

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("scan.bmp"), ctx)

            result.success shouldBe true
            result.images.single().mimeType shouldBe "image/jpeg"
        }

        "text file is rejected with UNSUPPORTED_FORMAT pointing to readFile" {
            tempDir.resolve("notes.txt").writeText("plain text")

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("notes.txt"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain "readFile"
        }

        "webp file is rejected with UNSUPPORTED_FORMAT" {
            tempDir.resolve("img.webp").writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("img.webp"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "UNSUPPORTED_FORMAT"
            result.output shouldContain "Supported: png, jpg, jpeg, gif, bmp, pdf"
        }

        "missing file returns an error" {
            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("absent.png"), ctx)

            result.success shouldBe false
            result.output shouldContain "Path does not exist"
        }

        "corrupt png content is rejected as unreadable image" {
            tempDir.resolve("corrupt.png").writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("corrupt.png"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "readable image"
        }

        "image declaring more pixels than the decode cap is rejected before decoding" {
            // 30000x30000 = 900M pixels, way over MAX_SOURCE_PIXELS; the file is a
            // 33-byte header, so any attempt to actually decode would fail differently.
            tempDir.resolve("huge.png").writeBytes(pngHeaderBytes(30_000, 30_000))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("huge.png"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "too large to decode"
        }

        "image content wins over the file extension for the pass-through mime type" {
            // A JPEG named .png must be labeled image/jpeg (the content), not image/png
            // (the extension): providers reject a mismatched declared media type.
            val file = tempDir.resolve("fake.png")
            ImageIO.write(BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "jpg", file.toFile())
            val originalBytes = Files.readAllBytes(file)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("fake.png"), ctx)

            result.success shouldBe true
            val image = result.images.single()
            image.mimeType shouldBe "image/jpeg"
            Base64.getDecoder().decode(image.content) shouldBe originalBytes
        }

        "file exceeding size cap is rejected before decoding" {
            val file = writePng("big.png", 50, 50)
            val tool = ReadAsImageTool(tempDir, readMaxSizeBytes = Files.size(file) - 1)

            val result = tool.execute(ReadAsImageTool.Input("big.png"), ctx)

            result.success shouldBe false
            result.output shouldContain "exceeds maximum size"
        }

        "sensitive file patterns are enforced" {
            tempDir.resolve("secret.pem").writeText("---KEY---")

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("secret.pem"), ctx)

            result.success shouldBe false
            result.output shouldContain "Access denied"
        }

        "path traversal is rejected" {
            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("../outside.png"), ctx)

            result.success shouldBe false
        }

        "3-page PDF renders one JPEG per page" {
            writePdf("cv.pdf", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("cv.pdf"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 3
            result.output shouldContain "page(s) 1-3 of 3"
            result.images.forEach { image ->
                image.mimeType shouldBe "image/jpeg"
                image.width!! shouldBeLessThanOrEqual 1024
                image.height!! shouldBeLessThanOrEqual 1024
            }
        }

        "pages parameter selects a single PDF page" {
            writePdf("cv.pdf", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("cv.pdf", pages = listOf(2)), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 1
            result.output shouldContain "page(s) 2 of 3"
        }

        "pages selection with duplicates and gaps is deduplicated, sorted and summarized as a list" {
            writePdf("cv.pdf", 5)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("cv.pdf", pages = listOf(3, 1, 3)), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 2
            result.output shouldContain "page(s) 1,3 of 5"
        }

        "page out of range is rejected with the actual page count" {
            writePdf("cv.pdf", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("cv.pdf", pages = listOf(9)), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "3 page(s)"
        }

        "PDF over the page cap renders the first pages and hints at continuation" {
            writePdf("long.pdf", 12)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("long.pdf"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 10
            result.output shouldContain "page(s) 1-10 of 12"
            result.output shouldContain "12 pages total"
        }

        "explicit selection above the page cap is rejected" {
            writePdf("long.pdf", 12)
            val pages = (1..11).toList()

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("long.pdf", pages = pages), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "At most 10 pages"
        }

        "empty pages selection is rejected" {
            writePdf("cv.pdf", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("cv.pdf", pages = emptyList()), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
        }

        "zero-page PDF is rejected as invalid input" {
            writePdf("empty.pdf", 0)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("empty.pdf"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "no page"
        }

        "PDF with a huge MediaBox renders with a capped resolution instead of exploding memory" {
            // A spec-max 14400x14400pt page at the nominal 150 DPI would allocate a
            // ~3.4GB buffer; the per-page DPI cap bounds the render to RENDER_TARGET_PX.
            val file = tempDir.resolve("poster.pdf")
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle(14400f, 14400f)))
                document.save(file.toFile())
            }

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("poster.pdf"), ctx)

            result.success shouldBe true
            val image = result.images.single()
            image.width!! shouldBeLessThanOrEqual 1024
            image.height!! shouldBeLessThanOrEqual 1024
        }

        "password-protected PDF is rejected" {
            writePdf("locked.pdf", 1, StandardProtectionPolicy("owner", "user", AccessPermission()))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("locked.pdf"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "password-protected"
        }

        "PDF embedding an image larger than the decode cap is rejected before rendering" {
            // PDFBox decodes embedded image XObjects at full declared resolution while
            // drawing: the pre-scan must reject on the declared dimensions, before the
            // (garbage) payload is ever decoded.
            writePdfWithHugeEmbeddedImage("bomb.pdf")

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("bomb.pdf"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "Embedded image is too large"
        }

        "PDF with a small embedded image still renders" {
            val file = tempDir.resolve("photo.pdf")
            PDDocument().use { document ->
                val page = PDPage()
                document.addPage(page)
                val image = LosslessFactory.createFromImage(document, BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB))
                PDPageContentStream(document, page).use { it.drawImage(image, 50f, 50f, 120f, 80f) }
                document.save(file.toFile())
            }

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("photo.pdf"), ctx)

            withClue(result.output) {
                result.success shouldBe true
            }
            result.images shouldHaveSize 1
        }

        "pages parameter on an image file is rejected" {
            writePng("photo.png", 100, 100)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("photo.png", pages = listOf(1)), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "PDF"
        }

        "3-slide PPTX renders one JPEG per slide" {
            writePptx("pitch.pptx", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("pitch.pptx"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 3
            result.output shouldContain "slide(s) 1-3 of 3"
            result.images.forEach { image ->
                image.mimeType shouldBe "image/jpeg"
                image.width!! shouldBeLessThanOrEqual 1024
                image.height!! shouldBeLessThanOrEqual 1024
            }
        }

        "pages parameter selects a single PPTX slide" {
            writePptx("pitch.pptx", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("pitch.pptx", pages = listOf(2)), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 1
            result.output shouldContain "slide(s) 2 of 3"
        }

        "slide out of range is rejected with the actual slide count" {
            writePptx("pitch.pptx", 3)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("pitch.pptx", pages = listOf(9)), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "3 slide(s)"
        }

        "PPTX over the page cap renders the first slides and hints at continuation" {
            writePptx("long.pptx", 12)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("long.pptx"), ctx)

            result.success shouldBe true
            result.images shouldHaveSize 10
            result.output shouldContain "slide(s) 1-10 of 12"
            result.output shouldContain "12 slides total"
        }

        "PPTX table referencing a style without a tableStyles part renders with default formatting" {
            // Some generators emit a tableStyleId while omitting ppt/tableStyles.xml;
            // PowerPoint falls back to default formatting, POI 5.5.1 still NPEs on it
            // (XSLFTable.getTableStyle dereferences the absent part).
            writePptxWithDanglingTableStyle("report.pptx")

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("report.pptx"), ctx)

            withClue(result.output) {
                result.success shouldBe true
            }
            result.images shouldHaveSize 1
        }

        "password-protected PPTX is rejected" {
            writeProtectedPptx("locked.pptx", "secret")

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("locked.pptx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "password-protected"
        }

        "corrupt bytes named .pptx are rejected as invalid" {
            tempDir.resolve("corrupt.pptx").writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("corrupt.pptx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
        }

        "PPTX embedding a picture larger than the decode cap is rejected before rendering" {
            // POI decodes embedded pictures at full source resolution while drawing: the
            // header pre-scan must reject on the declared dimensions before any decode.
            writePptxWithPicture("bomb.pptx", pngHeaderBytes(30_000, 30_000))

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("bomb.pptx"), ctx)

            result.success shouldBe false
            result.errorType shouldBe "INVALID_INPUT"
            result.output shouldContain "Embedded image is too large"
        }

        "PPTX with a small embedded picture still renders" {
            val pngBytes = ByteArrayOutputStream().also { buffer ->
                ImageIO.write(BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB), "png", buffer)
            }.toByteArray()
            writePptxWithPicture("photo.pptx", pngBytes)

            val result = ReadAsImageTool(tempDir).execute(ReadAsImageTool.Input("photo.pptx"), ctx)

            withClue(result.output) {
                result.success shouldBe true
            }
            result.images shouldHaveSize 1
        }
    }
}
