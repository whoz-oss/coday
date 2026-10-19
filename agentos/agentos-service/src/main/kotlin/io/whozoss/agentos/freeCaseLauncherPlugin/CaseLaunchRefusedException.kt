package io.whozoss.agentos.freeCaseLauncherPlugin

/**
 * A launch refused by a business rule of [CaseLaunchService], decided before anything is written: nothing was
 * created. [reason] says which rule, and is the stable `errorType` the `FreeCaseLauncher` tool reports;
 * [message] is safe to show to the LLM.
 *
 * It is how the service says "no" to its caller, the tool, which catches it and turns it into its own error
 * result. It is an exception so that the helpers of the service, which resolve something (the target user, the
 * agent), can return it directly instead of "it, or a refusal" for each caller to unwrap. There are of course other
 * ways to model this (a result type threaded through the helpers, a sealed hierarchy of refusals instead of
 * [CaseLaunchRefusal], an exception subclass per cause).
 *
 * Any other failure of the service (an infrastructure failure, a bug) is not a refusal: it is not this exception,
 * and nothing catches it but the agent layer.
 *
 * It carries no stack trace: it is control flow, not a failure.
 */
class CaseLaunchRefusedException(
    val reason: CaseLaunchRefusal,
    override val message: String,
) : RuntimeException(message, null, false, false)
