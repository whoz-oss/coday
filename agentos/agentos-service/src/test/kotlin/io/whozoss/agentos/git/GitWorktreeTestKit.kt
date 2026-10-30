package io.whozoss.agentos.git

import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.authSetting.AuthSettingService
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/**
 * Real repositories and a worktree provisioner over them, shared by the specs that prepare and
 * clean up case workspaces. Each [fixture] is a fresh namespace in a fresh Exchange mount.
 */
object GitWorktreeTestKit {
    val gitProperties = GitExecutionProperties(allowedRemoteProtocols = setOf("https", "file"))
    val runner = GitCommandRunner(gitProperties)

    fun rawGit(
        directory: Path,
        vararg args: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .also {
                    it.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                    it.environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
                }.start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return out
    }

    fun originRepository(): Path {
        val root = Files.createTempDirectory("agentos-origin-")
        rawGit(root, "init", "--quiet", "--initial-branch=main")
        rawGit(root, "config", "user.email", "ci@example.com")
        rawGit(root, "config", "user.name", "CI")
        rawGit(root, "config", "commit.gpgsign", "false")
        root.resolve("README.md").writeText("v1\n")
        rawGit(root, "add", "-A")
        rawGit(root, "commit", "--quiet", "-m", "base")
        return root
    }

    fun advanceOrigin(origin: Path) {
        origin.resolve("README.md").writeText("v2\n")
        rawGit(origin, "add", "-A")
        rawGit(origin, "commit", "--quiet", "-m", "advance")
    }

    class Fixture(
        val storage: ExchangeStorageService,
        val provisioner: CaseWorktreeProvisioner,
        val bindings: InMemoryCaseResourceBindingService,
        val namespaceId: UUID,
    )

    fun fixture(): Fixture {
        val mount = Files.createTempDirectory("agentos-mount-")
        val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mount.toString()), listOf(GitMetadataEntries()))
        val namespaceId = UUID.randomUUID()
        val bindings = InMemoryCaseResourceBindingService()

        val authSettings =
            mockk<AuthSettingService> {
                every { findById(any(), any()) } returns
                    BearerTokenAuthSetting(
                        metadata = EntityMetadata(),
                        namespaceId = namespaceId,
                        userId = null,
                        name = "git-service-account",
                        token = "unused-for-file-transport",
                    )
            }
        val serviceAccounts = GitServiceAccountResolver(authSettings)

        val provisioner =
            CaseWorktreeProvisioner(
                runner = runner,
                gitProperties = gitProperties,
                exchangeStorageService = storage,
                bindingService = bindings,
                checkoutProvisioner =
                    RepositoryCheckoutProvisioner(
                        runner = runner,
                        gitProperties = gitProperties,
                        exchangeStorageService = storage,
                        checkoutService = InMemoryRepositoryCheckoutService(),
                        serviceAccountResolver = serviceAccounts,
                    ),
                serviceAccountResolver = serviceAccounts,
                setupRunner = WorktreeSetupRunner(gitProperties),
                clock = Clock.systemUTC(),
            )
        return Fixture(storage, provisioner, bindings, namespaceId)
    }

    fun settings(
        namespaceId: UUID,
        origin: Path,
        setupCommand: String? = null,
    ): GitRepositorySettings =
        GitRepositorySettings(
            configId = UUID.randomUUID(),
            namespaceId = namespaceId,
            repositoryUrl = origin.toUri().toString(),
            mainBranch = "main",
            serviceAuthSettingId = UUID.randomUUID(),
            autoWorktreeForRootCases = true,
            setupCommand = setupCommand,
        )

    fun rootCase(
        namespaceId: UUID,
        title: String,
    ): Case = Case(metadata = EntityMetadata(), namespaceId = namespaceId, title = title)

    fun binding(
        fixture: Fixture,
        rootCase: Case,
        configured: GitRepositorySettings,
    ): CaseResourceBinding =
        fixture.bindings.create(
            CaseResourceBinding(
                rootCaseId = rootCase.id,
                namespaceId = fixture.namespaceId,
                integrationConfigId = configured.configId,
                // Frozen on every binding, as case creation records them.
                settings = configured,
            ),
        )

    /** Cleanup as the worker runs it once every case of the family has been deleted. */
    fun deletedFamilyLifecycle(f: Fixture, root: Case): GitWorkspaceLifecycleService {
        val removed = root.copy(metadata = root.metadata.copy(removed = true))
        val cases = mockk<io.whozoss.agentos.caseFlow.CaseRepository> {
            every { findByIds(any(), any()) } returns listOf(removed)
            every { findByParent(any()) } returns emptyList()
        }
        return GitWorkspaceLifecycleService(f.bindings, cases,
            mockk { every { getObject() } returns mockk { every { hasRunningExecutions(any()) } returns false; every { getAllActiveCases() } returns emptyList() } },
            GitExchangeRootResolver(cases, f.bindings, f.storage), f.storage, runner)
    }

    fun commitIdentity(path: Path) {
        rawGit(path, "config", "user.email", "ci@example.com")
        rawGit(path, "config", "user.name", "CI")
        rawGit(path, "config", "commit.gpgsign", "false")
    }
}
