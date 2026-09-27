package io.whozoss.factory.forge.domain

import java.nio.file.Path

/**
 * Pure Forge roots policy: the store-location vocabulary and the path
 * containment rules that decide where a Forge run store may live.
 *
 * Port of `factory/src/domain/forge-bmad/forge-roots.ts`. The filesystem
 * resolution lives in
 * [io.whozoss.factory.forge.infrastructure.ForgeRootsResolver].
 */
object ForgeRootsPolicy {

    /** Version 2 adds an explicit policy for an externally hosted run store. */
    const val FORGE_ROOTS_SCHEMA_VERSION = 2
    const val DEFAULT_RUN_STORE_POLICY = "under_orchestrator"
    const val EXTERNAL_RUN_STORE_POLICY = "external_allowed"
    const val REPO_RUN_STORE_POLICY = "under_repo"

    /** The accepted run-store policies, in canonical order. */
    val FORGE_RUN_STORE_POLICIES: List<String> = listOf(
        DEFAULT_RUN_STORE_POLICY,
        EXTERNAL_RUN_STORE_POLICY,
        REPO_RUN_STORE_POLICY,
    )

    /**
     * Path-segment containment: a sibling sharing a text prefix is NOT inside
     * the parent. Uses [Path.relativize], never prefix string matching.
     */
    fun isWithin(child: String, parent: String): Boolean {
        val rel = Path.of(parent).relativize(Path.of(child)).toString()
        return rel.isEmpty() || (!rel.startsWith("..") && !Path.of(rel).isAbsolute)
    }

    /**
     * Default run store root: `<repoRoot>/forge/factory-runs/`. Use this when
     * the ledgers must live in the target repository, not in the orchestrator.
     */
    fun defaultRunStoreRoot(repoRoot: String): String =
        Path.of(repoRoot, "forge", "factory-runs").toString()
}

/** Resolved, frozen Forge roots returned by the resolver. */
data class ForgeRoots(
    val orchestratorRoot: String,
    val runStoreRoot: String,
    val repoRoot: String,
    val forgeRoot: String? = null,
    val runStorePolicy: String = ForgeRootsPolicy.DEFAULT_RUN_STORE_POLICY,
    val schemaVersion: Int = ForgeRootsPolicy.FORGE_ROOTS_SCHEMA_VERSION,
) {
    /** The wire/ledger representation, byte-compatible with the Node frozen object. */
    fun toMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["schemaVersion"] = schemaVersion
        out["orchestratorRoot"] = orchestratorRoot
        out["runStoreRoot"] = runStoreRoot
        out["repoRoot"] = repoRoot
        if (forgeRoot != null) out["forgeRoot"] = forgeRoot
        out["runStorePolicy"] = runStorePolicy
        return out
    }

    companion object {
        /** Rebuild roots from a ledger `roots` payload. */
        fun fromMap(map: Map<String, Any?>): ForgeRoots = ForgeRoots(
            orchestratorRoot = map["orchestratorRoot"] as? String ?: "",
            runStoreRoot = map["runStoreRoot"] as? String ?: "",
            repoRoot = map["repoRoot"] as? String ?: "",
            forgeRoot = map["forgeRoot"] as? String,
            runStorePolicy = map["runStorePolicy"] as? String ?: ForgeRootsPolicy.DEFAULT_RUN_STORE_POLICY,
            schemaVersion = (map["schemaVersion"] as? Number)?.toInt() ?: ForgeRootsPolicy.FORGE_ROOTS_SCHEMA_VERSION,
        )
    }
}

const val FORGE_ROOTS_SCHEMA_VERSION = ForgeRootsPolicy.FORGE_ROOTS_SCHEMA_VERSION
const val DEFAULT_RUN_STORE_POLICY = ForgeRootsPolicy.DEFAULT_RUN_STORE_POLICY
const val EXTERNAL_RUN_STORE_POLICY = ForgeRootsPolicy.EXTERNAL_RUN_STORE_POLICY
const val REPO_RUN_STORE_POLICY = ForgeRootsPolicy.REPO_RUN_STORE_POLICY

fun isWithin(child: String, parent: String): Boolean = ForgeRootsPolicy.isWithin(child, parent)

fun defaultRunStoreRoot(repoRoot: String): String = ForgeRootsPolicy.defaultRunStoreRoot(repoRoot)
