package io.whozoss.agentos.messaging

import io.whozoss.agentos.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [MessagingBotRepository]. The token is encrypted before it is written and
 * decrypted when read, as [io.whozoss.agentos.credential.Neo4jCredentialRepository] does for credentials; deletes
 * are hard deletes. A bot that cannot be read is left out of every read.
 */
open class Neo4jMessagingBotRepository(
    private val neo4jRepository: MessagingBotNodeNeo4jRepository,
    private val encryptor: FieldEncryptor,
) : MessagingBotRepository {
    @Transactional
    open override fun save(bot: MessagingBot): MessagingBot =
        neo4jRepository
            .save(MessagingBotNode.fromDomain(bot, encryptor.encrypt(bot.token), bot.appToken?.let(encryptor::encrypt)))
            .toDomain()

    @Transactional(readOnly = true)
    open override fun findById(id: UUID): MessagingBot? = neo4jRepository.findByIdOrNull(id.toString())?.readable()

    @Transactional(readOnly = true)
    open override fun findAll(): List<MessagingBot> = neo4jRepository.findAll().mapNotNull { it.readable() }

    @Transactional(readOnly = true)
    open override fun findByRole(role: MessagingBotRole): List<MessagingBot> =
        neo4jRepository.findByRole(role.name).mapNotNull { it.readable() }

    @Transactional(readOnly = true)
    open override fun findByBotKey(botKey: String): MessagingBot? = neo4jRepository.findByBotKey(botKey)?.readable()

    @Transactional(readOnly = true)
    open override fun findBySlotKey(slotKey: String): MessagingBot? = neo4jRepository.findBySlotKey(slotKey)?.readable()

    @Transactional(readOnly = true)
    open override fun findAgentBotsByNamespace(namespaceId: UUID): List<MessagingBot> =
        neo4jRepository.findAgentBotsByNamespaceId(namespaceId.toString()).mapNotNull { it.readable() }

    @Transactional(readOnly = true)
    open override fun findByAgentId(agentId: UUID): List<MessagingBot> =
        neo4jRepository.findByAgentId(agentId.toString()).mapNotNull { it.readable() }

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository.existsById(id.toString()).also { exists ->
            if (exists) {
                neo4jRepository.deleteById(id.toString())
                logger.debug { "[Neo4jMessagingBotRepository] Deleted bot $id" }
            }
        }

    private fun MessagingBotNode.toDomain(): MessagingBot =
        toDomain(encryptor.decrypt(encryptedToken), encryptedAppToken?.let(encryptor::decrypt))

    /**
     * The bot, or null when it cannot be read (a token encrypted with another key, a platform gone): it is left
     * out of every read, and logged, so that the others still run and list.
     */
    private fun MessagingBotNode.readable(): MessagingBot? =
        runCatching { toDomain() }
            .onFailure { logger.error(it) { "[Neo4jMessagingBotRepository] bot $id (@$username) cannot be read, left out" } }
            .getOrNull()

    companion object : KLogging()
}
