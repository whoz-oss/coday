package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class GitToolProviderSpec :
    StringSpec({
        val provider = GitToolProvider()
        val mapper = jacksonObjectMapper()

        "declares the GIT integration with a configuration form" {
            provider.integrationType shouldBe "GIT"
            provider.configSchema.path("type").asText() shouldBe "object"
            provider.configSchema.path("properties").has("workingDirectory") shouldBe true
        }

        "provides no tool without a Git workspace or a configured repository and remote" {
            val remote = "https://github.com/org/project.git"
            val directory = Files.createTempDirectory("agentos-git-provider-").toString()

            provider.provideTools(null, "GIT").shouldBeEmpty()
            provider.provideTools(mapper.createObjectNode(), "GIT").shouldBeEmpty()
            provider.provideTools(mapper.createObjectNode().put("workingDirectory", "relative/repository").put("repositoryUrl", remote), "GIT").shouldBeEmpty()
            provider.provideTools(mapper.createObjectNode().put("workingDirectory", directory), "GIT").shouldBeEmpty()
        }

        "outside a Git workspace a configured repository and remote get the Git tools, as an ordinary integration" {
            // The repository itself is read when a tool first runs, so a wrong directory reaches the agent as its answer.
            val config =
                mapper.createObjectNode()
                    .put("workingDirectory", Files.createTempDirectory("agentos-git-provider-").toString())
                    .put("repositoryUrl", "https://github.com/org/project.git")

            provider.provideTools(config, "GIT") shouldHaveSize 6
        }
    })
