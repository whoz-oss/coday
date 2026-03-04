package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityRepository
import mu.KLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

/**
 * Generic file-system implementation of [EntityRepository].
 *
 * Storage layout on disk:
 * ```
 * <rootDir>/
 *   <parentId>/
 *     <entityId>.json
 * ```
 *
 * Each entity is serialised to a single JSON file named after its UUID.
 * Writes are atomic: the JSON is written to a `.tmp` file first, then
 * renamed into place, so a crash mid-write never corrupts an existing file.
 *
 * Soft-delete sets `metadata.removed = true` and re-persists the file;
 * the file is kept on disk so the audit trail is preserved.
 *
 * Thread-safety: file-system operations on individual entities are inherently
 * safe (each entity has its own file). The [findByParent] directory scan is
 * eventually-consistent under high concurrency, which is acceptable for the
 * current use-cases.
 *
 * @param T        Entity type
 * @param P        Parent identifier type (must have a stable [toString] representation)
 * @param rootDir  Root directory for this repository (e.g. `data/cases`)
 * @param entityClass  Reified class used by Jackson for deserialisation
 * @param objectMapper Jackson mapper configured with Kotlin module
 * @param parentIdExtractor  Extracts the parent ID from an entity
 * @param comparator  Orders entities returned by [findByParent]
 */
abstract class FilesystemEntityRepository<T : Entity, P>(
    protected val rootDir: Path,
    private val entityClass: Class<T>,
    private val objectMapper: ObjectMapper,
    private val parentIdExtractor: (T) -> P,
    private val comparator: Comparator<T>,
) : EntityRepository<T, P> {
    init {
        Files.createDirectories(rootDir)
        logger.info { "[${entityClass.simpleName}Repository] Initialised with rootDir=$rootDir" }
    }

    // -------------------------------------------------------------------------
    // EntityRepository implementation
    // -------------------------------------------------------------------------

    override fun save(entity: T): T {
        val parentId = parentIdExtractor(entity)
        val parentDir = rootDir.resolve(parentId.toString())
        Files.createDirectories(parentDir)

        val file = parentDir.resolve("${entity.metadata.id}.json")
        writeAtomic(file, entity)
        logger.debug { "[${entityClass.simpleName}Repository] Saved entity ${entity.metadata.id} under parent $parentId" }
        return entity
    }

    override fun findByIds(ids: Collection<UUID>): List<T> =
        ids
            .mapNotNull { id -> findFileById(id)?.let { readEntity(it) } }
            .filter { !it.metadata.removed }

    override fun findByParent(parentId: P): List<T> {
        val parentDir = rootDir.resolve(parentId.toString())
        if (!parentDir.exists()) return emptyList()

        return Files
            .list(parentDir)
            .filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
            .map { readEntity(it) }
            .filter { it != null && !it.metadata.removed }
            .map { it!! }
            .sorted(comparator)
            .toList()
    }

    override fun delete(id: UUID): Boolean {
        val file = findFileById(id) ?: return false
        val entity = readEntity(file) ?: return false
        if (entity.metadata.removed) return false

        entity.metadata.removed = true
        writeAtomic(file, entity)
        logger.debug { "[${entityClass.simpleName}Repository] Soft-deleted entity $id" }
        return true
    }

    override fun deleteByParent(parentId: P): Int {
        val parentDir = rootDir.resolve(parentId.toString())
        if (!parentDir.exists()) return 0

        var count = 0
        Files
            .list(parentDir)
            .filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
            .forEach { file ->
                val entity = readEntity(file)
                if (entity != null && !entity.metadata.removed) {
                    entity.metadata.removed = true
                    writeAtomic(file, entity)
                    count++
                }
            }
        logger.debug { "[${entityClass.simpleName}Repository] Soft-deleted $count entities under parent $parentId" }
        return count
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Scan the entire root directory to find a file by entity ID.
     * This is O(n) over all parent directories — acceptable because it is only
     * called by [findByIds] which is typically given small collections.
     *
     * Subclasses that know the parent ID can override [findFileById] to make
     * it O(1) by constructing the path directly.
     */
    protected open fun findFileById(id: UUID): Path? {
        if (!rootDir.exists()) return null
        return Files
            .walk(rootDir, 2)
            .filter { it.isRegularFile() && it.nameWithoutExtension == id.toString() }
            .findFirst()
            .orElse(null)
    }

    private fun readEntity(file: Path): T? =
        try {
            objectMapper.readValue(file.toFile(), entityClass)
        } catch (e: IOException) {
            logger.error(e) { "[${entityClass.simpleName}Repository] Failed to read entity from $file" }
            null
        }

    /**
     * Atomic write: serialise to a `.tmp` sibling file, then rename.
     * On POSIX file systems rename() is atomic, so readers never see partial JSON.
     */
    private fun writeAtomic(
        target: Path,
        entity: T,
    ) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            objectMapper.writeValue(tmp.toFile(), entity)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // ATOMIC_MOVE may not be supported on all file systems; fall back to non-atomic replace
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e2: IOException) {
                logger.error(e2) { "[${entityClass.simpleName}Repository] Failed to write entity to $target" }
                throw e2
            }
        }
    }

    companion object : KLogging()
}
