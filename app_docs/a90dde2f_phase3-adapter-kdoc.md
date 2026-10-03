# Phase 3 KDoc for `AgentOsAdapterConfiguration`

## What changed and why

This change finalizes the Phase 3 documentation pass on the AgentOS execution
adapter in `factory-service`. The Phase 3 code itself — `AgentRuntimeAdapter`,
`ActiveCaseRegistry`, `TrustedCaseBinding`, `AgentOsCaseShutdownHook`,
`HighWaterMark`, `DefaultAgentOsExecutionAdapter`, `VerdictDeriver` — was
already in the codebase; what was missing was a description at the wiring point
of what Phase 3 actually assembled.

The change replaces the one-line class-level KDoc on
`AgentOsAdapterConfiguration` (*"Wiring of the AgentOS execution adapter."*)
with a concise block that names Phase 3 and enumerates the collaborators the
configuration brings together into the single `agentOsExecutionAdapter` bean
driving `CapabilityExecutionService`:

- `DefaultAgentOsExecutionAdapter` — durable SSE-bridge implementation of the
  `AgentRuntimeAdapter` contract (create / turn start / observation /
  reconciliation / shutdown of a case).
- `ActiveCaseRegistry` — process-wide, thread-safe registry of active cases,
  used for status tracking and graceful shutdown via `AgentOsCaseShutdownHook`.
- `TrustedCaseBinding` — Factory-authority identities bound to each case,
  never sourced from LLM arguments.
- `HighWaterMark` — durable per-turn baseline fencing out older case events.
- `VerdictDeriver` — derives the `AgentOsExecutionVerdict` from observed case
  events.

The pre-existing paragraph about the bean being **always created** since the
final cutover (the durable SSE bridge as the primary, mandatory execution
driver) is preserved unchanged.

**Behavioral impact: none.** This is a comment-only (KDoc) change — no code,
imports, bean wiring, DB migration, or release-pipeline file was touched.

## Files that carry the change

- `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterConfiguration.kt`
  — the KDoc rewrite (18 insertions / 1 deletion in the class header comment).
- `specs/a90dde2f_phase3_config_kdoc.md` — the SDLC plan for this change
  (scope, the exact suggested KDoc content, verification commands, acceptance
  criteria, and the intended `docs(agentos): ...` commit message).

## How to verify

Open the class header of
`factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterConfiguration.kt`:
it should read *"Spring wiring of the AgentOS execution boundary (Phase 3)."*
followed by the bullet list above and the preserved cutover paragraph.

Since no behavior changed, the existing unit and integration tests remain the
verification gate:

```
pnpm nx test factory-service
```

All referenced KDoc links (`[DefaultAgentOsExecutionAdapter]`,
`[ActiveCaseRegistry]`, etc.) resolve to types in the same package
(`io.whozoss.factory.adapter.agentos`), so no imports were needed.
