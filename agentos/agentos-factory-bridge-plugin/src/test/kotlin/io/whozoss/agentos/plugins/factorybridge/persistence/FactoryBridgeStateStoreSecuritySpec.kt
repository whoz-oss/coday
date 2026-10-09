package io.whozoss.agentos.plugins.factorybridge.persistence

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Security properties of the durable bridge state: the capability token must not be
 * readable from disk, the file must not be readable by other users, and an expired secret
 * must not linger indefinitely.
 *
 * These assertions are deliberately made against the **raw file content** rather than the
 * store's own API: the threat is someone reading the file, so that is what the test reads.
 */
class FactoryBridgeStateStoreSecuritySpec : StringSpec({
    val now = Instant.parse("2030-01-01T00:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val token = "secret-token-value-with-sufficient-length"
    val mapper = jacksonObjectMapper()

    fun withDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("factory-bridge-security")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun state(
        caseId: UUID,
        namespaceId: UUID,
        expiresAt: Instant,
    ) = FactoryStepResultBindingState(
        caseId = caseId,
        namespaceId = namespaceId,
        agentName = "Worker",
        attemptId = "attempt",
        runtimeId = "runtime",
        capabilityToken = token,
        expiresAt = expiresAt,
        leased = false,
    )

    fun encrypting() = FactoryStateEncryptor.of("a-strong-random-key-of-sufficient-length", "0123456789abcdef")

    "the capability token is never written in plaintext when encryption is configured" {
        withDir { dir ->
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            val store = FactoryBridgeStateStore(file, mapper, encrypting(), clock)
            store.putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))

            val raw = Files.readString(file)
            raw shouldNotContain token
            // The envelope is still readable — only the secret is protected.
            raw shouldContain "attempt"
        }
    }

    "an encrypted token round-trips through a restart" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(caseId, namespaceId, now.plusSeconds(60)))

            val reopened = FactoryBridgeStateStore(file, mapper, encrypting(), clock)
            reopened.binding(caseId).shouldNotBeNull().capabilityToken shouldBe token
        }
    }

    // A rotated key (or a file written while encryption was off) must not yield a usable
    // binding: no capability is the safe outcome, the Factory can reissue one.
    "a token that cannot be decrypted is dropped rather than resurrected" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(caseId, UUID.randomUUID(), now.plusSeconds(60)))

            val otherKey = FactoryStateEncryptor.of("a-different-strong-random-key-value-here", "fedcba9876543210")
            FactoryBridgeStateStore(file, mapper, otherKey, clock).binding(caseId) shouldBe null
        }
    }

    "the state file and its directory are owner-only" {
        withDir { root ->
            val dir = root.resolve("nested")
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))

            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
            PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
        }
    }

    // Permissions survive a rewrite: ATOMIC_MOVE replaces the inode, so the temp file must
    // itself be created restricted. A regression here would silently widen the file.
    "permissions survive a subsequent write" {
        withDir { dir ->
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            val store = FactoryBridgeStateStore(file, mapper, encrypting(), clock)
            store.putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))
            store.putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))

            PosixFilePermissions.toString(Files.getPosixFilePermissions(file)) shouldBe "rw-------"
        }
    }

    "no temporary file is left behind" {
        withDir { dir ->
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))

            Files.list(dir).use { entries ->
                entries.map { it.fileName.toString() }.sorted().toList()
                    .shouldContainExactly(FactoryBridgeStateStore.STATE_FILE)
            }
        }
    }

    // Expiry alone must not evict — renewal depends on the binding still being there.
    "a recently expired binding is retained so it can still be renewed" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(caseId, UUID.randomUUID(), now.plusSeconds(60)))

            val justAfterExpiry = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)
            FactoryBridgeStateStore(file, mapper, encrypting(), justAfterExpiry).binding(caseId).shouldNotBeNull()
        }
    }

    "a long-expired binding is purged from memory and from disk" {
        withDir { dir ->
            val caseId = UUID.randomUUID()
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, encrypting(), clock)
                .putBinding(state(caseId, UUID.randomUUID(), now.plusSeconds(60)))

            val wellPastRetention =
                Clock.fixed(now.plus(FactoryBridgeStateStore.EXPIRED_RETENTION).plusSeconds(3600), ZoneOffset.UTC)
            val reopened = FactoryBridgeStateStore(file, mapper, encrypting(), wellPastRetention)
            reopened.binding(caseId) shouldBe null

            // The purge must reach the file, not just the working set: any mutation
            // rewrites the snapshot without the stale secret.
            reopened.putCheckpoint(UUID.randomUUID(), io.whozoss.agentos.plugins.factorybridge.FactoryCheckpointRef("w", "i", 1L))
            Files.readString(file) shouldNotContain caseId.toString()
        }
    }

    "encryption disabled leaves the token readable — the documented opt-out" {
        withDir { dir ->
            val file = dir.resolve(FactoryBridgeStateStore.STATE_FILE)
            FactoryBridgeStateStore(file, mapper, FactoryStateEncryptor.disabled(), clock)
                .putBinding(state(UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(60)))

            Files.readString(file) shouldContain token
        }
    }

    "ciphertext is non-deterministic across writes of the same token" {
        withDir { dir ->
            val encryptor = encrypting()
            encryptor.encrypt(token) shouldNotBe encryptor.encrypt(token)
        }
    }
})
