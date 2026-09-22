# Usage collection and cost confirmation

Agent execution now connects model pricing (#1305), usage persistence and aggregates (#1309), and configurable thresholds (#1301). It replaces the runtime portion of #1281 without introducing a second pricing or usage model.

## Setup

1. Configure model prices using the existing `AiModel.pricing` fields, per million tokens: input, output, cache read and cache write. Use one consistent currency across the platform. Historical records retain the estimate calculated when the call ran.
2. Configure `agentos.limits.run-cost-threshold` (environment variable `AGENTOS_LIMITS_RUN_COST_THRESHOLD`) or a namespace/case override. Resolution remains case → namespace ancestry → platform. Null inherits; if the entire chain is unset there is no monetary gate. Existing iteration guards remain independent.
3. Deploy the matching SDK, service and generated API client/UI together. No additional Copilot provider configuration shape is introduced by this PR; prices and thresholds must be populated through the configuration introduced by the prerequisite PRs.

## Runtime behavior

Every provider request made through an agent's chat client is measured, including compression, structured responses and AgentSimple tool rounds. Anthropic and OpenAI-compatible native metadata provide separate cache counters. Missing prices or unsupported native metadata keep cost unknown; available tokens are still retained. The UI reports a known lower bound alongside unknown usage, never a complete zero estimate.

A run's cost window begins at the latest human message in its case. Subsequent agent selections and redirects share that window. Child usage also contributes to ancestor windows, so delegating work cannot evade the parent threshold.

Before another provider request or an automatic tool round, the runtime checks known cost against every applicable threshold. At or above a threshold it waits for an explicit user decision. **Continue doubles that case's threshold** and persists the absolute value; the suspended execution resumes without replaying its prompt or tools. A duplicate confirmation cannot double twice. If one response overshot more than twice the threshold, further doublings each require a separate confirmation. A threshold of zero requires an explicit positive edit before continuing. An explicit higher edit while paused is honored by the confirmation.

Stop interrupts the case and its active descendants and releases their cost waits. Stopping one child does not release an ancestor confirmation for its siblings. Time spent awaiting cost confirmation is excluded from the delegation execution deadline. Read permission permits monitoring; write permission is required to continue or stop. Each paused descendant is listed in the parent conversation and confirmed under that descendant's own write permission.

These are estimates checked **between requests**, not a hard billing cap: a request already running, concurrent delegated calls or unknown prices can exceed the displayed threshold. The threshold is snapshotted for an active run; an edit is otherwise picked up by the next run. Monetary confirmation does not alter the separate iteration guards.

## Monitoring and persistence

- Each conversation displays the current run's known cost, threshold, live tokens, recorded conversation totals, and pending confirmations (including delegated work).
- Namespace **Usage and costs** displays persisted totals by agent and model over an inclusive UTC date range. The existing namespace-admin permission governs these aggregates.
- An `AgentFinishedEvent` carries the invocation's accumulated usage. Analytical records are written when the invocation finishes, including partial usage on interruption or provider failure. Priced and unpriced calls are grouped separately so unknown usage does not erase known costs. Namespace reports include completed writes; active usage is visible in the conversation panel.
- Reloading the browser can reconnect to the same live pause. A service restart loses in-flight execution and uncommitted live usage; it does not resume a suspended provider call. The next execution rebuilds its cost window from persisted records. Deployments with multiple service instances must route a case and its run-cost controls to the instance owning its runtime, as for the existing execution controls.

## Validation

Focused backend tests exercise native provider accounting, cache-only responses, cancellation, tool-round pausing, explicit doubling, simultaneous ancestor/child gates, independent child stop, and existing agent/case behavior. HTTP integration tests use real permissions and embedded Neo4j to verify read/write access, durable threshold doubling, duplicate rejection, and rebuilding known plus unknown consumption. Rendered Angular tests cover confirmation controls, stale requests, errors, date filters and existing case editing.
