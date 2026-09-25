package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitCredentials
import io.whozoss.agentos.git.core.GitExecutionProperties
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GitWorkspaceStatusServiceSpec : StringSpec({
    fun git(path: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", path.toString(), *args)).redirectErrorStream(true).apply {
            environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
            environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
        }.start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) { text }
        return text.trim()
    }
    data class Fixture(val path: Path, val binding: CaseResourceBinding,
        val bindings: InMemoryCaseResourceBindingService, val service: GitWorkspaceStatusService)

    fun fixture(maxOutputChars: Int = 100_000, hosting: GitHostingProvider = mockk()): Fixture {
        val root = Files.createTempDirectory("agentos-status-spec-")
        val origin = Files.createDirectory(root.resolve("origin"))
        git(origin, "init", "--quiet", "--initial-branch=main")
        git(origin, "config", "user.name", "Test")
        git(origin, "config", "user.email", "test@example.invalid")
        git(origin, "config", "commit.gpgsign", "false")
        Files.writeString(origin.resolve("tracked.txt"), "base")
        git(origin, "add", ".")
        git(origin, "commit", "--quiet", "-m", "base")
        val common = root.resolve("common.git")
        git(root, "clone", "--quiet", "--bare", origin.toString(), common.toString())
        val caseId = UUID.randomUUID()
        val path = root.resolve(caseId.toString())
        git(common, "worktree", "add", "--quiet", "--detach", path.toString(), "main")
        val namespaceId = UUID.randomUUID()
        val settings = GitRepositorySettings(UUID.randomUUID(), namespaceId, origin.toUri().toString(), "main", UUID.randomUUID(), true, null)
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val bindings = InMemoryCaseResourceBindingService()
        val binding = bindings.create(CaseResourceBinding(rootCaseId = caseId, namespaceId = namespaceId,
            integrationConfigId = settings.configId, status = CaseResourceStatus.READY,
            baseSha = git(path, "rev-parse", "HEAD"), settingsJson = mapper.writeValueAsString(settings)))
        val storage = mockk<ExchangeStorageService> { every { namespaceGitDirectory(namespaceId) } returns common }
        val account = mockk<GitServiceAccountResolver> { every { resolve(any()) } returns GitCredentials.UsernamePassword("fixture", "fake") }
        val runner = GitCommandRunner(GitExecutionProperties(maxOutputChars = maxOutputChars, allowedRemoteProtocols = setOf("file")))
        return Fixture(path, binding, bindings, GitWorkspaceStatusService(bindings, mockk(), storage, runner, account, hosting, mapper))
    }

    "a truncated list of untracked entries still proves dirty without refreshing the index" {
        val f = fixture(maxOutputChars = 512)
        val administrative = f.path.parent.resolve("common.git/worktrees/${f.binding.rootCaseId}")
        val indexBefore = Files.readAllBytes(administrative.resolve("index"))
        Files.setLastModifiedTime(f.path.resolve("tracked.txt"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        repeat(30) { Files.writeString(f.path.resolve("untracked-file-with-a-long-name-$it.txt"), "test") }
        val observed = f.service.summary(f.service.refresh(f.binding, f.path))!!
        observed.dirty shouldBe true
        observed.error shouldBe null
        Files.readAllBytes(administrative.resolve("index")).toList() shouldBe indexBefore.toList()
    }

    "provider exception messages and causes never enter the persisted status" {
        val secret = "synthetic-git-token"
        val hosting = mockk<GitHostingProvider> {
            every { inspect(any(), any(), any()) } throws IllegalArgumentException(
                "invalid header value: Bearer $secret", IllegalStateException(secret),
            )
        }
        val f = fixture(hosting = hosting)
        git(f.path, "switch", "-c", "agent-branch")
        val saved = f.service.refresh(f.binding, f.path)
        val observed = f.service.summary(saved)!!
        observed.prState shouldBe "UNKNOWN"
        observed.error shouldBe "Git status unavailable. Check repository access and service account settings."
        saved.summaryJson!! shouldNotContain secret
    }

    "slow forge observation does not hold admission lock or overwrite a concurrent lifecycle transition" {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hosting = mockk<GitHostingProvider> {
            every { inspect(any(), any(), any()) } answers {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                GitWorkspaceSummary(prState = "NONE")
            }
        }
        val f = fixture(hosting = hosting)
        git(f.path, "switch", "-c", "agent-branch")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<CaseResourceBinding> { f.service.refresh(f.binding, f.path) }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            WorkspaceLifecycleLocks.tryWithRoot(f.binding.rootCaseId, onBusy = { false }) { true } shouldBe true
            WorkspaceLifecycleLocks.withRoot(f.binding.rootCaseId) {
                f.bindings.markStatus(f.binding.id, CaseResourceStatus.DELETING)
            }
            release.countDown()
            future.get(10, TimeUnit.SECONDS).status shouldBe CaseResourceStatus.DELETING
            f.bindings.findByRootCaseId(f.binding.rootCaseId)!!.status shouldBe CaseResourceStatus.DELETING
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
})
