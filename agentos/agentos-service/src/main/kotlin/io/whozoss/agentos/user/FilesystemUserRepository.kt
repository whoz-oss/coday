package io.whozoss.agentos.user

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * File-system implementation of [UserRepository].
 *
 * Storage layout: `<dataDir>/users/all/<userId>.json`
 *
 * Supplies an O(1) [findFileByIdFn] that constructs the path directly from
 * the fixed parent directory, avoiding the default O(n) tree scan.
 *
 * [findByExternalId] performs a linear scan of the parent directory — acceptable
 * for filesystem persistence where the user count is bounded. A future database
 * implementation can replace this with an indexed query.
 */
class FilesystemUserRepository(
    private val dataDir: Path,
    private val objectMapper: ObjectMapper,
) : UserRepository,
    EntityRepository<User, String> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("users"),
        entityClass = User::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { UserRepository.USER_PARENT_KEY },
        comparator = compareBy { it.email },
        findFileByIdFn = { id ->
            val file = dataDir.resolve("users").resolve(UserRepository.USER_PARENT_KEY).resolve("$id.json")
            file.takeIf { it.exists() }
        },
    ) {
    private val parentDir: Path
        get() = dataDir.resolve("users").resolve(UserRepository.USER_PARENT_KEY)

    override fun findByExternalId(externalId: String): User? {
        if (!parentDir.exists()) return null
        val files = Files.list(parentDir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.fileName.toString().endsWith(".json") }
                .toList()
        }
        return files
            .mapNotNull { file ->
                try {
                    objectMapper.readValue(file.toFile(), User::class.java)
                } catch (_: Exception) {
                    null
                }
            }
            .firstOrNull { !it.metadata.removed && it.externalId == externalId }
    }
}
