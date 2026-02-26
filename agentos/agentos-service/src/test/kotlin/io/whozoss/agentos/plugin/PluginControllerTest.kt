package io.whozoss.agentos.plugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentRegistry
import io.whozoss.agentos.plugin.api.PluginController
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Paths

class PluginControllerTest : StringSpec({

    val pluginService = mockk<PluginService>(relaxed = true)
    val agentRegistry = mockk<AgentRegistry>(relaxed = true)
    val controller = PluginController(pluginService, agentRegistry)

    val testJarFilename = "test-plugin-upload.jar"
    val testNonJarFilename = "test-plugin-upload.txt"

    afterSpec {
        // The controller writes to plugins/ relative to the working directory — clean up only files created by these tests
        Files.deleteIfExists(Paths.get("plugins", testJarFilename))
        Files.deleteIfExists(Paths.get("plugins", testNonJarFilename))
    }

    "uploadPlugin should reject an empty file" {
        val file = MockMultipartFile("file", testJarFilename, "application/java-archive", ByteArray(0))
        controller.uploadPlugin(file).statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    "uploadPlugin should reject a non-JAR file" {
        val file = MockMultipartFile("file", testNonJarFilename, "text/plain", "not a jar".toByteArray())
        controller.uploadPlugin(file).statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    "uploadPlugin should accept a valid JAR file" {
        val file = MockMultipartFile("file", testJarFilename, "application/java-archive", "jar content".toByteArray())
        controller.uploadPlugin(file).statusCode shouldBe HttpStatus.CREATED
    }
})
