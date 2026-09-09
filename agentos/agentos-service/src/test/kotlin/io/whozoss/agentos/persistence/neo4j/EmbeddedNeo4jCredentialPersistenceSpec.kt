package io.whozoss.agentos.persistence.neo4j

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Runs the [AbstractCredentialPersistenceSpec] contract against the embedded Neo4j
 * engine using the Neo4j test harness (`embedded-neo4j` persistence mode).
 *
 * Encryption uses the fixed test key/salt from `application-test.yml` (not real secrets),
 * so [io.whozoss.agentos.encryption.SpringFieldEncryptor] is active. The persistence
 * contract is verified end-to-end including the encrypt/decrypt round-trip through
 * [io.whozoss.agentos.credential.Neo4jCredentialRepository].
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jCredentialPersistenceSpec : AbstractCredentialPersistenceSpec()
