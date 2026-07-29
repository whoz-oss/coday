package io.whozoss.agentos.plugins.file.tools

import io.whozoss.agentos.plugins.file.BoundaryPathResolver
import io.whozoss.agentos.plugins.file.SensitiveFilePatterns
import io.whozoss.agentos.plugins.file.image.ImageProcessor
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import mu.KLogging
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
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
import kotlin.time.Duration.Companion.seconds

/**
 * Render an image, PDF or PPTX file into [MessageContent.Image] attachments so a
 * vision-capable model can see it.
 *
 * Images (png/jpg/jpeg/gif/bmp) are bounded to [imageMaxDimension] and
 * re-encoded JPEG when needed; small originals pass through untouched. PDFs are
 * rendered one image per page, PPTX presentations one image per slide (at most
 * [MAX_PAGES_PER_CALL] per call, selectable via the `pages` parameter). The textual
 * [ToolExecutionResult.output] summarizes what was rendered; the images ride in
 * [ToolExecutionResult.images].
 *
 * PPTX fidelity (POI rendering): text, shapes, tables and bitmap pictures render
 * well; fonts absent from the host are substituted (e.g. Calibri to DejaVu),
 * SmartArt is not rendered and embedded charts come out blank.
 *
 * Decompression-bomb guards (PDFBox/POI decode embedded bitmaps at full source
 * resolution): standalone images are rejected above [imageMaxSourcePixels]
 * from their declared header before any decode. Inside a PDF every image is intercepted
 * at [PageDrawer.drawImage] ([BoundedPageDrawer]) and checked against the same cap before
 * PDFBox materializes it: that is the single decode point for XObjects, inline images
 * (BI/ID/EI), Type3 glyph images, forms at any nesting depth, patterns and annotations,
 * so no path is missed. Inside a PPTX every embedded picture's header is pre-checked.
 */
