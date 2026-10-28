package io.whozoss.factory.oracle.registry

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.oracle.domain.DuplicateOracleIdException
import io.whozoss.factory.oracle.domain.OracleDefinition
import io.whozoss.factory.oracle.domain.OracleDefinitionValidator
import io.whozoss.factory.oracle.domain.OraclePathIdentityMismatchException
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loads and validates the oracle-definition catalogue at application startup.
 *
 * Port of `OracleDefinitionRegistryCore` (`factory/src/application/oracle/`).
 * The root folder is configurable via `factory.oracle.definitions-root` (or the
 * `FACTORY_ORACLE_DEFINITIONS_ROOT` environment variable, defaulting to
 * `factory/oracles`).
 *
 * Invariants enforced while loading, in file-name order:
 *   1. every `.json` file parses and validates as an oracle definition
 *      ([OracleDefinitionValidator] -> `INVALID_ORACLE_DEFINITION`);
 *   2. a file's base name must equal the definition's `<id>@<version>`
 *      identity, otherwise `ORACLE_PATH_IDENTITY_MISMATCH`;
 *   3. two files may not declare the same `id`, otherwise `DUPLICATE_ORACLE_ID`.
 *
 * A missing root folder is not an error: the registry simply stays empty (this
 * is what lets the service boot where no oracle catalogue is mounted, e.g. the
 * OpenAPI generation profile).
 */
@Component
class OracleDefinitionRegistry(
    @param:Value("\${factory.oracle.definitions-root:\${FACTORY_ORACLE_DEFINITIONS_ROOT:factory/oracles}}")
    private val definitionsRoot: String,
    private val objectMapper: ObjectMapper,
) {

    private val logger = KotlinLogging.logger {}

    @Volatile
    private var definitions: Map<String, OracleDefinition> = emptyMap()

    /** Load (or reload) the catalogue. Idempotent; safe to call in tests. */
    @PostConstruct
    fun initialize() {
        val root: Path = Paths.get(definitionsRoot).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) {
            logger.warn { "Oracle definitions root '$root' does not exist; oracle registry is empty" }
            definitions = emptyMap()
            return
        }

        val files = Files.newDirectoryStream(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sortedBy { it.fileName.toString() }
                .toList()
        }

        val next = LinkedHashMap<String, OracleDefinition>()
        for (file in files) {
            val content = Files.readString(file)
            val definition = OracleDefinitionValidator.validate(content, objectMapper)
            val identity = OracleDefinitionValidator.identityFromFileName(file.fileName.toString())
            if (identity != definition.identity) {
                throw OraclePathIdentityMismatchException(
                    "Oracle definition file '${file.fileName}' does not match '${definition.identity}'",
                )
            }
            if (next.containsKey(definition.id)) {
                throw DuplicateOracleIdException("Duplicate oracle id '${definition.id}'")
            }
            next[definition.id] = definition
        }

        definitions = next
        logger.info { "Loaded ${next.size} oracle definition(s) from $root" }
    }

    /** Every loaded definition, in file-name order. */
    fun list(): List<OracleDefinition> = definitions.values.toList()

    /** The definition identified by `id`, or `null` when absent. */
    fun get(id: String): OracleDefinition? = definitions[id]
}
