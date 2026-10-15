package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.git.GitWorktreeTestKit.advanceOrigin
import io.whozoss.agentos.git.GitWorktreeTestKit.binding
import io.whozoss.agentos.git.GitWorktreeTestKit.fixture
import io.whozoss.agentos.git.GitWorktreeTestKit.originRepository
import io.whozoss.agentos.git.GitWorktreeTestKit.rawGit
import io.whozoss.agentos.git.GitWorktreeTestKit.rootCase
import io.whozoss.agentos.git.GitWorktreeTestKit.settings
import io.whozoss.agentos.git.core.GitCommandException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A case family gets its own detached worktree, cut from a frozen base commit.
 *
 * Exercised against a real repository: the retry cases are the point, since the design's whole
 * recovery story rests on preparation being safely repeatable.
 */
@EnabledIf(PosixOnly::class)
class CaseWorktreeProvisionerSpec :
    StringSpec({
        timeout = 180_000

        "a root case gets a detached worktree without creating a branch" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Corriger les exports")

            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            ready.status shouldBe CaseResourceStatus.READY
            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.resolve("README.md").readText() shouldBe "v1\n"
            worktree.resolve(".git").exists() shouldBe true
            rawGit(worktree, "branch", "--show-current").trim() shouldBe ""
            rawGit(worktree, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
        }

        "preparation is idempotent" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Idempotent")
            val created = binding(f, root, configured)

            val first = f.provisioner.ensureReady(created, configured, root)
            val second = f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)

            second.baseSha shouldBe first.baseSha
        }

        "a retry after explicit worktree removal keeps the frozen base after the main branch moved" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val root = rootCase(f.namespaceId, "Frozen base")
            val created = binding(f, root, configured)

            val first = f.provisioner.ensureReady(created, configured, root)
            val frozen = requireNotNull(first.baseSha)

            // Explicit Git removal removes this clean checkout and its registration together.
            advanceOrigin(origin)
            val worktree = f.provisioner.worktreePath(root)
            rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "worktree", "remove", worktree.toString())
            worktree.exists() shouldBe false
            f.bindings.markStatus(first.id, CaseResourceStatus.FAILED)

            val retried = f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)

            retried.baseSha shouldBe frozen
        }

        "retry retains an absent family's index and succeeds when its checkout returns" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Unavailable checkout")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            path.resolve("README.md").writeText("Unique staged work\n")
            rawGit(path, "add", "README.md")
            path.resolve("README.md").writeText("Different working copy\n")
            val admin = f.storage.namespaceGitDirectory(f.namespaceId).resolve("worktrees/${root.id}")
            val index = Files.readAllBytes(admin.resolve("index")).toList()
            val offline = path.resolveSibling("offline")
            Files.move(path, offline)
            try {
                val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))
                shouldThrow<GitCommandException> { f.provisioner.ensureReady(requested, configured, root) }
                Files.readAllBytes(admin.resolve("index")).toList() shouldBe index
            } finally {
                Files.move(offline, path)
            }
            f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root).status shouldBe CaseResourceStatus.READY
            rawGit(path, "show", ":README.md").trim() shouldBe "Unique staged work"
            path.resolve("README.md").readText() shouldBe "Different working copy\n"
        }

        "retry adopts a valid registration whose pointers are relative" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Relative Git pointers")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            val pinned = f.storage.namespaceGitDirectory(f.namespaceId).toAbsolutePath().resolve("worktrees/${root.id}")
            val allocated = pinned.resolveSibling("repo")
            Files.move(pinned, allocated)
            // Git's relative-pointer format; construct it directly so this regression also runs
            // with Git versions that predate the worktree.useRelativePaths configuration switch.
            path.resolve(".git").writeText("gitdir: ${path.relativize(allocated)}\n")
            allocated.resolve("gitdir").writeText("${allocated.relativize(path.resolve(".git"))}\n")
            val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))
            f.provisioner.ensureReady(requested, configured, root).status shouldBe CaseResourceStatus.READY
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
            pinned.resolve("index").exists() shouldBe true
            allocated.exists() shouldBe false
        }

        "a new case fetches the latest remote base without changing an existing dirty worktree" {
            val f = fixture()
            val origin = originRepository()
            val configured = settings(f.namespaceId, origin)
            val first = rootCase(f.namespaceId, "Existing")
            val original = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val path = f.provisioner.worktreePath(first)
            rawGit(path, "switch", "-c", "agent-feature")
            path.resolve("README.md").writeText("local unfinished edit\n")
            rawGit(path, "add", "README.md")
            val indexBefore = rawGit(path, "diff", "--cached")
            advanceOrigin(origin)
            val latest = rawGit(origin, "rev-parse", "HEAD").trim()
            val next = rootCase(f.namespaceId, "New")
            val fresh = f.provisioner.ensureReady(binding(f, next, configured), configured, next)
            fresh.baseSha shouldBe latest
            rawGit(f.provisioner.worktreePath(next), "rev-parse", "HEAD").trim() shouldBe latest
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe original.baseSha
            rawGit(path, "branch", "--show-current").trim() shouldBe "agent-feature"
            rawGit(path, "diff", "--cached") shouldBe indexBefore
            path.resolve("README.md").readText() shouldBe "local unfinished edit\n"
            // Ordinary agent fetches use remote-tracking refs and preserve local branches too.
            rawGit(path, "fetch", "origin")
            rawGit(path, "rev-parse", "origin/main").trim() shouldBe latest
            rawGit(path, "rev-parse", "HEAD").trim() shouldBe original.baseSha
        }

        "two root cases with the same title get independent detached worktrees" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val first = rootCase(f.namespaceId, "Meme titre")
            val second = rootCase(f.namespaceId, "Meme titre")

            val a = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val b = f.provisioner.ensureReady(binding(f, second, configured), configured, second)

            rawGit(f.storage.namespaceGitDirectory(f.namespaceId), "for-each-ref", "--format=%(refname)", "refs/heads/").trim() shouldBe ""
            f.storage.caseRoot(f.namespaceId, first.id, first.metadata.created) shouldNotBe
                f.storage.caseRoot(f.namespaceId, second.id, second.metadata.created)
        }

        "a retry preserves a branch created by an agent and its local work" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Agent workflow")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "switch", "-c", "agent-chosen-name")
            path.resolve("local.txt").writeText("work in progress")
            f.provisioner.ensureReady(ready, configured, root)
            rawGit(path, "branch", "--show-current").trim() shouldBe "agent-chosen-name"
            path.resolve("local.txt").readText() shouldBe "work in progress"
        }

        listOf("before metadata rename", "before pointer rewrite").forEach { crashPoint ->
            "retry recovers a creation interrupted $crashPoint without discarding local files" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val root = rootCase(f.namespaceId, "Interrupted registration")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val pinned = f.storage.namespaceGitDirectory(f.namespaceId).toAbsolutePath().resolve("worktrees/${root.id}")
                val allocated = pinned.resolveSibling("repo")
                if (crashPoint == "before metadata rename") Files.move(pinned, allocated)
                path.resolve(".git").writeText("gitdir: $allocated\n")
                path.resolve("local.txt").writeText("Keep the interrupted workspace")
                val interrupted = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))

                f.provisioner.ensureReady(interrupted, configured, root).status shouldBe CaseResourceStatus.READY

                path.resolve("local.txt").readText() shouldBe "Keep the interrupted workspace"
                rawGit(path, "rev-parse", "HEAD").trim() shouldBe ready.baseSha
                Path.of(path.resolve(".git").readText().trim().removePrefix("gitdir: ")).toRealPath() shouldBe pinned.toRealPath()
            }
        }

        "retry refuses a worktree pointer redirected to another case" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "First")
            val other = rootCase(f.namespaceId, "Other")
            val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
            f.provisioner.ensureReady(binding(f, other, configured), configured, other)
            val path = f.provisioner.worktreePath(root)
            path.resolve(".git").writeText(f.provisioner.worktreePath(other).resolve(".git").readText())
            shouldThrow<IllegalStateException> { f.provisioner.ensureReady(ready, configured, root) }
            f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
            path.resolve("README.md").readText() shouldBe "v1\n"
        }

        "a populated target directory fails loudly instead of being wiped" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Occupied")
            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            Files.createDirectories(worktree)
            worktree.resolve("stray-upload.txt").writeText("uploaded before the workspace was ready\n")

            val error = shouldThrow<IllegalStateException> { f.provisioner.ensureReady(binding(f, root, configured), configured, root) }

            error.message!! shouldContain "already holds"
            worktree.resolve("stray-upload.txt").exists() shouldBe true
            f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
        }

        "an empty target directory is fine, since a tool grant routinely creates one" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val root = rootCase(f.namespaceId, "Pre created")
            Files.createDirectories(f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo"))

            f.provisioner.ensureReady(binding(f, root, configured), configured, root).status shouldBe CaseResourceStatus.READY
        }

        "a checkout interrupted before the workspace was ever ready is set aside and recreated" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository())
            val first = rootCase(f.namespaceId, "Prepare namespace repository")
            val ready = f.provisioner.ensureReady(binding(f, first, configured), configured, first)
            val root = rootCase(f.namespaceId, "Interrupted checkout")
            val pending = f.bindings.update(binding(f, root, configured).copy(baseSha = ready.baseSha))
            val common = f.storage.namespaceGitDirectory(f.namespaceId)
            val path = f.provisioner.worktreePath(root)
            Files.createDirectories(path.parent)
            // Git's own pre-checkout state has valid HEAD/pointers but no index or tracked files.
            rawGit(common, "worktree", "add", "--no-checkout", "--detach", path.toString(), ready.baseSha!!)
            val admin = Path.of(path.resolve(".git").readText().trim().removePrefix("gitdir: "))
            admin.resolve("locked").writeText("initializing\n")
            path.resolve("README.md").exists() shouldBe false
            admin.resolve("index").exists() shouldBe false
            path.resolve("local.txt").writeText("Preserve partial work")

            f.provisioner.ensureReady(pending, configured, root).status shouldBe CaseResourceStatus.READY

            path.resolve("README.md").readText() shouldBe "v1\n"
            path.resolve("local.txt").exists() shouldBe false
            // Nothing is deleted: the interrupted checkout is kept outside both browsable Exchanges.
            val support = f.storage.workspaceSupportDirectory(f.namespaceId, root.id)
            val setAside = Files.list(support).use { entries -> entries.toList() }
                .single { it.fileName.toString().startsWith("interrupted-checkout-") }
            setAside.resolve("local.txt").readText() shouldBe "Preserve partial work"
        }

        listOf("initializing", "missing index").forEach { incompleteState ->
            "retry refuses an incomplete checkout with $incompleteState and preserves local work" {
                val f = fixture()
                val configured = settings(f.namespaceId, originRepository())
                val root = rootCase(f.namespaceId, "Incomplete checkout")
                val ready = f.provisioner.ensureReady(binding(f, root, configured), configured, root)
                val path = f.provisioner.worktreePath(root)
                val admin = f.storage.namespaceGitDirectory(f.namespaceId).resolve("worktrees/${root.id}")
                val index = Files.readAllBytes(admin.resolve("index"))
                path.resolve("local.txt").writeText("Keep my work")
                if (incompleteState == "initializing") admin.resolve("locked").writeText("initializing\n")
                else Files.delete(admin.resolve("index"))
                val requested = f.bindings.update(ready.copy(status = CaseResourceStatus.REQUESTED))

                shouldThrow<IllegalStateException> { f.provisioner.ensureReady(requested, configured, root) }
                f.bindings.findByRootCaseId(root.id)!!.status shouldBe CaseResourceStatus.FAILED
                path.resolve("local.txt").readText() shouldBe "Keep my work"
                // An ordinary retry cannot overwrite the incomplete checkout or run setup over it.
                shouldThrow<IllegalStateException> {
                    f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root)
                }
                path.resolve("local.txt").readText() shouldBe "Keep my work"

                // Explicit inspection/recovery restores the valid checkout; retry then converges.
                Files.deleteIfExists(admin.resolve("locked"))
                Files.write(admin.resolve("index"), index)
                f.provisioner.ensureReady(f.bindings.findByRootCaseId(root.id)!!, configured, root).status shouldBe CaseResourceStatus.READY
                path.resolve("local.txt").readText() shouldBe "Keep my work"
            }
        }

        "setup HOME and tool caches stay outside both browsable Exchanges" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(),
                setupCommand = "mkdir -p \"${'$'}HOME/.npm\" \"${'$'}XDG_CACHE_HOME/tool\"; touch \"${'$'}HOME/.npm/log\" \"${'$'}XDG_CACHE_HOME/tool/item\"")
            val root = rootCase(f.namespaceId, "Setup cache")
            f.provisioner.ensureReady(binding(f, root, configured), configured, root).status shouldBe CaseResourceStatus.READY
            val path = f.provisioner.worktreePath(root)
            rawGit(path, "status", "--porcelain").trim() shouldBe ""
            val support = f.storage.workspaceSupportDirectory(f.namespaceId, root.id)
            support.resolve(".npm/log").exists() shouldBe true
            support.resolve(".cache/tool/item").exists() shouldBe true
            path.parent.resolve(".setup-home").exists() shouldBe false
            f.storage.listManifest(path.parent, io.whozoss.agentos.sdk.api.exchange.ExchangeScope.CASE)
                .map { it.path } shouldBe listOf("repo/README.md")
            f.storage.listManifest(f.storage.namespaceRoot(f.namespaceId), io.whozoss.agentos.sdk.api.exchange.ExchangeScope.NAMESPACE)
                .isEmpty() shouldBe true
        }

        "the configured setup command runs in the worktree" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "echo prepared > setup-marker.txt")
            val root = rootCase(f.namespaceId, "With setup")

            f.provisioner.ensureReady(binding(f, root, configured), configured, root)

            val worktree = f.storage.caseRoot(f.namespaceId, root.id, root.metadata.created).resolve("repo")
            worktree.resolve("setup-marker.txt").readText().trim() shouldBe "prepared"
        }

        "a failing setup command leaves the workspace unusable without publishing its output" {
            val f = fixture()
            val configured = settings(f.namespaceId, originRepository(), setupCommand = "echo synthetic-secret; exit 1")
            val root = rootCase(f.namespaceId, "Broken setup")
            val logger = org.slf4j.LoggerFactory.getLogger("io.whozoss.agentos.git") as ch.qos.logback.classic.Logger
            val logs = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            logger.addAppender(logs)
            try {
                shouldThrow<IllegalStateException> { f.provisioner.ensureReady(binding(f, root, configured), configured, root) }
                val failed = f.bindings.findByRootCaseId(root.id)!!
                failed.status shouldBe CaseResourceStatus.FAILED
                failed.failureReason!!.contains("synthetic-secret") shouldBe false
                failed.failureReason shouldContain "Setup failed"
                logs.list.any { it.loggerName == CaseWorktreeProvisioner::class.java.name && it.level == ch.qos.logback.classic.Level.ERROR } shouldBe true
                val rendered = logs.list.joinToString("\n") {
                    it.formattedMessage + (it.throwableProxy?.let(ch.qos.logback.classic.spi.ThrowableProxyUtil::asString) ?: "")
                }
                rendered.contains("synthetic-secret") shouldBe false
            } finally {
                logger.detachAppender(logs)
                logs.stop()
            }
        }
    })
