package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams

class LspLanguageClientUnitSpec :
    StringSpec({
        "a configuration request should be answered with one null per requested item, so that the server uses its defaults" {
            // given
            val client = LspLanguageClient()
            val params =
                ConfigurationParams(
                    listOf(
                        ConfigurationItem().apply { section = "kotlin" },
                        ConfigurationItem().apply {
                            section =
                                "files"
                        },
                    ),
                )

            // when
            val answer = client.configuration(params).get()

            // then
            answer shouldBe listOf(null, null)
        }

        "a configuration request without item should be answered with an empty list" {
            // given
            val client = LspLanguageClient()

            // when
            val answer = client.configuration(ConfigurationParams(emptyList())).get()

            // then
            answer shouldBe emptyList<Any?>()
        }

        "the registration and the progress requests of the server should be accepted" {
            // given
            val client = LspLanguageClient()

            // when
            val registered = client.registerCapability(RegistrationParams(emptyList())).get()
            val unregistered = client.unregisterCapability(UnregistrationParams(emptyList())).get()
            val progress = client.createProgress(WorkDoneProgressCreateParams()).get()

            // then
            registered shouldBe null
            unregistered shouldBe null
            progress shouldBe null
        }
    })