class ReadAsImageTool(
    private val projectRoot: Path,
    private val configName: String? = null,
    private val readMaxSizeBytes: Long = DEFAULT_READ_MAX_SIZE,
    private val denyPatterns: List<String> = SensitiveFilePatterns.DEFAULT_PATTERNS,
    private val ioTimeoutSeconds: Long = IO_TIMEOUT,
    private val imageMaxDimension: Int = ImageProcessor.MAX_DIMENSION,
    private val imageJpegQuality: Float = ImageProcessor.JPEG_QUALITY,
    private val imageMaxSourcePixels: Long = ImageProcessor.MAX_SOURCE_PIXELS,
    private val imagePassThroughMaxBytes: Long = ImageProcessor.PASS_THROUGH_MAX_BYTES,
) : StandardTool<ReadAsImageTool.Input> {
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
        For Word documents (.docx) use readDocument.
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
            val (summary, images) =
                withTimeout(ioTimeoutSeconds.seconds) {
                    // Rendering is blocking, CPU-bound PDFBox/POI work that ignores
                    // cooperative cancellation: run it as an abandonable job on the bounded
                    // [renderScope] pool so the timeout releases the caller immediately and a
                    // runaway render pins at most [MAX_CONCURRENT_RENDERS] threads.
                    renderScope.async { render(params) }.await()
                }
            ToolExecutionResult.successWithImages(summary, images)
        } catch (e: TimeoutCancellationException) {
            logger.warn {
                "readAsImage timed out after ${ioTimeoutSeconds}s on '${params.filePath}'; " +
                    "the abandoned render keeps its pool slot until it completes"
            }
            ToolExecutionResult.error(
                "Operation timed out after $ioTimeoutSeconds seconds",
                errorType = "TIMEOUT",
                errorMessage = e.message,
            )
        } catch (e: CancellationException) {
            // The tool coroutine was cancelled for an external reason (case aborted, stream
            // closed). Never swallow it into a READ_ERROR: rethrow so cancellation propagates.
            // Must sit after the TimeoutCancellationException catch (that one is our own timeout).
            throw e
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
            in IMAGE_EXTENSIONS -> {
                if (params.pages != null) {
                    throw IllegalArgumentException("\"pages\" applies only to PDF and PPTX files: ${params.filePath}")
                }
                renderImage(resolved)
            }
            else -> throw UnsupportedFormatException(
                "Unsupported file type '.$extension'. Supported: png, jpg, jpeg, gif, bmp, pdf, pptx. " +
                    "For text-based files use readFile; for Word (.docx) use readDocument.",
            )
        }
    }

    private fun renderImage(file: Path): Pair<String, List<MessageContent.Image>> {
        val header = ImageProcessor.readHeader(file)
            ?: throw IllegalArgumentException("File does not contain a readable image: ${file.name}")
        if (header.width.toLong() * header.height > imageMaxSourcePixels) {
            throw IllegalArgumentException(
                "Image is too large to decode (${header.width}x${header.height}," +
                    " max $imageMaxSourcePixels pixels): ${file.name}",
            )
        }

        // The pass-through mime is the one detected from the bytes, never the extension:
        // a JPEG named .png sent as image/png would be rejected by the provider.
        val contentMime = header.mimeType
        val image = if (
            contentMime != null &&
            contentMime in PASS_THROUGH_MIME_TYPES &&
            ImageProcessor.passThroughEligible(
                header.width,
                header.height,
                file.fileSize(),
                imageMaxDimension,
                imagePassThroughMaxBytes,
            )
        ) {
            MessageContent.Image(
                content = Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                mimeType = contentMime,
                width = header.width,
                height = header.height,
            )
        } else {
            val decoded = ImageIO.read(file.toFile())
                ?: throw IllegalArgumentException("File does not contain a readable image: ${file.name}")
            ImageProcessor.toJpegContent(decoded, imageMaxDimension, imageJpegQuality)
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

                // [BoundedRenderer] rejects any embedded image over the cap at the single
                // decode point (see [BoundedPageDrawer]); subsampling is belt-and-braces so
                // an in-cap-but-still-large image does not materialize at full resolution.
                val renderer = BoundedRenderer(document, file.name)
                renderer.isSubsamplingAllowed = true
                val images = selection.map { page ->
                    ImageProcessor.toJpegContent(
                        renderer.renderImageWithDPI(page - 1, pageRenderDpi(document, page), ImageType.RGB),
                        imageMaxDimension,
                        imageJpegQuality,
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

    /**
     * A [PDFRenderer] whose page drawer enforces the embedded-image cap. Overriding the
     * renderer's single factory hook is what lets [BoundedPageDrawer] see every image.
     */
    private inner class BoundedRenderer(document: PDDocument, private val fileName: String) : PDFRenderer(document) {
        override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer =
            BoundedPageDrawer(parameters, fileName)
    }

    /**
     * Rejects any image over [imageMaxSourcePixels] from its DECLARED dimensions
     * before PDFBox decodes it. [PageDrawer.drawImage] is the single decode entry point for
     * every image PDFBox draws — image XObjects, inline images (BI/ID/EI), Type3 glyph
     * images, and images reachable through forms/patterns/annotations at any nesting depth —
     * so this one check covers them all, where a resource-tree walk would miss inline and
     * content-stream images. Without it a sub-cap file declaring a huge image would allocate
     * a multi-GB raster and throw an uncatchable OutOfMemoryError in the shared service JVM.
     */
    private inner class BoundedPageDrawer(
        parameters: PageDrawerParameters,
        private val fileName: String,
    ) : PageDrawer(parameters) {
        override fun drawImage(pdImage: PDImage) {
            checkEmbeddedImageSize(pdImage.width, pdImage.height, fileName)
            if (pdImage is PDImageXObject) {
                // A small base image can carry a huge /SMask or /Mask that PDFBox decodes at
                // its own full resolution while compositing.
                pdImage.softMask?.let { checkEmbeddedImageSize(it.width, it.height, fileName) }
                pdImage.mask?.let { checkEmbeddedImageSize(it.width, it.height, fileName) }
            }
            super.drawImage(pdImage)
        }
    }

    /**
     * Decompression-bomb guard for pictures embedded in a PPTX: sniffs the header of every
     * picture part in the package (slides, layouts, masters and backgrounds share the same
     * part pool) and rejects the deck when a picture's declared dimensions exceed
     * [imageMaxSourcePixels]. POI decodes pictures at full source resolution
     * while drawing. Header-only sniff: no pixel data is decoded; unrecognized parts
     * (vector metafiles, corrupt data) are skipped, as POI itself skips them.
     */
    private fun checkPptxEmbeddedImages(presentation: XMLSlideShow, fileName: String) {
        presentation.pictureData.forEach { picture ->
            val header = runCatching {
                picture.inputStream.use { ImageProcessor.readHeader(it) }
            }.getOrNull() ?: return@forEach
            checkEmbeddedImageSize(header.width, header.height, fileName)
        }
    }

    private fun checkEmbeddedImageSize(width: Int, height: Int, fileName: String) {
        if (width.toLong() * height > imageMaxSourcePixels) {
            throw IllegalArgumentException(
                "Embedded image is too large to decode (${width}x$height," +
                    " max $imageMaxSourcePixels pixels): $fileName",
            )
        }
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
                    checkPptxEmbeddedImages(presentation, file.name)

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
        return ImageProcessor.toJpegContent(target, imageMaxDimension, imageJpegQuality)
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

    private class UnsupportedFormatException(message: String) : IllegalArgumentException(message)

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

        /** Extensions routed to the standalone-image path. */
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp")

        /**
         * Original bytes may be sent as-is only for these mime types, DETECTED FROM THE
         * CONTENT by [ImageProcessor.readHeader] (never from the file extension, which the
         * provider would reject on a mismatched payload): the vision APIs accept
         * png/jpeg/gif but reject image/bmp, so bmp is always re-encoded JPEG.
         */
        private val PASS_THROUGH_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif")

        /** Maximum concurrent renders across all instances of this tool. */
        private const val MAX_CONCURRENT_RENDERS = 4

        /**
         * Bounded, abandonable pool for the blocking renders. Coroutine cancellation is
         * cooperative and PDFBox/POI rendering never suspends, so a timed-out render
         * cannot be stopped: it is left to finish here (result discarded) instead of
         * pinning the caller or an unbounded share of the Dispatchers.IO workers.
         */
        private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_RENDERS))
    }
}
