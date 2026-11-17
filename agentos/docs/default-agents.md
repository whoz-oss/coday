# Default agents

Every personal namespace (see `agentos.personal-namespace.enabled`) is created with two agents, both owned by the
namespace and deployed on it. Neither is a platform agent, so they never clash with an agent of the same name in
another namespace.

| Agent | Role | Model | Setting |
|---|---|---|---|
| `Coday` | The friendly default agent: welcomes the user, explains AgentOS, proposes configuration changes, delegates | `BIG` | `agentos.defaults.coday-agent.enabled` |
| `Searcher` | Read-only search sub-agent that `Coday` delegates wide searches to | `BIG` | `agentos.defaults.searcher-agent.enabled` |

Both are created by `CodayAgentSeeder` / `SearcherAgentSeeder` through `NamespaceAgentCreator`, when the namespace
is provisioned (`PersonalNamespaceProvisioner`), not at startup.

## Existence, not content

A seeder only acts when the namespace owns no agent of that name (case insensitive, a disabled one counts). An
agent the namespace already owns, ours or one a user made, belongs to the administrators of the namespace and is
never rewritten. Seeding is create-only: switching a setting back to `false` does not delete an agent already
created. Consequence: a change to the shipped instructions or integrations reaches new namespaces only.

## Why a search sub-agent

When `Coday` has to find something, the listing, reading and dead ends of the search would fill its own context.
`Coday` delegates it instead (`DELEGATE__delegate`): the search runs in a sub-case of `Searcher`, and only the last
message of that sub-case, the report, comes back. This is the same pattern as the Explore agent of Claude Code, the
`explore` sub-agent of OpenCode and the `delegate_task` of Hermes.

`Searcher` is read-only by construction, not by instruction. Its integrations list only the tools that read and
search (`listFiles`, `readFile`, `readDocument`, `readSpreadsheet`, `readAsImage`, `searchFiles`) in the files of the
conversation (`CASE_FILE_EXCHANGE`) and the shared folder of the namespace (`NAMESPACE_FILE_EXCHANGE`), and
`AGENTOS_CONFIG` limited to `read`. It has no `subAgents`: delegation stops at one level on this path.

## What the delegation tool does for the model

- Lists the agents it may delegate to as `- name: description`, sorted by name (stable prompt prefix). Write a
  useful `description` on any agent that others may delegate to.
- Returns, per delegation, the last message of the sub-agent, cut in the middle past 24 000 characters. The full
  answer stays in the sub-case, which the delegating agent can resume through its `subCaseId`.
- Accepts at most 10 delegations per call.

`Coday` is created with `subAgents: ["*"]`: it can delegate to every agent it can reach.
