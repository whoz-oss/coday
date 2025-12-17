package io.biznet.agentos.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication
class AgentOSApplication

fun main(args: Array<String>) {
    runApplication<AgentOSApplication>(*args)
}
