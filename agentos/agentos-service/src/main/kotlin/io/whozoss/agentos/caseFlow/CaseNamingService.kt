package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.chat.forUtilityCall
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.CaseUpdatedEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import jakarta.annotation.PreDestroy
import io.whozoss.agentos.namespace.NamespaceCaseListUpdateBroadcaster
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicBoolean
import mu.KLogging
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Generates a short title for a [Case] from its most recent user message(s).
 *
 * Called by [CaseServiceImpl] whenever [CaseServiceImpl.triggerNamingIfNeeded] decides
 * a naming attempt is warranted. This service only generates and persists the title —
 * it does not decide whether naming should happen.
 *
 * The transcript fed to the LLM is built from the last [MAX_USER_MESSAGES_FOR_NAMING]
 * user [MessageEvent]s. Using the last messages (rather than the first) ensures the
 * title is always derived from the most contextually relevant content, including the
 * blank-title recovery path where earlier messages may already be stale.
 */
@Service
class CaseNamingService(
    private val aiModelService: AiModelService,
    private val aiProviderService: AiProviderService,
    private val chatClientProvider: ChatClientProvider,
    private val caseRepository: CaseRepository,
    private val caseListUpdateBroadcaster: NamespaceCaseListUpdateBroadcaster? = null,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
    @param:org.springframework.beans.factory.annotation.Value("\${agentos.case.naming-call-timeout-ms:30000}")
    private val namingCallTimeoutMs: Long = 30_000,
) {
    private val namingExecutor = java.util.concurrent.ThreadPoolExecutor(
        0, 2, 60, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        { task -> Thread(task, "case-naming-llm").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepActive = AtomicBoolean(false)

    /** Sweep persisted intents after commit and on restart, without blocking case creation. */
    @Scheduled(fixedDelayString = "\${agentos.case.naming-sweep-ms:5000}")
    fun sweepPendingNaming() {
        if (sweepActive.compareAndSet(false, true)) {
            scope.launch {
                try {
                    caseRepository.purgeExpiredNaming(java.time.Instant.now(clock).minusSeconds(NAMING_RETENTION_SECONDS), NAMING_BATCH_SIZE)
                    caseRepository.findPendingNaming(NAMING_BATCH_SIZE).forEach { pending ->
                        val now = java.time.Instant.now(clock)
                        val claimed = caseRepository.claimNaming(pending, UUID.randomUUID().toString(), now, now.plusSeconds(600))
                        val instructions = claimed?.namingInstructions
                        if (claimed != null && instructions != null) {
                            runCatching {
                                nameFromInstructions(claimed, instructions, claimed.namingPrefix) {
                                    caseListUpdateBroadcaster?.publishCaseUpdated(pending.namespaceId)
                                }
                            }.onFailure { logger.warn { "[CaseNaming] Could not process pending naming for case ${pending.id}" } }
                        }
                    }
                } finally {
                    sweepActive.set(false)
                }
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        namingExecutor.shutdownNow()
    }
    /**
     * Attempts to generate and persist a title for [case] from [events].
     *
     * Silently skips when no default AI model is configured for the namespace,
     * or when the LLM call fails. Emits a transient [CaseUpdatedEvent] via
     * [emitEvent] so connected SSE clients refresh immediately.
     */
    suspend fun nameCase(
        case: Case,
        events: List<CaseEvent>,
        emitEvent: (CaseEvent) -> Unit,
    ) {
        val transcript = events
            .filterIsInstance<MessageEvent>()
            .filter { it.actor.role == ActorRole.USER }
            .takeLast(MAX_USER_MESSAGES_FOR_NAMING)
            .joinToString("\n") { event ->
                event.content
                    .filterIsInstance<MessageContent.Text>()
                    .joinToString(" ") { it.content }
                    .trim()
            }.trim()

        if (transcript.isBlank()) {
            logger.debug { "[CaseNaming] No text content in user messages for case ${case.id}, skipping" }
            return
        }

        nameFromInstructions(case, transcript, null, emitEvent, singleLine = false)
    }

    /** Names a case from supplied instructions, retaining an optional Jira prefix. */
    suspend fun nameFromInstructions(
        case: Case,
        instructions: String,
        prefix: String?,
        emitEvent: (CaseEvent) -> Unit,
    ) = nameFromInstructions(case, instructions, prefix, emitEvent, singleLine = true)

    private suspend fun nameFromInstructions(
        case: Case,
        instructions: String,
        prefix: String?,
        emitEvent: (CaseEvent) -> Unit,
        singleLine: Boolean,
    ) {
        val transcript = instructions.trim()
        if (transcript.isBlank() || case.titleEditGeneration != null) return
        val fallbackTitle = if (singleLine) transcript.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_FALLBACK_TITLE_LENGTH)
            else transcript.take(MAX_FALLBACK_TITLE_LENGTH)
        val title = generateBoundedTitle(transcript, case) ?: fallbackTitle
        val titleText = if (singleLine) title?.lineSequence()?.firstOrNull()?.trim() else title
        val resolvedTitle = listOfNotNull(prefix?.takeIf { it.isNotBlank() }, titleText?.takeIf { it.isNotEmpty() })
            .joinToString(" ").takeIf { it.isNotEmpty() } ?: return
        // Persist Factory intent under a database-time lease fence. Ordinary naming retains optimistic writes.
        val updated = if (case.namingInstructions != null) {
            caseRepository.completeNaming(case, resolvedTitle)
        } else {
            caseRepository.findByIds(setOf(case.id), withRemoved = true).firstOrNull()?.let { fresh ->
                saveGeneratedTitle(case, fresh, resolvedTitle, CHANGE_ATTEMPTS)
            }
        }
        if (updated != null) {
            logger.info { "[CaseNaming] Case ${case.id} titled" }
            emitEvent(
                CaseUpdatedEvent(
                    namespaceId = updated.namespaceId,
                    caseId = updated.id,
                    title = resolvedTitle,
                ),
            )
        }
    }

    /** Never retry a generated title over a changed title, including after an optimistic conflict. */
    private fun saveGeneratedTitle(original: Case, current: Case, title: String, attemptsLeft: Int): Case? =
        if (original.titleEditGeneration != null || current.metadata.removed ||
            current.namingInstructions != original.namingInstructions ||
            (original.namingInstructions != null && (current.namingLeaseOwner != original.namingLeaseOwner ||
                current.namingLeaseUntil?.isAfter(java.time.Instant.now(clock)) != true)) ||
            current.title != original.title || current.titleEditGeneration != original.titleEditGeneration
        ) {
            null
        } else {
            try {
                caseRepository.save(current.copy(title = title, namingInstructions = null, namingPrefix = null, namingLeaseOwner = null, namingLeaseUntil = null))
            } catch (error: OptimisticLockingFailureException) {
                if (attemptsLeft <= 1) {
                    throw error
                } else {
                    caseRepository.findByIds(listOf(original.id), withRemoved = true).firstOrNull()?.let { fresh ->
                        saveGeneratedTitle(original, fresh, title, attemptsLeft - 1)
                    }
                }
            }
        }

    /** Bounds waiting and the number of blocked provider threads; late results never write state. */
    private suspend fun generateBoundedTitle(transcript: String, case: Case): String? = withContext(Dispatchers.IO) {
        val future = try {
            namingExecutor.submit<String?> {
                val model = aiModelService.findAiModel(case.namespaceId)
                val provider = model?.let { aiProviderService.getById(it.aiProviderId) }
                if (model == null || provider == null) null else generateTitle(transcript, case.id, model, provider)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            null
        }
        if (future == null) {
            logger.warn { "[CaseNaming] Provider capacity exhausted for case ${case.id}; using fallback" }
            null
        } else {
            try {
                future.get(namingCallTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                logger.warn { "[CaseNaming] Provider timeout for case ${case.id}; using fallback" }
                null
            } catch (interrupted: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: java.util.concurrent.ExecutionException) {
                logger.warn { "[CaseNaming] Provider failed for case ${case.id}; using fallback" }
                null
            }
        }
    }

    private fun generateTitle(
        transcript: String,
        caseId: UUID,
        model: io.whozoss.agentos.sdk.aiProvider.AiModel,
        provider: io.whozoss.agentos.sdk.aiProvider.AiProvider,
    ): String? {
        val prompt = """
            Given the following user message(s) from a conversation, reply with ONLY a short title
            (maximum 60 characters, no quotes, no punctuation at the end).
            The title must be in the same language as the message(s).

            Message(s):
            <usermessage>
            $transcript
            </usermessage>

            Title:
        """.trimIndent()

        return runCatching {
            val chatClient = chatClientProvider.getChatClient(model.forUtilityCall(), provider, caseId.toString())
            chatClient
                .prompt(Prompt(UserMessage(prompt)))
                .call()
                .content()
                ?.trim()
                ?.trimEnd('.', '!', '?')
                ?.take(MAX_TITLE_LENGTH)
                ?.takeUnless { it.isBlank() }
                ?.also { logger.debug { "[CaseNaming] LLM generated title for case $caseId" } }
                ?: run {
                    logger.warn { "[CaseNaming] LLM returned blank title for case $caseId" }
                    null
                }
        }.onFailure {
            logger.warn { "[CaseNaming] LLM call failed for case $caseId" }
        }.getOrNull()
    }

    companion object : KLogging() {
        /** Number of recent user messages fed to the LLM for title generation. */
        const val MAX_USER_MESSAGES_FOR_NAMING = 3
        private const val NAMING_BATCH_SIZE = 20
        private const val NAMING_RETENTION_SECONDS = 24L * 60 * 60
        private const val MAX_TITLE_LENGTH = 60
        private const val MAX_FALLBACK_TITLE_LENGTH = 50
    }
}
