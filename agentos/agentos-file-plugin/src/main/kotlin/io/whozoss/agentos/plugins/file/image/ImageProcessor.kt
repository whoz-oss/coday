package io.whozoss.agentos.plugins.file.image

import io.whozoss.agentos.sdk.caseEvent.MessageContent
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.io.path.inputStream

/**
 * Pure image processing helpers for the readAsImage tool.
 *
 * Everything is sized for LLM vision input: images are bounded to
 * [MAX_DIMENSION] on their longest edge and re-encoded as JPEG at
 * [JPEG_QUALITY] (alpha flattened onto white), matching the common
 * denominator of the vision-capable providers.
 */
object ImageProcessor {

    /** Maximum width/height sent to the LLM. Hard-coded for now, configurable later. */
    const val MAX_DIMENSION = 1024

    /** JPEG re-encoding quality. */
    const val JPEG_QUALITY = 0.80f

    /** Decode-bomb guard: refuse to decode sources above this pixel count. */
    const val MAX_SOURCE_PIXELS = 50_000_000L

    /** Original bytes below this size that already fit [MAX_DIMENSION] are passed through untouched. */
    const val PASS_THROUGH_MAX_BYTES = 1L * 1024 * 1024

    /**
     * Reads the dimensions (width x height) of an image file from its header,
     * without decoding the pixel data. Returns null when no installed reader
     * recognizes the format (e.g. corrupt or misnamed file).
     */
    fun readDimensions(file: Path): Pair<Int, Int>? =
        file.inputStream().use { input ->
            ImageIO.createImageInputStream(input).use { imageInput ->
                val readers = ImageIO.getImageReaders(imageInput)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = imageInput
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        }

    /**
     * True when the original file bytes can be sent as-is: the image already fits
     * [MAX_DIMENSION] and the file is small enough that re-encoding would not
     * meaningfully reduce the payload (also preserves GIF animation and PNG sharpness).
     */
    fun passThroughEligible(width: Int, height: Int, fileSizeBytes: Long): Boolean =
        width <= MAX_DIMENSION && height <= MAX_DIMENSION && fileSizeBytes <= PASS_THROUGH_MAX_BYTES

    /**
     * Target dimensions scaled to fit [maxDimension] on the longest edge,
     * preserving aspect ratio, never enlarging.
     */
    fun scaleDimensions(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Pair<Int, Int> {
        if (width <= maxDimension && height <= maxDimension) return width to height
        val scale = minOf(maxDimension.toDouble() / width, maxDimension.toDouble() / height)
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /**
     * Renders [source] into an RGB image of [targetWidth] x [targetHeight]:
     * bilinear scaling, alpha flattened onto a white background (JPEG has no alpha).
     */
    fun renderRgb(source: BufferedImage, targetWidth: Int, targetHeight: Int): BufferedImage {
        val target = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, targetWidth, targetHeight)
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /** Encodes [image] as JPEG at [quality]. */
    fun encodeJpeg(image: BufferedImage, quality: Float = JPEG_QUALITY): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val output = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(output).use { imageOutput ->
            writer.output = imageOutput
            try {
                writer.write(null, IIOImage(image, null, null), params)
            } finally {
                writer.dispose()
            }
        }
        return output.toByteArray()
    }

    /**
     * Scales [source] to fit [MAX_DIMENSION] (never enlarging), flattens alpha and
     * encodes JPEG at [JPEG_QUALITY], as a ready-to-send [MessageContent.Image].
     */
    fun toJpegContent(source: BufferedImage): MessageContent.Image {
        val (targetWidth, targetHeight) = scaleDimensions(source.width, source.height)
        val bytes = encodeJpeg(renderRgb(source, targetWidth, targetHeight))
        return MessageContent.Image(
            content = Base64.getEncoder().encodeToString(bytes),
            mimeType = "image/jpeg",
            width = targetWidth,
            height = targetHeight,
        )
    }
}
