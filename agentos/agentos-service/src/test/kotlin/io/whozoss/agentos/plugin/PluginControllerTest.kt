package io.whozoss.agentos.plugin

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentRegistry
import io.whozoss.agentos.plugin.api.PluginController
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile

class PluginControllerTest :
    DescribeSpec({

        val pluginService = mockk<PluginService>(relaxed = true)
        val agentRegistry = mockk<AgentRegistry>(relaxed = true)
        val controller = PluginController(pluginService, agentRegistry)

        describe("uploadPlugin") {

            it("should reject an empty file") {
                val file = MockMultipartFile("file", "plugin.jar", "application/java-archive", ByteArray(0))
                val response = controller.uploadPlugin(file)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("should reject a non-JAR file") {
                val file = MockMultipartFile("file", "plugin.txt", "text/plain", "not a jar".toByteArray())
                val response = controller.uploadPlugin(file)
                response.statusCode shouldBe HttpStatus.BAD_REQUEST
            }

            it("should accept a valid JAR file") {
                val file = MockMultipartFile("file", "plugin.jar", "application/java-archive", "jar content".toByteArray())
                val response = controller.uploadPlugin(file)
                response.statusCode shouldBe HttpStatus.CREATED
            }
        }
    })
