package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.plugins.file.image.ImageProcessor
import io.whozoss.agentos.sdk.caseEvent.MessageContent
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
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFPictureData
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.ByteArrayInputStream
import java.nio.file.FileSystemException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Read a Word document (.docx) as Markdown text so the model can work on the exact content.
 *
 * A Word document is text with structure, not a fixed-layout visual (POI has no layout engine
 * to rasterize it, unlike a PDF), so it is projected to Markdown rather than rendered to
 * images: headings become `#`..`######`, list paragraphs become `-` bullets, tables become
 * Markdown tables (`|` escaped, newlines flattened). Body elements are walked in document
 * order via [XWPFDocument.getBodyElements]. This gives exact, quotable, searchable text at
 * minimal token cost.
 *
 * Embedded pictures (diagrams, screenshots, scans) are where vision is useful, so they ARE
 * attached: [XWPFDocument.getAllPictures] yields the embedded bitmaps directly (no rendering),
 * each is checked against [ImageProcessor.MAX_SOURCE_PIXELS] and re-encoded as JPEG, up to
 * [MAX_ATTACHED_IMAGES]. An oversized or unreadable picture is skipped (the text still reads),
 * not fatal. Image delivery to the model is an AgentAdvanced feature (see [ToolExecutionResult.images]).
 *
 * Budgets per call: [MAX_OUTPUT_CHARS] characters of Markdown; [startElement] (1-based body
 * element index) pages through a long document, with a `[truncated]` notice telling the model
 * how to continue. Tables are cut at [MAX_TABLE_COLUMNS] columns and [MAX_CELL_CHARS] per cell.
 *
 * For a document whose visual LAYOUT is itself the content (a form, a designed one-pager),
 * export it to PDF (Word's own layout engine, highest fidelity) and use readAsImage instead.
 *
 * .docx only. Legacy binary .doc, .odt and .rtf are rejected: convert to .docx first. The
 * timeout cannot interrupt a POI parse mid-flight (raising `readMaxSizeMb` raises parse cost).
 */
class ReadDocumentTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<ReadDocumentTool.Input> {
    companion object : KLogging() {
        /** Parsing a whole XWPF DOM is heavy like PDF/PPTX rendering: distinct, larger timeout. */
        private const val IO_TIMEOUT = 60L
        private const val DEFAULT_READ_MAX_SIZE = 10L * 1024 * 1024 // 10 MB

        /** Markdown character budget per call: the real guard for very long documents. */
        const val MAX_OUTPUT_CHARS = 100_000

        /** Table columns beyond this are not emitted. */
        const val MAX_TABLE_COLUMNS = 64

        /** A single table cell longer than this is cut. */
        const val MAX_CELL_CHARS = 5000

        /** Maximum embedded pictures attached as vision images per call, newest layout order. */
        const val MAX_ATTACHED_IMAGES = 10

        private val HEADING_STYLE = Regex("""(?i)^(?:heading|titre)\s*([1-9])$""")
    }

    override val name: String =
        if (configName != null) "${configName}__readDocument" else "FILES__readDocument"

    override val description: String =
        """
        Read a Word document (.docx) as Markdown text. Headings become #.., list paragraphs
        become - bullets, tables become Markdown tables; the body is read in document order.
        Returns exact, quotable text (far cheaper than images). Embedded pictures in the
        document are also attached as images so you can see diagrams and scans.
        At most ~$MAX_OUTPUT_CHARS characters per call: for a longer document pass "startElement"
        (1-based body element index) to page through, as instructed by the [truncated] notice.
        .docx only. Legacy .doc, .odt and .rtf are not supported: convert to .docx first.
        If a document's visual LAYOUT is the content (a form, a designed page), export it to
        PDF and use readAsImage instead.
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
                    "description": "Relative file path (e.g. \"docs/report.docx\")"
                },
                "startElement": {
                    "type": "integer",
                    "minimum": 1,
                    "description": "1-based body element (paragraph or table) to start from, to page through a long document. Default 1."
                }
            },
            "required": ["filePath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val filePath: String = "",
        val startElement: Int? = null,
    )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val (text, images) = runIOWithTimeout(IO_TIMEOUT) { read(params) }
            ToolExecutionResult.successWithImages(text, images)
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
        } catch (e: FileSystemException) {
            // NoSuchFileException/AccessDeniedException messages are the bare absolute
            // server path, which must not reach the model.
            logger.warn(e) { "readDocument failed to read '${params.filePath}'" }
            ToolExecutionResult.error(
                "File could not be read: ${params.filePath}",
                errorType = "READ_ERROR",
                errorMessage = e.javaClass.simpleName,
            )
        } catch (e: Exception) {
            logger.warn(e) { "readDocument failed to read '${params.filePath}'" }
            ToolExecutionResult.error(
                "Error reading document: ${e.message}",
                errorType = "READ_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun read(params: Input): Pair<String, List<MessageContent.Image>> {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(params.filePath, createIntent = false)

        // Size guard before anything is parsed. Also the first line of defense against
        // decompression bombs; POI's built-in ZipSecureFile checks (inflate ratio cap,
        // max entry size) cover the docx zip itself.
        val size = resolved.fileSize()
        if (size > readMaxSizeBytes) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${readMaxSizeBytes / 1024 / 1024} MB: ${params.filePath}",
            )
        }

        when (val extension = resolved.name.substringAfterLast('.', "").lowercase()) {
            "docx" -> Unit
            "doc" -> throw UnsupportedFormatException("Legacy binary '.doc' is not supported: convert to .docx first.")
            else -> throw UnsupportedFormatException(
                "Unsupported file type '.$extension'. Supported: .docx only. " +
                    "Convert .doc/.odt/.rtf to .docx; for plain text use readFile.",
            )
        }

        if (params.startElement != null && params.startElement < 1) {
            throw IllegalArgumentException("\"startElement\" must be at least 1 (got ${params.startElement}).")
        }

        try {
            // Opened from the file (lazy random-access zip reading), not an InputStream (which
            // buffers the whole decompressed package up front). OPCPackage/XWPFDocument are
            // constructed directly on purpose: WorkbookFactory/SlideShowFactory-style provider
            // lookup resolves through the thread context classloader, which under PF4J is the
            // application classloader and does not see the bundled POI classes.
            val opcPackage = OPCPackage.open(resolved.toFile(), PackageAccess.READ)
            val document = try {
                XWPFDocument(opcPackage)
            } catch (e: Exception) {
                opcPackage.revert()
                throw e
            }
            document.use {
                return renderDocument(it, params, resolved.name)
            }
        } catch (e: EncryptedDocumentException) {
            throw IllegalArgumentException("Word file is password-protected: ${resolved.name}")
        } catch (e: NotOfficeXmlFileException) {
            // Also covers encrypted docx (stored as an OLE2 container) and legacy .doc
            // files renamed to .docx.
            throw IllegalArgumentException(
                "Not a valid .docx file (corrupt, password-protected, or legacy .doc; convert to .docx): ${resolved.name}",
            )
        } catch (e: POIXMLException) {
            // A valid OOXML package that is not a Word document (e.g. an .xlsx renamed .docx).
            throw IllegalArgumentException("Not a valid .docx file (the package is not a Word document): ${resolved.name}")
        } catch (e: InvalidFormatException) {
            // A valid zip that is not an OOXML package (no content-types part).
            throw IllegalArgumentException("Not a valid .docx file (the package is not a Word document): ${resolved.name}")
        } catch (e: InvalidOperationException) {
            // POI wraps open failures (e.g. permission denied) with the absolute server
            // path in the message; rethrow with the relative path only.
            logger.warn(e) { "readDocument could not open '${params.filePath}'" }
            throw IllegalArgumentException("File could not be opened: ${params.filePath}")
        }
    }

    private fun renderDocument(
        document: XWPFDocument,
        params: Input,
        fileName: String,
    ): Pair<String, List<MessageContent.Image>> {
        val elements = document.bodyElements
        val startElement = params.startElement ?: 1
        if (startElement > 1 && startElement > elements.size) {
            throw IllegalArgumentException(
                "startElement $startElement out of range: $fileName has ${elements.size} body element(s).",
            )
        }

        val out = StringBuilder()
        var nextElement: Int? = null
        for (index in (startElement - 1) until elements.size) {
            if (out.length >= MAX_OUTPUT_CHARS) {
                nextElement = index + 1 // 1-based
                break
            }
            when (val element = elements[index]) {
                is XWPFParagraph -> appendParagraph(out, element)
                is XWPFTable -> appendTable(out, element)
                else -> {}
            }
        }

        if (out.isBlank()) out.append("(no textual content)")
        if (nextElement != null) {
            out.append("\n[truncated] ${elements.size} body elements total; call again with startElement=$nextElement to continue.")
        }

        val images = collectImages(document, fileName)
        if (images.isNotEmpty()) {
            out.append("\n\n[${images.size} embedded image(s) attached below.]")
        }
        return out.toString() to images
    }

    private fun appendParagraph(out: StringBuilder, paragraph: XWPFParagraph) {
        val text = paragraph.text.trim()
        if (text.isEmpty()) {
            out.append('\n')
            return
        }
        val heading = HEADING_STYLE.find(paragraph.styleID?.trim().orEmpty())?.groupValues?.get(1)?.toInt()
        when {
            heading != null -> out.append("#".repeat(heading)).append(' ').append(text).append('\n')
            paragraph.numID != null -> out.append("- ").append(text).append('\n')
            else -> out.append(text).append("\n\n")
        }
    }

    private fun appendTable(out: StringBuilder, table: XWPFTable) {
        val rows = table.rows
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.tableCells.size }.coerceIn(1, MAX_TABLE_COLUMNS)

        rows.forEachIndexed { rowIndex, row ->
            val cells = row.tableCells
            out.append('|')
            for (column in 0 until columns) {
                out.append(' ').append(escapeCell(cells.getOrNull(column)?.text.orEmpty())).append(" |")
            }
            out.append('\n')
            if (rowIndex == 0) {
                out.append('|')
                repeat(columns) { out.append(" --- |") }
                out.append('\n')
            }
        }
        out.append('\n')
    }

    private fun escapeCell(value: String): String =
        value
            .replace("|", "\\|")
            .replace(Regex("[\r\n]+"), "<br>")
            .let { if (it.length > MAX_CELL_CHARS) it.take(MAX_CELL_CHARS) + "…[cell truncated]" else it }

    /**
     * Embedded pictures attached as vision images. Each is checked against the decode-bomb cap
     * from its declared header before decoding, then re-encoded JPEG. Oversized or unreadable
     * pictures are skipped (the textual read still succeeds), never fatal.
     */
    private fun collectImages(document: XWPFDocument, fileName: String): List<MessageContent.Image> {
        val images = mutableListOf<MessageContent.Image>()
        for (picture in document.allPictures) {
            if (images.size >= MAX_ATTACHED_IMAGES) break
            toImage(picture, fileName)?.let { images.add(it) }
        }
        return images
    }

    private fun toImage(picture: XWPFPictureData, fileName: String): MessageContent.Image? {
        val bytes = runCatching { picture.data }.getOrNull() ?: return null
        val header = runCatching { ImageProcessor.readHeader(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (header.width.toLong() * header.height > ImageProcessor.MAX_SOURCE_PIXELS) {
            logger.warn {
                "readDocument skipped an oversized embedded image (${header.width}x${header.height}) in $fileName"
            }
            return null
        }
        val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return ImageProcessor.toJpegContent(decoded)
    }

    // Local to this tool on the readAsImage base branch; #1151 promotes an identical exception
    // to a shared UnsupportedFormatException.kt — deduplicate to it if #1151 lands first.
    private class UnsupportedFormatException(message: String) : IllegalArgumentException(message)
}
