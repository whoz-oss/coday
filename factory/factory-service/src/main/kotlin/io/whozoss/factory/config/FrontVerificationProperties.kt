package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Factory-owned frontend verification policy shared by supported target repositories. */
@ConfigurationProperties(prefix = "factory.verification.front")
data class FrontVerificationProperties(
    val buildHosts: List<String> = listOf("aphrodite", "admin", "agentic-studio", "copilot-chat"),
    val buildTarget: String = "build",
    val buildConfiguration: String = "development",
    val testTarget: String = "frontend-test",
    val timeoutMs: Long = 1_800_000L,
)
