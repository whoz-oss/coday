package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.api.namespace.AdminCaseListing
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The platform default of the namespace setting `adminCaseListing` is
 * `agentos.case.admin-case-listing-default`, overridable with `AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT`.
 * These specs load the shipped `application.yml`, so they guard the value operators actually get.
 */
class CaseConfigPropertiesSpec :
    StringSpec({
        // The placeholders read the system environment first: replacing that property source keeps these
        // specs about the shipped file, whatever the machine or the runner exports.
        fun ApplicationContextRunner.withSystemEnvironment(variables: Map<String, Any> = emptyMap()) =
            withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        variables,
                    ),
                )
            }

        val shipped =
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withUserConfiguration(CasePropertiesTestConfiguration::class.java)
                .withSystemEnvironment()

        "the code default is that admins see their own cases" {
            CaseConfigProperties().adminCaseListingDefault shouldBe AdminCaseListing.OWN
        }

        "the shipped application.yml defines the key under agentos.case and keeps it on OWN" {
            shipped.run { context ->
                context.environment.getProperty("agentos.case.admin-case-listing-default") shouldBe "OWN"
                context.getBean(CaseConfigProperties::class.java).adminCaseListingDefault shouldBe AdminCaseListing.OWN
            }
        }

        listOf("OWN" to AdminCaseListing.OWN, "SWITCH" to AdminCaseListing.SWITCH, "ALL" to AdminCaseListing.ALL)
            .forEach { (value, expected) ->
                "AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT=$value binds to $expected" {
                    shipped
                        .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT" to value))
                        .run { context ->
                            context.getBean(CaseConfigProperties::class.java).adminCaseListingDefault shouldBe expected
                        }
                }
            }

        "a lower-case value binds too, as Spring relaxed binding allows" {
            shipped
                .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT" to "switch"))
                .run { context ->
                    context.getBean(CaseConfigProperties::class.java).adminCaseListingDefault shouldBe AdminCaseListing.SWITCH
                }
        }

        "an unknown value fails the startup instead of silently falling back" {
            shipped
                .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT" to "EVERYTHING"))
                .run { context ->
                    (context.startupFailure != null) shouldBe true
                }
        }

        "the other agentos.case settings keep their defaults when the new one is set" {
            shipped
                .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_CASE_LISTING_DEFAULT" to "ALL"))
                .run { context ->
                    val properties = context.getBean(CaseConfigProperties::class.java)
                    properties.idleEvictionGraceMs shouldBe CaseConfigProperties().idleEvictionGraceMs
                    properties.summaryEnabled shouldBe CaseConfigProperties().summaryEnabled
                }
        }
    })

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CaseConfigProperties::class)
private class CasePropertiesTestConfiguration
