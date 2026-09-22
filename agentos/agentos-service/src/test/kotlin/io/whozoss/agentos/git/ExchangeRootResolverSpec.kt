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
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.util.UUID

/**
 * The central invariant of the design: every case of an equipped family resolves to the *same*
 * directory — the root case's worktree — so grooming, development and review sub-cases work on the
 * same files and the same branch, while an ordinary family keeps its historic per-case directory.
 */
class ExchangeRootResolverSpec :
    StringSpec({

        val namespaceId = UUID.randomUUID()

        class Fixture(
            val resolver: ExchangeRootResolver,
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
            return Fixture(ExchangeRootResolver(repository, bindings, storage), storage, bindings)
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
                    branchName = "corriger-les-exports",
                ),
            )

        "an ordinary case resolves to its own historic directory" {
            val ordinary = case("Ordinary")
            val f = fixture(ordinary)

            val resolved = f.resolver.resolve(ordinary)

            resolved.binding shouldBe null
            resolved.isUsable shouldBe true
            resolved.path shouldBe f.storage.caseRoot(namespaceId, ordinary.id, ordinary.metadata.created)
        }

        "an ordinary sub-case keeps its own directory, separate from its parent" {
            val parent = case("Parent")
            val child = case("Child", parent = parent)
            val f = fixture(parent, child)

            f.resolver.resolve(child).path shouldNotBe f.resolver.resolve(parent).path
        }

        "an equipped root resolves to its worktree" {
            val root = case("Corriger les exports")
            val f = fixture(root)
            equip(f, root)

            f.resolver.resolve(root).path shouldBe f.storage.caseRoot(namespaceId, root.id, root.metadata.created)
        }

        "a sub-case of an equipped family resolves to the root's worktree" {
            val root = case("Corriger les exports")
            val child = case("Developpement", parent = root)
            val f = fixture(root, child)
            equip(f, root)

            f.resolver.resolve(child).path shouldBe f.resolver.resolve(root).path
            f.resolver.resolve(child).binding shouldNotBe null
        }

        "a grandchild resolves to the same worktree as the root" {
            val root = case("Corriger les exports")
            val child = case("Developpement", parent = root)
            val grandchild = case("Revue", parent = child)
            val f = fixture(root, child, grandchild)
            equip(f, root)

            f.resolver.resolve(grandchild).path shouldBe f.resolver.resolve(root).path
        }

        "two equipped families never share a directory" {
            val first = case("Premiere racine")
            val second = case("Seconde racine")
            val f = fixture(first, second)
            equip(f, first)
            equip(f, second)

            f.resolver.resolve(first).path shouldNotBe f.resolver.resolve(second).path
        }

        "a soft-deleted root still anchors its family" {
            val root = case("Removed root")
            val child = case("Still alive", parent = root)
            val removedRoot = root.copy(metadata = root.metadata.copy(removed = true))
            val f = fixture(removedRoot, child)
            equip(f, root)

            // Deleting a parent does not delete its children, so the family must keep resolving to
            // the same worktree rather than every descendant falling back to its own directory.
            f.resolver.resolve(child).path shouldBe f.storage.caseRoot(namespaceId, root.id, root.metadata.created)
        }

        "a workspace that is not ready refuses to hand out its path" {
            val root = case("Preparing")
            val child = case("Child", parent = root)
            val f = fixture(root, child)
            equip(f, root, status = CaseResourceStatus.PREPARING)

            val resolved = f.resolver.resolve(child)

            resolved.isUsable shouldBe false
            resolved.isPending shouldBe true
            shouldThrow<CaseWorkspaceUnavailableException> { resolved.requireUsable() }
        }

        "a removed workspace is not pending, and still refuses its path" {
            val root = case("Removed workspace")
            val f = fixture(root)
            equip(f, root, status = CaseResourceStatus.REMOVED)

            val resolved = f.resolver.resolve(root)

            resolved.isPending shouldBe false
            shouldThrow<CaseWorkspaceUnavailableException> { resolved.requireUsable() }
        }

        "a ready workspace hands out its path" {
            val root = case("Ready")
            val f = fixture(root)
            equip(f, root)

            f.resolver.resolve(root).requireUsable() shouldBe f.resolver.resolve(root).path
        }
    })
