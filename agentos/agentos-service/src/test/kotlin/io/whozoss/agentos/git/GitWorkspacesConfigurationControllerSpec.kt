package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** The configuration route stays registered whatever the switch, unlike the workspace routes it describes. */
class GitWorkspacesConfigurationControllerSpec :
    StringSpec({
        val contexts = ApplicationContextRunner().withUserConfiguration(GitWorkspacesConfigurationController::class.java)

        listOf(null to false, "false" to false, "true" to true).forEach { (property, expected) ->
            "the controller is registered and reports enabled=$expected with agentos.git.workspaces.enabled=$property" {
                val runner = property?.let { contexts.withPropertyValues("agentos.git.workspaces.enabled=$it") } ?: contexts
                runner.run { context ->
                    val controller = context.getBean(GitWorkspacesConfigurationController::class.java)
                    controller.getGitWorkspacesConfiguration().enabled shouldBe expected
                }
            }
        }

        "the route serves the setting as JSON" {
            for (enabled in listOf(false, true)) {
                val mvc = MockMvcBuilders.standaloneSetup(GitWorkspacesConfigurationController(enabled)).build()
                mvc
                    .perform(get("/api/git-workspaces-configuration"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.enabled").value(enabled))
            }
        }
    })
