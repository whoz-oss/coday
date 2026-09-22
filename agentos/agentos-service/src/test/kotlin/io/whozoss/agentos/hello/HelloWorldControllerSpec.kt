package io.whozoss.agentos.hello

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the HelloWorldController.
 */
class HelloWorldControllerSpec : StringSpec({

    "helloWorld should return correct message" {
        val controller = HelloWorldController()
        val result = controller.helloWorld()

        result["message"] shouldBe "Hello World from AgentOS!"
        result["version"] shouldBe "1.0"
        result["status"] shouldBe "running"
    }
})
