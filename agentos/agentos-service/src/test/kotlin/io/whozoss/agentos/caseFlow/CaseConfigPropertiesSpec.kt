package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The platform default of the namespace setting "admin can list all cases" is
 * `agentos.case.admin-list-all-cases-default`, overridable with `AGENTOS_CASE_ADMIN_LIST_ALL_CASES_DEFAULT`.
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

        "the code default is that admins get no All cases switch" {
            CaseConfigProperties().adminListAllCasesDefault shouldBe false
        }

        "the shipped application.yml defines the key under agentos.case and keeps it off" {
            shipped.run { context ->
                context.environment.getProperty("agentos.case.admin-list-all-cases-default") shouldBe "false"
                context.getBean(CaseConfigProperties::class.java).adminListAllCasesDefault shouldBe false
            }
        }

        "AGENTOS_CASE_ADMIN_LIST_ALL_CASES_DEFAULT=true turns the platform default on" {
            shipped
                .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_LIST_ALL_CASES_DEFAULT" to "true"))
                .run { context ->
                    context.getBean(CaseConfigProperties::class.java).adminListAllCasesDefault shouldBe true
                }
        }

        "the other agentos.case settings keep their defaults when the new one is set" {
            shipped
                .withSystemEnvironment(mapOf("AGENTOS_CASE_ADMIN_LIST_ALL_CASES_DEFAULT" to "true"))
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
