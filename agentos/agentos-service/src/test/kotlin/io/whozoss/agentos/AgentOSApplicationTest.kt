package io.whozoss.agentos

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class AgentOSApplicationTest : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        "context should load successfully" {
            // This test verifies that the Spring application context loads correctly
            // The SpringExtension handles the context lifecycle
        }
    }
}
