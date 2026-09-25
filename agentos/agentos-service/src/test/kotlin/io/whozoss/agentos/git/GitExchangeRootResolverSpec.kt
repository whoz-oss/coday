package io.whozoss.agentos.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.exchange.ExchangeStorageConfigProperties
import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeUnavailableException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The central invariant of the design: every case of an equipped family resolves to the *same*
 * directory — the root case's worktree — so grooming, development and review sub-cases work on the
 * same files and the same branch, while an ordinary family keeps its historic per-case directory.
 */
class GitExchangeRootResolverSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        class Fixture(
            val resolver: GitExchangeRootResolver,
            val storage: ExchangeStorageService,
            val bindings: InMemoryCaseResourceBindingService,
        )

        fun fixture(vararg cases: Case): Fixture {
            val mount = Files.createTempDirectory("agentos-resolver-")
            val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mount.toString()))
            val byId = cases.associateBy { it.id }
            val repository =
                mockk<CaseRepository> {
                    every { findByIds(any(), any()) } answers
                        {
                            firstArg<Collection<UUID>>().mapNotNull { byId[it] }
                        }
                }
            val bindings = InMemoryCaseResourceBindingService()
            return Fixture(GitExchangeRootResolver(repository, bindings, storage), storage, bindings)
        }

        fun case(
            title: String,
            parent: Case? = null,
        ): Case =
            Case(
                metadata = EntityMetadata(),
                namespaceId = namespaceId,
                title = title,
                parentCaseId = parent?.id,
            )

        fun equip(
            fixture: Fixture,
            rootCase: Case,
            status: CaseResourceStatus = CaseResourceStatus.READY,
        ): CaseResourceBinding =
            fixture.bindings.create(
                CaseResourceBinding(
                    rootCaseId = rootCase.id,
                    namespaceId = namespaceId,
                    integrationConfigId = UUID.randomUUID(),
                    status = status,
                ),
            )

        "an ordinary case resolves to its own historic directory" {
            val ordinary = case("Ordinary")
            val f = fixture(ordinary)

            val resolved = f.resolver.resolveGit(ordinary)

            resolved.binding shouldBe null
            resolved.isUsable shouldBe true
            resolved.path shouldBe f.storage.caseRoot(namespaceId, ordinary.id, ordinary.metadata.created)
        }

        "an ordinary sub-case keeps its own directory, separate from its parent" {
            val parent = case("Parent")
            val child = case("Child", parent = parent)
            val f = fixture(parent, child)

            f.resolver.resolveGit(child).path shouldNotBe f.resolver.resolveGit(parent).path
        }

        "an equipped root resolves to its worktree" {
            val root = case("Corriger les exports")
            val f = fixture(root)
            equip(f, root)

            f.resolver.resolveGit(root).path shouldBe f.storage.caseRoot(namespaceId, root.id, root.metadata.created)
        }

        "a sub-case of an equipped family resolves to the root's worktree" {
            val root = case("Corriger les exports")
            val child = case("Developpement", parent = root)
            val f = fixture(root, child)
            equip(f, root)

            f.resolver.resolveGit(child).path shouldBe f.resolver.resolveGit(root).path
            f.resolver.resolveGit(child).binding shouldNotBe null
        }

        "a grandchild resolves to the same worktree as the root" {
            val root = case("Corriger les exports")
            val child = case("Developpement", parent = root)
            val grandchild = case("Revue", parent = child)
            val f = fixture(root, child, grandchild)
            equip(f, root)

            f.resolver.resolveGit(grandchild).path shouldBe f.resolver.resolveGit(root).path
        }

        "two equipped families never share a directory" {
            val first = case("Premiere racine")
            val second = case("Seconde racine")
            val f = fixture(first, second)
            equip(f, first)
            equip(f, second)

            f.resolver.resolveGit(first).path shouldNotBe f.resolver.resolveGit(second).path
        }

        "a soft-deleted root still anchors its family" {
            val root = case("Removed root")
            val child = case("Still alive", parent = root)
            val removedRoot = root.copy(metadata = root.metadata.copy(removed = true))
            val f = fixture(removedRoot, child)
            equip(f, root)

            // Deleting a parent does not delete its children, so the family must keep resolving to
            // the same worktree rather than every descendant falling back to its own directory.
            f.resolver.resolveGit(child).path shouldBe f.storage.caseRoot(namespaceId, root.id, root.metadata.created)
        }

        "a workspace that is not ready refuses to hand out its path" {
            val root = case("Preparing")
            val child = case("Child", parent = root)
            val f = fixture(root, child)
            equip(f, root, status = CaseResourceStatus.PREPARING)

            val resolved = f.resolver.resolveGit(child)

            resolved.isUsable shouldBe false
            resolved.isPending shouldBe true
            shouldThrow<ExchangeUnavailableException> { resolved.requireUsable() }
        }

        "a removed workspace is not pending, and still refuses its path" {
            val root = case("Removed workspace")
            val f = fixture(root)
            equip(f, root, status = CaseResourceStatus.REMOVED)

            val resolved = f.resolver.resolveGit(root)

            resolved.isPending shouldBe false
            shouldThrow<ExchangeUnavailableException> { resolved.requireUsable() }
        }

        "a ready workspace hands out its path" {
            val root = case("Ready")
            val f = fixture(root)
            equip(f, root)

            f.resolver.resolveGit(root).requireUsable() shouldBe f.resolver.resolveGit(root).path
        }

        "the exchange contract keeps ordinary ownership and maps the equipped family without Git details" {
            val root = case("Root")
            val child = case("Child", root)
            val f = fixture(root, child)
            val resolver: ExchangeRootResolver = f.resolver

            val ordinary = resolver.resolve(child)
            ordinary.ownerCaseId shouldBe child.id
            ordinary.workspace shouldBe null
            ordinary.requireWorkingDirectory() shouldBe f.storage.caseRoot(namespaceId, child.id, child.metadata.created)

            equip(f, root)
            val shared = resolver.resolve(child)
            shared.ownerCaseId shouldBe root.id
            shared.workspace?.id shouldBe root.id
            shared.requireWorkingDirectory() shouldBe f.storage.caseRoot(namespaceId, root.id, root.metadata.created).resolve("repo")
        }

        "the exchange contract refuses every unavailable Git state without a directory fallback" {
            val root = case("Root")
            val f = fixture(root)
            val binding = equip(f, root)
            val resolver: ExchangeRootResolver = f.resolver

            CaseResourceStatus.entries.filter { !it.isUsable }.forEach { status ->
                f.bindings.update(binding.copy(status = status))
                val resolved = resolver.resolve(root)
                resolved.isUsable shouldBe false
                shouldThrow<ExchangeUnavailableException> { resolved.requireUsable() }
                shouldThrow<ExchangeUnavailableException> { resolved.requireWorkingDirectory() }
            }
        }

        "a shared file mutation cannot enter while preparation or cleanup owns the root lock" {
            val root = case("Root")
            val child = case("Child", root)
            val f = fixture(root, child)
            equip(f, root)
            val acquired = CountDownLatch(1)
            val release = CountDownLatch(1)
            val owner = Thread.ofVirtual().start {
                WorkspaceLifecycleLocks.withRoot(root.id) {
                    acquired.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
            try {
                acquired.await(5, TimeUnit.SECONDS) shouldBe true
                var mutated = false
                shouldThrow<ConflictException> {
                    f.resolver.withCaseMutation(child.id) { mutated = true }
                }
                mutated shouldBe false
            } finally {
                release.countDown()
                owner.join(5000)
            }
            f.resolver.withCaseMutation(child.id) { it.ownerCaseId } shouldBe root.id
        }

        "mutation rechecks availability after taking the lifecycle lock" {
            val root = case("Root")
            val f = fixture(root)
            val binding = equip(f, root)
            val changingBindings = mockk<CaseResourceBindingService> {
                every { findByRootCaseId(root.id) } returnsMany listOf(
                    binding, binding.copy(status = CaseResourceStatus.DELETING),
                )
            }
            val repository = mockk<CaseRepository> {
                every { findByIds(listOf(root.id), withRemoved = true) } returns listOf(root)
            }
            val resolver = GitExchangeRootResolver(repository, changingBindings, f.storage)
            var mutated = false

            shouldThrow<ExchangeUnavailableException> {
                resolver.withCaseMutation(root.id) { mutated = true }
            }

            mutated shouldBe false
            WorkspaceLifecycleLocks.tryWithRoot(root.id, onBusy = { false }) { true } shouldBe true
        }
    })
