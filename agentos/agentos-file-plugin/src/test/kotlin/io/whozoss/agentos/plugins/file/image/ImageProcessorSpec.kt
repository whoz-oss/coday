package io.whozoss.agentos.plugins.file.image

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class ImageProcessorSpec : StringSpec() {
    init {
        "scaleDimensions never enlarges an image that already fits" {
            ImageProcessor.scaleDimensions(800, 600) shouldBe (800 to 600)
            ImageProcessor.scaleDimensions(10, 10) shouldBe (10 to 10)
            ImageProcessor.scaleDimensions(1024, 1024) shouldBe (1024 to 1024)
        }

        "scaleDimensions fits landscape image inside max dimension preserving ratio" {
            val (width, height) = ImageProcessor.scaleDimensions(2200, 1600)
            width shouldBe 1024
            height shouldBe 745
        }

        "scaleDimensions fits portrait image inside max dimension preserving ratio" {
            val (width, height) = ImageProcessor.scaleDimensions(1600, 3200)
            width shouldBe 512
            height shouldBe 1024
        }

        "scaleDimensions caps a square image at max dimension" {
            ImageProcessor.scaleDimensions(5000, 5000) shouldBe (1024 to 1024)
        }

        "passThroughEligible accepts small file fitting max dimension" {
            ImageProcessor.passThroughEligible(1000, 800, 200L * 1024) shouldBe true
        }

        "passThroughEligible rejects oversized dimensions" {
            ImageProcessor.passThroughEligible(2000, 800, 200L * 1024) shouldBe false
            ImageProcessor.passThroughEligible(800, 2000, 200L * 1024) shouldBe false
        }

        "passThroughEligible rejects heavy file even when dimensions fit" {
            ImageProcessor.passThroughEligible(1000, 800, 5L * 1024 * 1024) shouldBe false
        }

        "renderRgb flattens alpha onto white background" {
            val source = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB) // fully transparent
            val rendered = ImageProcessor.renderRgb(source, 10, 10)

            val pixel = Color(rendered.getRGB(5, 5))
            pixel.red shouldBe 255
            pixel.green shouldBe 255
            pixel.blue shouldBe 255
        }

        "encodeJpeg produces a decodable JPEG" {
            val source = BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB)
            val bytes = ImageProcessor.encodeJpeg(source)

            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
            decoded.width shouldBe 20
            decoded.height shouldBe 10
        }

        "toJpegContent resizes and reports final dimensions" {
            val source = BufferedImage(2048, 1024, BufferedImage.TYPE_INT_RGB)
            val content = ImageProcessor.toJpegContent(source)

            content.mimeType shouldBe "image/jpeg"
            content.width shouldBe 1024
            content.height shouldBe 512

            val decoded = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(content.content)))
            decoded.width shouldBe 1024
            decoded.height shouldBe 512
        }

        "toJpegContent keeps small images at their original size" {
            val source = BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB)
            val content = ImageProcessor.toJpegContent(source)

            content.width shouldBe 300
            content.height shouldBe 200
            content.width!! shouldBeLessThanOrEqual ImageProcessor.MAX_DIMENSION
        }
    }
}
