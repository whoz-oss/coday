package io.whozoss.agentos.entity

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.entity.Entity
import mu.KLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
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
 * Writes are atomic: JSON is written to a `.tmp` sibling first, then renamed,
 * so a crash mid-write never corrupts an existing file.
 *
 * Soft-delete sets `metadata.removed = true` and re-persists the file.
 *
 * @param T                Entity type
 * @param P                Parent identifier type (must have a stable [Any.toString])
 * @param rootDir          Root directory for this repository
 * @param entityClass      Reified class used by Jackson for deserialisation
 * @param objectMapper     Jackson mapper configured with Kotlin module
 * @param parentIdExtractor Extracts the parent ID from an entity
 * @param comparator       Orders entities returned by [findByParent]
 * @param findFileByIdFn   Optional override for locating a file by entity ID.
 *                         Defaults to an O(n) tree scan; supply an O(1) lambda
 *                         when the parent directory can be derived from the ID.
 */
class FilesystemEntityRepository<T : Entity, P>(
    private val rootDir: Path,
    private val entityClass: Class<T>,
    private val objectMapper: ObjectMapper,
    private val parentIdExtractor: (T) -> P,
    private val comparator: Comparator<T>,
    private val findFileByIdFn: ((UUID) -> Path?)? = null,
) : EntityRepository<T, P> {
    init {
        Files.createDirectories(rootDir)
        logger.info { "[${entityClass.simpleName}Repository] Initialised with rootDir=$rootDir" }
    }

    override fun save(entity: T): T {
        val parentId = parentIdExtractor(entity)
        val parentDir = rootDir.resolve(parentId.toString())
        Files.createDirectories(parentDir)
        val file = parentDir.resolve("${entity.metadata.id}.json")
        writeAtomic(file, entity)
        logger.debug { "[${entityClass.simpleName}Repository] Saved ${entity.metadata.id} under parent $parentId" }
        return entity
    }

    override fun findByIds(ids: Collection<UUID>): List<T> =
        ids
            .mapNotNull { id -> findFileById(id)?.let { readEntity(it) } }
            .filter { !it.metadata.removed }

    override fun findByParent(parentId: P): List<T> =
        listEntityFiles(parentId)
            .mapNotNull { readEntity(it) }
            .filter { !it.metadata.removed }
            .sortedWith(comparator)

    override fun delete(id: UUID): Boolean {
        val file = findFileById(id)
        require(file != null) { "No data stored for id $id" }
        val entity = readEntity(file)
        check(entity != null) { "Cannot read data stored for id $id" }
        return if (entity.metadata.removed) {
            false
        } else {
            entity.metadata.removed = true
            writeAtomic(file, entity)
            logger.debug { "[${entityClass.simpleName}Repository] Soft-deleted $id" }
            true
        }
    }

    override fun deleteByParent(parentId: P): Int {
        val count =
            listEntityFiles(parentId)
                .mapNotNull { file -> readEntity(file)?.takeIf { !it.metadata.removed }?.let { file to it } }
                .onEach { (file, entity) ->
                    entity.metadata.removed = true
                    writeAtomic(file, entity)
                }.count()
        logger.debug { "[${entityClass.simpleName}Repository] Soft-deleted $count entities under parent $parentId" }
        return count
    }

    /** Lists all non-tmp JSON files directly under the parent directory. */
    private fun listEntityFiles(parentId: P): List<Path> {
        val parentDir = rootDir.resolve(parentId.toString())
        if (!parentDir.exists()) return emptyList()
        return Files.list(parentDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
                .toList()
        }
    }

    private fun findFileById(id: UUID): Path? =
        findFileByIdFn?.invoke(id) ?: run {
            if (!rootDir.exists()) return null
            Files.walk(rootDir, 2).use { stream -> // rootDir, then parent dir, then entity file
                stream
                    .filter { it.isRegularFile() && it.nameWithoutExtension == id.toString() }
                    .findFirst()
                    .orElse(null)
            }
        }

    private fun readEntity(file: Path): T? =
        try {
            objectMapper.readValue(file.toFile(), entityClass)
        } catch (e: IOException) {
            logger.error(e) { "[${entityClass.simpleName}Repository] Failed to read entity from $file" }
            null
        }

    private fun writeAtomic(
        target: Path,
        entity: T,
    ) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            objectMapper.writeValue(tmp.toFile(), entity)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
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
