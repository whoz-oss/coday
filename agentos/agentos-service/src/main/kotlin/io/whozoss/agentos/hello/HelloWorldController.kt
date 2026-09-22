package io.whozoss.agentos.hello

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Simple Hello World controller for AgentOS.
 */
@RestController
@RequestMapping("/api/hello")
@Tag(name = "Hello", description = "Hello World endpoint")
class HelloWorldController {

    @GetMapping
    @Operation(summary = "Get hello world message")
    fun helloWorld(): Map<String, String> {
        return mapOf(
            "message" to "Hello World from AgentOS!",
            "version" to "1.0",
            "status" to "running"
        )
    }
}
