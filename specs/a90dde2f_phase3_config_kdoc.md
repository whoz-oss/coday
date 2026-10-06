# Plan — Phase 3 KDoc finalization for `AgentOsAdapterConfiguration`

## Objective

Add a concise KDoc comment to the Spring configuration class
`AgentOsAdapterConfiguration` describing **Phase 3** and the components it
assembles, then run the full SDLC chain (build, test, review, document) and
ensure build / test_1 / review_1 all pass.

This is a **documentation-only** change (a KDoc comment). No behavior,
signature, bean wiring, migration, or release-pipeline change is allowed.

## Scope

### File to touch (ONLY this file)

`factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsAdapterConfiguration.kt`

### Do NOT touch
- Any DB migration file (e.g. Flyway `V*.sql` / migration folders).
- The release pipeline.
- Any other Kotlin / TS source, test, or wiring.
- The existing bean method body, its parameters, or the class signature.

## Current state

The file already carries a class-level KDoc explaining the "always created"
cutover behavior of the bean, plus a one-line *"Phase 3 validation marker"*
note. The single `@Bean` factory method `agentOsExecutionAdapter(...)`
assembles the `DefaultAgentOsExecutionAdapter` from a `RestClient.Builder`,
`ProxyProperties`, `AgentOsAdapterProperties`, `ActiveCaseRegistry`, and an
`AgentOsSseClient` factory.

The Phase 3 components already present in the same package
(`io/whozoss/factory/adapter/agentos/`) are:
- `AgentRuntimeAdapter` — runtime-agnostic contract driving a case through its
  full lifecycle (create → turn start → observation → reconciliation →
  shutdown); defines `TurnToken` carrying a per-turn `baseline`.
- `ActiveCaseRegistry` — process-wide, thread-safe registry of every active
  AgentOS case driven by this Factory instance (used for status tracking and
  graceful shutdown).
- `TrustedCaseBinding` — trusted identities bound to a case at the Factory
  boundary (never from LLM arguments): caseId, attemptId, namespaceId,
  runtimeId, capabilityToken, environmentRef/revision, externalUserId.
- `AgentOsCaseShutdownHook` — `DisposableBean` that interrupts/kills every
  still-tracked case on Spring context close.
- `HighWaterMark` — durable per-turn baseline fencing out older case events.
- `DefaultAgentOsExecutionAdapter` — the durable SSE-bridge implementation of
  the adapter contract (the primary, mandatory execution driver).
- `VerdictDeriver` — derives the execution verdict
  (`AgentOsExecutionVerdict`) from observed case events.

## Change to make

In `AgentOsAdapterConfiguration.kt`, **replace / extend the existing
class-level KDoc** so it concisely describes Phase 3 and the components this
Spring configuration assembles. Keep it concise (a short paragraph plus a
bullet list of the assembled/related components). Preserve the useful existing
content about the bean being always created and the `enabled` flag selecting
the driver rather than gating the bean.

Suggested KDoc content (adapt wording; keep concise, no semicolons rule is for
code not comments, but keep style consistent with the file):

```kotlin
/**
 * Spring wiring of the AgentOS execution boundary (Phase 3).
 *
 * Phase 3 introduced the durable, runtime-agnostic agent-execution adapter and
 * its supporting lifecycle machinery. This configuration assembles those
 * collaborators into the single [agentOsExecutionAdapter] bean driving
 * `CapabilityExecutionService`:
 *
 * - [DefaultAgentOsExecutionAdapter] — durable SSE-bridge implementation of the
 *   [AgentRuntimeAdapter] contract (create / turn start / observation /
 *   reconciliation / shutdown of a case).
 * - [ActiveCaseRegistry] — process-wide, thread-safe registry of every active
 *   case driven by this Factory instance, used for status tracking and graceful
 *   shutdown ([AgentOsCaseShutdownHook]).
 * - [TrustedCaseBinding] — Factory-authority identities bound to each case,
 *   never sourced from LLM arguments.
 * - [HighWaterMark] — durable per-turn baseline fencing out older case events.
 * - [VerdictDeriver] — derives the [AgentOsExecutionVerdict] from observed
 *   case events.
 *
 * The bean is **always created** since the final cutover: the durable SSE bridge
 * is the primary, mandatory execution driver. `factory.adapter.agentos.enabled`
 * (default `true`) no longer gates the bean — it selects the driver: when set to
 * `false` the service explicitly falls back to the legacy
 * `HttpAgentOsProxyClient` polling path while still depending on this adapter
 * instance (which is then never invoked).
 */
```

Notes for the builder:
- Use `[Type]` KDoc references only for types resolvable from this file's
  package/imports (all the listed components live in the same package, so bare
  `[TypeName]` links resolve). If a reference does not resolve and triggers a
  warning, downgrade it to plain text rather than add an import.
- The old "Phase 3 validation marker: ... 566 tests ..." line may be dropped or
  kept — prefer dropping the stale hardcoded test count to avoid a misleading
  record, but this is optional and non-blocking.
- Do not alter imports unless strictly required; a KDoc change needs none.

## Verification

The factory runs the gated suite automatically; builder does not need to run it
unless debugging. For local confidence the builder may run:

- Build: `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`
- Test:  `pnpm nx affected -t test --base="$(cat /work/data/baseline)"`
- Lint:  `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`

For the Kotlin module directly (debugging only):
- `pnpm nx test factory-service` (or the Nx project name of the Kotlin service;
  confirm with `pnpm nx show projects | grep factory`).

Since this is a comment-only change, no test should change behavior; all unit
and integration tests must stay 100% green.

## Acceptance criteria

- `AgentOsAdapterConfiguration.kt` carries a concise KDoc describing Phase 3 and
  the assembled components.
- Only that one file changed; no migration or release-pipeline file touched.
- build, test_1, review_1 pass with SUCCESS; unit + integration tests 100%.

## Commit

Documentation-only change → use:

```
docs(agentos): describe Phase 3 wiring in AgentOsAdapterConfiguration
```
