package io.whozoss.agentos.sdk.tool

/**
 * Declares how a [StandardTool] participates in the user-confirmation flow (WZ-31596).
 *
 * The orchestrator reads this value once per tool call to decide whether to gate
 * execution behind an explicit user prompt or to proceed directly.
 *
 * - [NONE]: no confirmation flow — tool executes immediately (default).
 * - [INFER]: confirmation is required, but the orchestrator may skip the explicit prompt
 *   when [io.whozoss.agentos.agent.ConfirmationManager.shouldConfirm] detects that the
 *   user already implicitly authorised the action in the conversation history. Suitable
 *   for write operations that are recoverable or low-risk.
 * - [EVERY_TIME]: confirmation is required on every call; implicit consent is never
 *   trusted. Use this for irreversible side-effects such as file deletion or external
 *   API mutations that cannot be undone.
 */
enum class ConfirmationMode {
    NONE,
    INFER,
    EVERY_TIME,
}
