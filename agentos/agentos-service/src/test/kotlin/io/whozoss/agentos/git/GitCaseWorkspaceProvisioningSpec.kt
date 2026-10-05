package io.whozoss.agentos.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider

/**
 * When a case creation results in a workspace being requested.
 *
 * The rules are all about *not* acting: a sub-case never allocates, an unassociated namespace
 * never allocates, an association with automation off never allocates, and a broken association
 * is refused for the admin to fix instead of silently creating an unequipped family.
 */
class GitCaseWorkspaceProvisioningSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        fun settings(autoWorktree: Boolean): GitRepositorySettings =
            GitRepositorySettings(
                configId = UUID.randomUUID(),
                namespaceId = namespaceId,
                repositoryUrl = "https://forge.example/org/project.git",
                mainBranch = "main",
                serviceAuthSettingId = UUID.randomUUID(),
                autoWorktreeForRootCases = autoWorktree,
                setupCommand = null,
            )

        fun availability(available: Boolean = true): GitAvailability = mockk { every { isAvailable() } returns available }

        fun hook(
            bindings: CaseResourceBindingService,
            available: Boolean = true,
            automation: Boolean = true,
            resolve: () -> GitRepositorySettings?,
        ): GitCaseWorkspaceProvisioning {
            val association = mockk<GitRepositoryAssociationService> {
                every { automationEnabled(namespaceId) } returns automation
                every { findAutomaticSettings(any()) } answers { resolve() }
            }
            return GitCaseWorkspaceProvisioning(
                association,
                bindings,
                availability(available),
                workerOn(),
            )
        }

        /** Creation as the case service runs it: allocation happens inside [GitCaseWorkspaceProvisioning.aroundCreation]. */
        fun GitCaseWorkspaceProvisioning.create(case: Case) = aroundCreation(case) { onCaseCreated(case) }

        fun rootCase(title: String = "Corriger les exports"): Case =
            Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = title)

        fun subCase(parent: Case): Case =
            Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = "Developpement", parentCaseId = parent.id)

        "a case is never equipped without the plugin, as a sub-case, or without automation on its namespace" {
            table(
                headers("situation", "available", "automation", "associated", "inSubCase"),
                row("GIT plugin not loaded", false, true, true, false),
                row("sub-case sharing its root's workspace", true, true, true, true),
                row("namespace without an association", true, false, false, false),
                row("association with automation off", true, false, true, false),
            ).forAll { _, available, automation, associated, inSubCase ->
                val bindings = InMemoryCaseResourceBindingService()
                val case = if (inSubCase) subCase(rootCase()) else rootCase()

                hook(bindings, available = available, automation = automation) {
                    settings(autoWorktree = automation).takeIf { associated }
                }.create(case)

                bindings.findByRootCaseId(case.id).shouldBeNull()
            }
        }

        "a root case in a namespace with automation on is equipped" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            val configured = settings(autoWorktree = true)
            hook(bindings) { configured }.create(case)

            val binding = bindings.findByRootCaseId(case.id)
            binding.shouldNotBeNull()
            binding.status shouldBe CaseResourceStatus.REQUESTED
            binding.settings shouldBe configured
        }

        "root case creations wait for each other only in a namespace that equips new families" {
            table(
                headers("automation", "secondWaits"),
                row(true, true),
                row(false, false),
            ).forAll { automation, secondWaits ->
                val provisioning = hook(InMemoryCaseResourceBindingService(), automation = automation) { null }
                val firstInside = CountDownLatch(1)
                val releaseFirst = CountDownLatch(1)
                val secondInside = CountDownLatch(1)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    executor.submit {
                        provisioning.aroundCreation(rootCase()) {
                            firstInside.countDown()
                            check(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                    }
                    firstInside.await(5, TimeUnit.SECONDS) shouldBe true
                    executor.submit { provisioning.aroundCreation(rootCase()) { secondInside.countDown() } }
                    secondInside.await(200, TimeUnit.MILLISECONDS) shouldBe !secondWaits
                    releaseFirst.countDown()
                    secondInside.await(5, TimeUnit.SECONDS) shouldBe true
                } finally {
                    releaseFirst.countDown()
                    executor.shutdownNow()
                }
            }
        }

        "a broken association is rejected as a conflict for the admin to fix, before creating an unequipped family" {
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()

            shouldThrow<ConflictException> {
                hook(bindings) { throw BadRequestException("serviceAuthSettingId must be a UUID") }.create(case)
            }.message shouldBe "The namespace Git settings are invalid (serviceAuthSettingId must be a UUID): " +
                "a namespace admin must fix them before new conversations can start"

            bindings.findByRootCaseId(case.id).shouldBeNull()
        }

        "disabled automation never parses broken Git fields or resolves a remote host" {
            val config = IntegrationConfig(
                namespaceId = namespaceId, name = "git", integrationType = GitRepositoryIntegration.TYPE,
                parameters = jacksonObjectMapper().readTree(
                    """{"autoWorktreeForRootCases":false,"repositoryUrl":"https://127.0.0.1/repo","serviceAuthSettingId":"invalid"}"""),
            )
            val configs = mockk<IntegrationConfigService> {
                every { findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) } returns config
            }
            val validator = mockk<GitRemoteUrlValidator>()
            val association = GitRepositoryAssociationService(configs, GitRepositorySettingsFactory(validator))
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()
            GitCaseWorkspaceProvisioning(association, bindings, availability(), workerOn())
                .create(case)
            bindings.findByRootCaseId(case.id).shouldBeNull()
            association.automationEnabled(namespaceId) shouldBe false
            verify(exactly = 0) { validator.validate(any()) }
        }

        "enabled automation records preparation without doing a DNS check in case creation" {
            val mapper = jacksonObjectMapper()
            val config = IntegrationConfig(
                namespaceId = namespaceId, name = "git", integrationType = GitRepositoryIntegration.TYPE,
                parameters = mapper.valueToTree(mapOf(
                    GitRepositoryIntegration.PARAM_AUTO_WORKTREE to true,
                    GitRepositoryIntegration.PARAM_REPOSITORY_URL to "https://127.0.0.1/repo",
                    GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID to UUID.randomUUID().toString(),
                )),
            )
            val configs = mockk<IntegrationConfigService> {
                every { findActiveNamespaceSingleton(namespaceId, GitRepositoryIntegration.TYPE) } returns config
            }
            val validator = mockk<GitRemoteUrlValidator>()
            val association = GitRepositoryAssociationService(configs, GitRepositorySettingsFactory(validator))
            val bindings = InMemoryCaseResourceBindingService()
            val case = rootCase()
            GitCaseWorkspaceProvisioning(association, bindings, availability(), workerOn()).create(case)
            bindings.findByRootCaseId(case.id)?.status shouldBe CaseResourceStatus.REQUESTED
            association.automationEnabled(namespaceId) shouldBe true
            verify(exactly = 0) { validator.validate(any()) }
        }

    
        "no family is equipped while the Git worker is disabled, and only a namespace with automation on is warned about" {
            table(
                headers("automation", "warnings"),
                row(true, 1),
                row(false, 0),
            ).forAll { automation, warnings ->
                val bindings = InMemoryCaseResourceBindingService()
                val case = rootCase()
                val association = mockk<GitRepositoryAssociationService> {
                    every { automationEnabled(namespaceId) } returns automation
                    every { findAutomaticSettings(any()) } returns settings(autoWorktree = automation)
                }
                val workerOff = mockk<ObjectProvider<CaseWorkspaceWorker>> { every { getIfAvailable() } returns null }
                val logger = LoggerFactory.getLogger(GitCaseWorkspaceProvisioning::class.java) as Logger
                val logs = ListAppender<ILoggingEvent>().also { it.start() }
                logger.addAppender(logs)
                try {
                    GitCaseWorkspaceProvisioning(association, bindings, availability(), workerOff)
                        .create(case)
                } finally {
                    logger.detachAppender(logs)
                    logs.stop()
                }

                bindings.findByRootCaseId(case.id).shouldBeNull()
                logs.list.count { it.level == Level.WARN && it.formattedMessage.contains("Git worker is disabled") } shouldBe warnings
            }
        }
})

/** The Git worker is enabled, so a family can be equipped. */
internal fun workerOn(): ObjectProvider<CaseWorkspaceWorker> =
    mockk { every { getIfAvailable() } returns mockk() }
