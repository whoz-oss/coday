package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.caseEvent.FilesystemCaseEventRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.FilesystemCaseRepository
import io.whozoss.agentos.namespace.FilesystemNamespaceRepository
import io.whozoss.agentos.namespace.NamespaceRepository
import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * Registers file-system repository beans.
 *
 * Active by default (when `agentos.persistence.mode` is absent or set to `filesystem`).
 * Deactivated by setting `agentos.persistence.mode=in-memory`, which lets the
 * in-memory [org.springframework.stereotype.Repository]-annotated classes take over instead.
 */
@Configuration
@EnableConfigurationProperties(PersistenceConfigProperties::class)
@ConditionalOnProperty(
    name = ["agentos.persistence.mode"],
    havingValue = "filesystem",
    matchIfMissing = true,
)
class PersistenceConfiguration(
    private val props: PersistenceConfigProperties,
) {
    private val dataDir: Path
        get() = Path.of(props.dataDir).toAbsolutePath().normalize()

    @Bean
    fun filesystemCaseRepository(objectMapper: ObjectMapper): CaseRepository {
        logger.info { "[Persistence] FilesystemCaseRepository -> ${dataDir.resolve("cases")}" }
        return FilesystemCaseRepository(dataDir, objectMapper)
    }

    @Bean
    fun filesystemCaseEventRepository(objectMapper: ObjectMapper): CaseEventRepository {
        logger.info { "[Persistence] FilesystemCaseEventRepository -> ${dataDir.resolve("case-events")}" }
        return FilesystemCaseEventRepository(dataDir, objectMapper)
    }

    @Bean
    fun filesystemNamespaceRepository(objectMapper: ObjectMapper): NamespaceRepository {
        logger.info { "[Persistence] FilesystemNamespaceRepository -> ${dataDir.resolve("namespaces")}" }
        return FilesystemNamespaceRepository(dataDir, objectMapper)
    }

    companion object : KLogging()
}
