package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.caseEvent.QuestionType
import java.util.UUID

/**
 * Signals that the current agent run must suspend until the user answers a Factory
 * human checkpoint.
 *
 * The AgentOS runtime represents this control-flow signal with
 * `io.whozoss.agentos.agent.AgentInterrupt.AwaitAnswer`. That class lives in
 * `agentos-service`, not in the SDK, and must never be duplicated in the plugin JAR
 * (duplicating it would produce two distinct classes across the PF4J classloader
 * boundary and the host's typed `catch` would miss it).
 *
 * The Factory checkpoint itself never crosses the host boundary: the tool registers it in
 * [FactoryBridgeServices.pendingCheckpoints] before suspending, and [FactoryAnswerInterceptor]
 * consumes it by case id when the answer arrives.
 *
 * The plugin therefore routes the signal through [FactoryAnswerAwaiter]. The default
 * [HostAgentInterruptAwaiter] resolves and raises the host's own `AgentInterrupt.AwaitAnswer`
 * instance by reflection. When the host runtime is not present (unit tests, standalone
 * plugin validation), it falls back to [FactoryAwaitAnswerInterrupt], which carries the
 * same payload and is safe to throw from plugin code.
 */
data class FactoryAwaitAnswer(
    val question: String,
    val options: List<String>? = null,
    val questionType: QuestionType = QuestionType.FREE_TEXT,
    val userId: UUID? = null,
)

/**
 * Renders the awaited-answer control-flow signal. Implementations throw; they never return.
 */
fun interface FactoryAnswerAwaiter {
    fun awaitAnswer(await: FactoryAwaitAnswer): Nothing
}

/**
 * Plugin-local control-flow signal used when the AgentOS host runtime is unavailable.
 */
class FactoryAwaitAnswerInterrupt(
    val await: FactoryAwaitAnswer,
) : RuntimeException("Awaiting user answer")

/**
 * Default [FactoryAnswerAwaiter].
 *
 * Prefers the host runtime's `AgentInterrupt.AwaitAnswer` so that AgentOS's own
 * typed `catch (e: AgentInterrupt)` block suspends the run and emits the
 * [io.whozoss.agentos.sdk.caseEvent.QuestionEvent] carrying the checkpoint.
 * Falls back to [FactoryAwaitAnswerInterrupt] when the host class cannot be resolved.
 */
object HostAgentInterruptAwaiter : FactoryAnswerAwaiter {
    private const val HOST_INTERRUPT_CLASS = "io.whozoss.agentos.agent.AgentInterrupt\$AwaitAnswer"

    override fun awaitAnswer(await: FactoryAwaitAnswer): Nothing {
        val hostInterrupt = runCatching {
            val type = Class.forName(HOST_INTERRUPT_CLASS)
            val constructor =
                type.getConstructor(
                    String::class.java,
                    List::class.java,
                    QuestionType::class.java,
                    UUID::class.java,
                )
            constructor.newInstance(
                await.question,
                await.options,
                await.questionType,
                await.userId,
            ) as Throwable
        }.getOrNull()
        throw hostInterrupt ?: FactoryAwaitAnswerInterrupt(await)
    }
}
