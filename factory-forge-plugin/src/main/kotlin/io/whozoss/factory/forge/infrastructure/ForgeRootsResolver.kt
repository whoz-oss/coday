package io.whozoss.factory.forge.infrastructure

import io.whozoss.factory.forge.domain.DEFAULT_RUN_STORE_POLICY
import io.whozoss.factory.forge.domain.EXTERNAL_RUN_STORE_POLICY
import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeRoots
import io.whozoss.factory.forge.domain.ForgeRootsPolicy
import io.whozoss.factory.forge.domain.REPO_RUN_STORE_POLICY
import io.whozoss.factory.forge.domain.isWithin
import java.nio.file.Files
import java.nio.file.Path

/**
 * Filesystem adapter for the Forge roots resolution.
 *
 * Port of `factory/src/adapters/forge/forge-roots-resolver.ts`. The policy
 * vocabulary, containment predicate and default location live in the domain.
 */
object ForgeRootsResolver {

    private fun resolveExistingDirectory(value: Any?, field: String): String {
        if (value !is String || value.isBlank()) throw ForgeCodedException("FORGE_ROOTS_INVALID", "$field is required")
        val path = Path.of(value)
        if (!path.isAbsolute) throw ForgeCodedException("FORGE_ROOTS_INVALID", "$field must be an absolute path")
        return try {
            val real = path.toRealPath()
            if (!Files.isDirectory(real)) throw ForgeCodedException("FORGE_ROOTS_INVALID", "not a directory")
            real.toString()
        } catch (error: ForgeCodedException) {
            throw error
        } catch (error: Exception) {
            throw ForgeCodedException(
                "FORGE_ROOTS_INVALID",
                "$field must exist as a directory and resolve without a broken symlink",
                error,
            )
        }
    }

    /**
     * The store itself may be absent: Factory owns its idempotent creation. Its
     * immediate parent is nevertheless explicit, existing, and realpath-checked.
     */
    private fun resolveStoreRoot(value: Any?): String {
        if (value !is String || value.isBlank()) {
            throw ForgeCodedException("FORGE_ROOTS_INVALID", "roots.runStoreRoot is required")
        }
        val requested = Path.of(value)
        if (!requested.isAbsolute) {
            throw ForgeCodedException("FORGE_ROOTS_INVALID", "roots.runStoreRoot must be an absolute path")
        }
        val parent = resolveExistingDirectory(requested.parent?.toString(), "roots.runStoreParent")
        val candidate = Path.of(parent, requested.fileName.toString())
        return if (Files.exists(candidate)) {
            resolveExistingDirectory(candidate.toString(), "roots.runStoreRoot")
        } else {
            candidate.toString()
        }
    }

    /** Resolve, validate and freeze the Forge roots. */
    @Suppress("UNCHECKED_CAST")
    fun resolve(input: Any?): ForgeRoots {
        if (input !is Map<*, *>) throw ForgeCodedException("FORGE_ROOTS_INVALID", "roots object is required")
        val map = input.entries.associate { it.key.toString() to it.value }
        val orchestratorRoot = resolveExistingDirectory(map["orchestratorRoot"], "roots.orchestratorRoot")
        val repoRoot = resolveExistingDirectory(map["repoRoot"], "roots.repoRoot")
        val forgeRoot = map["forgeRoot"]?.let { resolveExistingDirectory(it, "roots.forgeRoot") }
        val runStoreRoot = resolveStoreRoot(map["runStoreRoot"])
        val runStorePolicy = map["runStorePolicy"] ?: DEFAULT_RUN_STORE_POLICY
        if (runStorePolicy !in ForgeRootsPolicy.FORGE_RUN_STORE_POLICIES) {
            throw ForgeCodedException(
                "FORGE_ROOTS_INVALID",
                "roots.runStorePolicy must be $DEFAULT_RUN_STORE_POLICY, $EXTERNAL_RUN_STORE_POLICY, or $REPO_RUN_STORE_POLICY",
            )
        }
        val policy = runStorePolicy as String
        if (policy == DEFAULT_RUN_STORE_POLICY && !isWithin(runStoreRoot, orchestratorRoot)) {
            throw ForgeCodedException(
                "FORGE_ROOTS_INVALID",
                "roots.runStoreRoot must remain under roots.orchestratorRoot unless runStorePolicy is external_allowed",
            )
        }
        if (policy == REPO_RUN_STORE_POLICY && !isWithin(runStoreRoot, repoRoot)) {
            throw ForgeCodedException(
                "FORGE_ROOTS_INVALID",
                "roots.runStoreRoot must remain under roots.repoRoot when runStorePolicy is under_repo",
            )
        }
        return ForgeRoots(
            orchestratorRoot = orchestratorRoot,
            runStoreRoot = runStoreRoot,
            repoRoot = repoRoot,
            forgeRoot = forgeRoot,
            runStorePolicy = policy,
        )
    }

    /** Idempotently create the run store directory and return its real path. */
    fun ensureForgeRunStore(runStoreRoot: String): String {
        val path = Path.of(runStoreRoot)
        Files.createDirectories(path)
        return resolveExistingDirectory(runStoreRoot, "roots.runStoreRoot")
    }
}
