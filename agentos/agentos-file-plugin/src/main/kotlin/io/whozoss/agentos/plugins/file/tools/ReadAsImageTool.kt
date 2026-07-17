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
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTable
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Render an image, PDF or PPTX file into [MessageContent.Image] attachments so a
 * vision-capable model can see it.
 *
 * Images (png/jpg/jpeg/gif/bmp) are bounded to [ImageProcessor.MAX_DIMENSION] and
 * re-encoded JPEG when needed; small originals pass through untouched. PDFs are
 * rendered one image per page, PPTX presentations one image per slide (at most
 * [MAX_PAGES_PER_CALL] per call, selectable via the `pages` parameter). The textual
 * [ToolExecutionResult.output] summarizes what was rendered; the images ride in
 * [ToolExecutionResult.images].
 *
 * PPTX fidelity (POI rendering): text, shapes, tables and bitmap pictures render
 * well; fonts absent from the host are substituted (e.g. Calibri to DejaVu),
 * SmartArt is not rendered and embedded charts come out blank.
 */
class ReadAsImageTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
) : StandardTool<ReadAsImageTool.Input> {
    companion object : KLogging() {
        /** PDF/PPTX rendering is significantly slower than a plain read: distinct, larger timeout. */
        private const val IO_TIMEOUT = 60L
        private const val DEFAULT_READ_MAX_SIZE = 10L * 1024 * 1024 // 10 MB

        /** Maximum PDF pages / PPTX slides rendered in a single call. */
        const val MAX_PAGES_PER_CALL = 10

        /** Nominal PDF rendering resolution before downscaling: keeps text legible at 1024px. */
        private const val PDF_RENDER_DPI = 150f

        /** PDF user-space unit: 72 points per inch. */
        private const val POINTS_PER_INCH = 72f

        /**
         * PDF pages and PPTX slides are rendered with their longest edge capped at this
         * size (2x supersampling), then downscaled to [ImageProcessor.MAX_DIMENSION].
         * On PDFs this also bounds the render allocation: a page declaring a huge
         * MediaBox at [PDF_RENDER_DPI] would otherwise allocate a multi-GB buffer.
         */
        private const val RENDER_TARGET_PX = 2048

        private val IMAGE_MIME_BY_EXTENSION = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "bmp" to "image/bmp",
        )

        /**
         * Original bytes may be sent as-is only for these mime types: the vision APIs
         * accept png/jpeg/gif but reject image/bmp, so bmp is always re-encoded JPEG.
         */
        private val PASS_THROUGH_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif")
    }

    override val name: String = if (configName != null) "${configName}__readAsImage" else "FILES__readAsImage"

    override val description: String =
        """
        Render an image, PDF or PowerPoint file VISUALLY, as image(s) attached to the
        conversation, so you can SEE its content: photos, scans, screenshots, diagrams,
        charts, PDF documents (one image per page) and PPTX presentations (one image per
        slide). Requires a vision-capable model.
        Supported: .png, .jpg, .jpeg, .gif (first frame when resized), .bmp, .pdf, .pptx.
        Do NOT use for text-based files (source code, markdown, CSV, JSON, logs...):
        use readFile instead, which returns the exact text and is far cheaper.
        For Excel spreadsheets (.xlsx) use readSpreadsheet.
        PDF/PPTX: at most $MAX_PAGES_PER_CALL pages/slides are rendered per call; pass
        "pages" (1-based page or slide numbers) to view a specific subset of a longer document.
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
                    "description": "Relative file path (e.g. \"cv.pdf\", \"decks/pitch.pptx\", \"diagrams/archi.png\")"
                },
                "pages": {
                    "type": "array",
                    "items": { "type": "integer", "minimum": 1 },
                    "description": "1-based page numbers (PDF) or slide numbers (PPTX); max $MAX_PAGES_PER_CALL per call. Omit to render from the beginning up to the cap."
                }
            },
            "required": ["filePath"],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val filePath: String = "",
        val pages: List<Int>? = null,
    )

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val params = input ?: Input()

        return try {
            val (summary, images) = runIOWithTimeout(IO_TIMEOUT) { render(params) }
            ToolExecutionResult.successWithImages(summary, images)
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
            logger.warn(e) { "readAsImage failed to render '${params.filePath}'" }
            ToolExecutionResult.error(
                "Error rendering file: ${e.message}",
                errorType = "READ_ERROR",
                errorMessage = e.message,
            )
        }
    }

    private fun render(params: Input): Pair<String, List<MessageContent.Image>> {
        val resolver = BoundaryPathResolver(projectRoot, denyPatterns)
        val resolved = resolver.resolve(params.filePath, createIntent = false)

        // Size guard before anything is parsed. Also the first line of defense against
        // decompression bombs; POI's built-in ZipSecureFile checks (inflate ratio cap,
        // max entry size) cover the pptx zip itself.
        val size = resolved.fileSize()
        if (size > readMaxSizeBytes) {
            throw IllegalArgumentException(
                "File exceeds maximum size of ${readMaxSizeBytes / 1024 / 1024} MB: ${params.filePath}",
            )
        }

        return when (val extension = resolved.name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> renderPdf(resolved, params.pages)
            "pptx" -> renderPptx(resolved, params.pages)
            in IMAGE_MIME_BY_EXTENSION.keys -> {
                if (params.pages != null) {
                    throw IllegalArgumentException("\"pages\" applies only to PDF and PPTX files: ${params.filePath}")
                }
                renderImage(resolved, IMAGE_MIME_BY_EXTENSION.getValue(extension))
            }
            else -> throw UnsupportedFormatException(
                "Unsupported file type '.$extension'. Supported: png, jpg, jpeg, gif, bmp, pdf, pptx. " +
                    "For text-based files use readFile; for .xlsx use readSpreadsheet.",
            )
        }
    }

    private fun renderImage(file: Path, mimeType: String): Pair<String, List<MessageContent.Image>> {
        val (width, height) = ImageProcessor.readDimensions(file)
            ?: throw IllegalArgumentException("File does not contain a readable image: ${file.name}")
        if (width.toLong() * height > ImageProcessor.MAX_SOURCE_PIXELS) {
            throw IllegalArgumentException(
                "Image is too large to decode (${width}x$height, max ${ImageProcessor.MAX_SOURCE_PIXELS} pixels): ${file.name}",
            )
        }

        val image = if (mimeType in PASS_THROUGH_MIME_TYPES && ImageProcessor.passThroughEligible(width, height, file.fileSize())) {
            MessageContent.Image(
                content = Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                mimeType = mimeType,
                width = width,
                height = height,
            )
        } else {
            val decoded = ImageIO.read(file.toFile())
                ?: throw IllegalArgumentException("File does not contain a readable image: ${file.name}")
            ImageProcessor.toJpegContent(decoded)
        }

        return "Loaded image ${file.name} (${image.width}x${image.height}, ${image.mimeType}, attached)." to listOf(image)
    }

    private fun renderPdf(file: Path, pages: List<Int>?): Pair<String, List<MessageContent.Image>> {
        try {
            Loader.loadPDF(file.toFile()).use { document ->
                val pageCount = document.numberOfPages
                if (pageCount == 0) {
                    throw IllegalArgumentException("PDF has no page: ${file.name}")
                }
                val selection = selectPages(pages, pageCount, file.name)

                val renderer = PDFRenderer(document)
                val images = selection.map { page ->
                    ImageProcessor.toJpegContent(
                        renderer.renderImageWithDPI(page - 1, pageRenderDpi(document, page), ImageType.RGB),
                    )
                }

                return buildRenderSummary("PDF", file.name, selection, pageCount, "page", pages) to images
            }
        } catch (e: InvalidPasswordException) {
            throw IllegalArgumentException("PDF is password-protected: ${file.name}")
        }
    }

    /**
     * DPI for one page: nominal [PDF_RENDER_DPI], reduced when the page geometry would
     * push the rendered longest edge past [RENDER_TARGET_PX]. Bounds the render buffer
     * (a spec-max 14400pt MediaBox at 150 DPI would allocate a ~3.4GB image and throw
     * an uncatchable OutOfMemoryError in the service JVM).
     */
    private fun pageRenderDpi(document: PDDocument, page: Int): Float {
        val box = document.getPage(page - 1).mediaBox
        val longestEdgePt = maxOf(box.width, box.height)
        if (longestEdgePt <= 0f) return PDF_RENDER_DPI
        return minOf(PDF_RENDER_DPI, RENDER_TARGET_PX * POINTS_PER_INCH / longestEdgePt)
    }

    private fun renderPptx(file: Path, pages: List<Int>?): Pair<String, List<MessageContent.Image>> {
        try {
            // XMLSlideShow is constructed directly on purpose: SlideShowFactory resolves
            // providers through the thread context classloader, which under PF4J is the
            // application classloader and does not see the bundled POI classes.
            Files.newInputStream(file).use { input ->
                XMLSlideShow(input).use { presentation ->
                    val slideCount = presentation.slides.size
                    if (slideCount == 0) {
                        throw IllegalArgumentException("PPTX has no slide: ${file.name}")
                    }
                    sanitizeDanglingTableStyleRefs(presentation)
                    val selection = selectPages(pages, slideCount, file.name, unit = "slide")

                    val pageSize = presentation.pageSize
                    val scale = RENDER_TARGET_PX.toDouble() / maxOf(pageSize.width, pageSize.height)
                    val width = Math.round(pageSize.width * scale).toInt()
                    val height = Math.round(pageSize.height * scale).toInt()

                    val images = selection.map { slide -> renderSlide(presentation, slide, width, height, scale) }

                    return buildRenderSummary("PPTX", file.name, selection, slideCount, "slide", pages) to images
                }
            }
        } catch (e: EncryptedDocumentException) {
            throw IllegalArgumentException("PowerPoint file is password-protected: ${file.name}")
        } catch (e: NotOfficeXmlFileException) {
            // Also covers encrypted pptx (stored as an OLE2 container) and legacy .ppt
            // files renamed to .pptx.
            throw IllegalArgumentException(
                "Not a valid .pptx file (corrupt, password-protected, or legacy .ppt; convert to .pptx): ${file.name}",
            )
        }
    }

    /**
     * Some generators emit tables referencing a table style by id without shipping the
     * `ppt/tableStyles.xml` part. PowerPoint falls back to default formatting, but POI
     * dereferences the absent part and throws an NPE while drawing the table
     * (XSLFTable.getTableStyle, still unguarded in POI 5.5.1). When the part is missing,
     * the dangling references are dropped from the in-memory model only (the file is
     * never written back), so rendering matches the PowerPoint fallback.
     */
    private fun sanitizeDanglingTableStyleRefs(presentation: XMLSlideShow) {
        if (presentation.tableStyles != null) return
        presentation.slides.forEach { slide -> stripTableStyleIds(slide.shapes) }
    }

    private fun stripTableStyleIds(shapes: List<XSLFShape>) {
        shapes.forEach { shape ->
            when (shape) {
                is XSLFTable -> {
                    val ctTable = shape.ctTable
                    if (ctTable.isSetTblPr && ctTable.tblPr.isSetTableStyleId) {
                        ctTable.tblPr.unsetTableStyleId()
                    }
                }
                is XSLFGroupShape -> stripTableStyleIds(shape.shapes)
            }
        }
    }

    private fun renderSlide(
        presentation: XMLSlideShow,
        slideNumber: Int,
        width: Int,
        height: Int,
        scale: Double,
    ): MessageContent.Image {
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.scale(scale, scale)
            presentation.slides[slideNumber - 1].draw(graphics)
        } finally {
            graphics.dispose()
        }
        return ImageProcessor.toJpegContent(target)
    }

    private fun selectPages(
        pages: List<Int>?,
        pageCount: Int,
        fileName: String,
        unit: String = "page",
    ): List<Int> {
        if (pages == null) return (1..minOf(pageCount, MAX_PAGES_PER_CALL)).toList()

        val selection = pages.distinct().sorted()
        if (selection.isEmpty()) {
            throw IllegalArgumentException("\"pages\" must not be empty: $fileName")
        }
        if (selection.size > MAX_PAGES_PER_CALL) {
            throw IllegalArgumentException(
                "At most $MAX_PAGES_PER_CALL ${unit}s per call (got ${selection.size}): split into several calls.",
            )
        }
        val outOfRange = selection.filter { it !in 1..pageCount }
        if (outOfRange.isNotEmpty()) {
            throw IllegalArgumentException(
                "Page(s) $outOfRange out of range: $fileName has $pageCount ${unit}(s).",
            )
        }
        return selection
    }

    /**
     * Summary shown to the model. When the default selection was truncated by
     * [MAX_PAGES_PER_CALL], it also doubles as the pagination affordance.
     */
    private fun buildRenderSummary(
        kind: String,
        fileName: String,
        selection: List<Int>,
        total: Int,
        unit: String,
        requestedPages: List<Int>?,
    ): String =
        buildString {
            append(
                "Rendered $kind $fileName: ${unit}(s) ${describePages(selection)} of $total" +
                    " (one image per $unit, attached).",
            )
            if (requestedPages == null && total > MAX_PAGES_PER_CALL) {
                append(
                    " $total ${unit}s total: call again with pages=[${MAX_PAGES_PER_CALL + 1},...]" +
                        " to view the following ${unit}s.",
                )
            }
        }

    private fun describePages(selection: List<Int>): String {
        val contiguous = selection.size > 1 && selection.last() - selection.first() == selection.size - 1
        return when {
            selection.size == 1 -> "${selection.first()}"
            contiguous -> "${selection.first()}-${selection.last()}"
            else -> selection.joinToString(",")
        }
    }

}
