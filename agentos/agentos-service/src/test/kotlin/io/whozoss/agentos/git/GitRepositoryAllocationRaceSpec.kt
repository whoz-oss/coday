package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import io.whozoss.agentos.integrationConfig.InMemoryIntegrationConfigRepository
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigPolicy
import io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GitRepositoryAllocationRaceSpec : StringSpec({
    "repair and family allocation agree even when allocation starts between validation and save" {
        // The uncoordinated control reproduces the old failure with the same production paths;
        // the coordinated run must give the new family the repaired settings instead.
        listOf(false, true).forEach { coordinated ->
            val mapper = jacksonObjectMapper()
            val namespaceId = UUID.randomUUID()
            val oldUrl = "https://forge.example/old.git"
            val newUrl = "https://forge.example/correct.git"
            val checkouts = InMemoryRepositoryCheckouts()
            val bindings = InMemoryCaseResourceBindingService()
            val root = Files.createTempDirectory("git-allocation-race-")
            val storage = mockk<ExchangeStorageService> {
                every { namespaceGitDirectory(namespaceId) } returns root.resolve("repository.git")
            }
            val factory = GitRepositorySettingsFactory(mockk<GitRemoteUrlValidator> {
                every { validate(any()) } returns Unit
            })
            val provisioner = RepositoryCheckoutProvisioner(mockk(), GitExecutionProperties(), storage, checkouts, mockk(), bindings)
            val policy = GitRepositoryConfigPolicy(factory, checkouts, provisioner, mockk { every { isAvailable() } returns true })
            val validated = CountDownLatch(1)
            val saveAllowed = CountDownLatch(1)
            val allocationStarted = CountDownLatch(1)
            val observedPolicy = object : IntegrationConfigPolicy by policy {
                override fun <T> aroundSave(config: IntegrationConfig, action: () -> T): T =
                    if (coordinated) policy.aroundSave(config, action) else action()

                override fun validate(config: IntegrationConfig) {
                    policy.validate(config)
                    if (config.parameters?.get(GitRepositoryIntegration.PARAM_REPOSITORY_URL)?.asText() == newUrl) {
                        validated.countDown()
                        check(saveAllowed.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            val service = IntegrationConfigServiceImpl(InMemoryIntegrationConfigRepository(), IntegrationConfigMergeStrategy(), listOf(observedPolicy))
            val associations = GitRepositoryAssociationService(service, factory)
            val allocation = GitCaseWorkspaceProvisioning(associations, bindings, mapper, mockk { every { isAvailable() } returns true })
            val parameters = mapper.createObjectNode()
                .put(GitRepositoryIntegration.PARAM_REPOSITORY_URL, oldUrl)
                .put(GitRepositoryIntegration.PARAM_SERVICE_AUTH_SETTING_ID, UUID.randomUUID().toString())
                .put(GitRepositoryIntegration.PARAM_AUTO_WORKTREE, true)
            val original = service.create(IntegrationConfig(namespaceId = namespaceId, name = "git",
                integrationType = GitRepositoryIntegration.TYPE, parameters = parameters))
            checkouts.markStatus(checkouts.findByNamespaceId(namespaceId)!!.id, RepositoryCheckoutStatus.FAILED, "First clone failed")
            val corrected = original.copy(parameters = parameters.deepCopy().put(GitRepositoryIntegration.PARAM_REPOSITORY_URL, newUrl))
            val case = Case(namespaceId = namespaceId)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val repair = executor.submit<IntegrationConfig> { service.update(corrected) }
                validated.await(5, TimeUnit.SECONDS) shouldBe true
                val allocate = executor.submit {
                    allocationStarted.countDown()
                    allocation.onCaseCreated(case)
                }
                allocationStarted.await(5, TimeUnit.SECONDS) shouldBe true
                if (coordinated) {
                    shouldThrow<TimeoutException> { allocate.get(200, TimeUnit.MILLISECONDS) }
                } else {
                    allocate.get(5, TimeUnit.SECONDS)
                }
                saveAllowed.countDown()
                repair.get(5, TimeUnit.SECONDS)
                allocate.get(5, TimeUnit.SECONDS)
                associations.findSettings(namespaceId)!!.repositoryUrl shouldBe newUrl
                val boundSettings = mapper.readValue(bindings.findByRootCaseId(case.id)!!.settingsJson, GitRepositorySettings::class.java)
                boundSettings.repositoryUrl shouldBe if (coordinated) newUrl else oldUrl
                checkouts.findByNamespaceId(namespaceId)!!.repositoryUrl shouldBe if (coordinated) newUrl else oldUrl
            } finally {
                saveAllowed.countDown()
                executor.shutdownNow()
                root.toFile().deleteRecursively()
            }
        }
    }
})
