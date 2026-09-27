package io.whozoss.factory.forge.infrastructure

import io.whozoss.factory.forge.domain.ForgeCodedException
import io.whozoss.factory.forge.domain.ForgeSpec
import io.whozoss.factory.forge.domain.ForgeStorySpec
import io.whozoss.factory.forge.domain.ForgeWorkItem
import java.nio.file.Files
import java.nio.file.Path

/** Result of a validated Epic/Story spec read. */
data class LoadedForgeSpec(
    val path: String,
    val sha256: String,
    val schemaVersion: Int,
    val frontmatter: Map<String, Any?>,
    val rawContent: String? = null,
)

/**
 * Filesystem adapter for the Forge spec reads.
 *
 * Port of `factory/src/adapters/forge/forge-spec-reader.ts`. The frontmatter
 * parsers/validators are pure and live in the domain; this adapter owns the
 * filesystem boundary and the root-confinement checks.
 */
object ForgeSpecReader {

    private fun inside(child: String, root: String): Boolean {
        val rel = Path.of(root).relativize(Path.of(child)).toString()
        return rel.isEmpty() || (!rel.startsWith("..") && !Path.of(rel).isAbsolute)
    }

    private fun fail(code: String): Nothing = throw ForgeCodedException(code)

    private fun resolveFile(specPath: String, code: String): Path {
        if (specPath.isBlank() || !Path.of(specPath).isAbsolute) fail(code)
        return try {
            val path = Path.of(specPath).toRealPath()
            if (!Files.isRegularFile(path)) fail(code)
            path
        } catch (error: ForgeCodedException) {
            throw error
        } catch (error: Exception) {
            fail(code)
        }
    }

    /** Load and validate an Epic spec, checking the file is within the allowed roots. */
    fun loadForgeSpec(
        specPath: String,
        repoRoot: String,
        forgeRoot: String?,
        workItem: ForgeWorkItem,
    ): LoadedForgeSpec {
        val path = resolveFile(specPath, "G2_SPEC_PATH_INVALID")
        if (!inside(path.toString(), repoRoot) && !(forgeRoot != null && inside(path.toString(), forgeRoot))) {
            fail("G2_SPEC_OUTSIDE_ROOT")
        }
        val content = Files.readString(path)
        val match = ForgeSpec.FORGE_SPEC_FRONTMATTER_PATTERN.find(content) ?: fail("G2_FRONTMATTER_MISSING")
        val frontmatter = ForgeSpec.parseForgeSpecFrontmatter(match.groupValues[1])
        ForgeSpec.validateForgeSpecSchema(frontmatter, workItem)
        return LoadedForgeSpec(
            path = path.toString(),
            sha256 = ForgeSpec.computeForgeSpecHash(content),
            schemaVersion = (frontmatter["schemaVersion"] as? Number)?.toInt() ?: 0,
            frontmatter = frontmatter,
        )
    }

    /** Read and structurally validate a Story spec from disk. */
    fun readStorySpec(specPath: String, repoRoot: String, forgeRoot: String?): LoadedForgeSpec {
        val path = resolveFile(specPath, "G2_US_SPEC_PATH_INVALID")
        if (!inside(path.toString(), repoRoot) && !(forgeRoot != null && inside(path.toString(), forgeRoot))) {
            fail("G2_US_SPEC_OUTSIDE_ROOT")
        }
        val rawContent = Files.readString(path)
        val match = ForgeSpec.FORGE_SPEC_FRONTMATTER_PATTERN.find(rawContent) ?: fail("G2_FRONTMATTER_MISSING")
        val frontmatter = ForgeStorySpec.parseStorySpecFrontmatter(match.groupValues[1])
        ForgeStorySpec.validateStorySpec(frontmatter)
        return LoadedForgeSpec(
            path = path.toString(),
            sha256 = ForgeStorySpec.computeStorySpecHash(rawContent),
            schemaVersion = (frontmatter["schemaVersion"] as? Number)?.toInt() ?: 0,
            frontmatter = frontmatter,
            rawContent = rawContent,
        )
    }

    /** Compute the SHA-256 hash of a Story spec file without full validation. */
    fun hashStorySpec(specPath: String): String {
        val path = resolveFile(specPath, "G2_US_SPEC_PATH_INVALID")
        return ForgeStorySpec.computeStorySpecHash(Files.readString(path))
    }
}
