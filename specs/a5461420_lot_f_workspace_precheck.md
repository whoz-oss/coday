# Lot F Plan: Workspace Pre-Check and Run Worktree Guarantee

## 1. Overview & Architecture

Lot F introduces two core runtime guarantees in `factory/factory-service`:
1. **Workspace Read-Only Pre-Check (PRÉCONTRÔLE WORKSPACE)**: Performed BEFORE/AT the creation of the run's root case or execution context. Checks namespace access, Git workspaces enabling, Git repository configuration, autoWorktreeForRootCases, workstream context, and agent availability (when permitted by API). It does NOT repair or mutate any configuration, failing with an actionable error (`WorkspacePrecheckException`) if prerequisites are missing.
2. **Workspace Availability & Execution Worktree Guarantee**:
   - Waits for a usable workspace after root case creation without long database transactions.
   - Distinguishes `PREPARING`, `READY`, and error states, exposing actionable error details and NEVER falling back to a different directory.
   - Explicitly sets `repoRoot` for code steps (`WorkUnitEnvironment`, execution context, and tool invocations) to point directly to the run's Git worktree.

### Isolation relative to Lot E & other Lots:
- **Lot E Isolation**: Lot E modified `CapabilityExecutionService.kt` (verdicts, amendment counter, Searcher routing). Lot F places all precheck and workspace polling logic in dedicated services/classes (`WorkspacePrecheckService`, `WorkspaceStateWaiter`, etc.). `CapabilityExecutionService.kt` is touched ONLY at strict anchor points (passing/verifying `repoRoot = worktreePath`), leaving Lot E result/routing/amendment logic completely untouched.
- **SDK & External Scope Isolation**: No changes to `agentos-factory-bridge-plugin`, `agentos-sdk`, Lot B root reservation, Lot C propagation, or UI components.

---

## 2. Components & Detailed Changes

### 2.1 Domain Exceptions & Models
- File: `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspacePrecheckException.kt`
  - Structured exception extending `FactoryException` or `RuntimeException`.
  - Properties: `code` (e.g. `NAMESPACE_INACCESSIBLE`, `GIT_WORKSPACES_DISABLED`, `GIT_CONFIG_MISSING`, `AUTO_WORKTREE_DISABLED`, `WORKSTREAM_UNRESOLVABLE`, `REQUIRED_AGENTS_MISSING`), `details`, actionable message.

- File: `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspaceStatus.kt`
  - Enum or Sealed Interface for workspace states: `PREPARING`, `READY`, `FAILED`.

### 2.2 Workspace Pre-Check Service (Read-Only)
- File: `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspacePrecheckService.kt`
  - Injected with `AgentOsExecutionAdapter`, `WorkstreamService` (or `WorkstreamRepository`), `WorkflowDefinitionRepository` / agent definition checkers.
  - Method: `fun performPrecheck(namespaceId: String, callerUserId: String?, workstreamId: String?, requiredAgents: List<String>)`
  - Checks:
    1. **Namespace accessibility**: Queries AgentOS adapter / namespace endpoint. If inaccessible or returns 403/404, throws `NAMESPACE_INACCESSIBLE`.
    2. **Git Workspaces enabled & Git configuration**: Queries AgentOS `/api/namespaces/{namespaceId}/git` via adapter or REST client.
       - Checks `associated == true`. If not associated or checkout state is disabled/failed, throws `GIT_CONFIG_MISSING`.
       - Checks `agentos.git.workspaces.enabled` / `autoWorktreeForRootCases` flag in configuration/association. If workspaces/autoWorktree disabled, throws `GIT_WORKSPACES_DISABLED` / `AUTO_WORKTREE_DISABLED`.
    3. **Workstream Context**: Verifies `workstreamId` exists and is resolvable. If missing/invalid, throws `WORKSTREAM_UNRESOLVABLE`.
    4. **Required Agents Availability**: When the AgentOS/Factory API exposes agent availability per namespace, verifies all `requiredAgents` are present & enabled. If missing, throws `REQUIRED_AGENTS_MISSING`. Document clearly if an agent check API is unavailable.
  - Strictly read-only: No mutations or auto-repairs.

### 2.3 Workspace State Waiter & Ready Verification
- File: `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspaceStateWaiter.kt`
  - Method: `fun awaitWorkspaceReady(caseId: String, timeoutMs: Long, pollIntervalMs: Long): WorkspaceReadyFacts`
  - Executes polling outside long DB transactions (`@Transactional` NOT on this polling loop).
  - Queries AgentOS workspace status endpoint `/api/cases/{caseId}/resource-binding` or `/api/namespaces/{namespaceId}/git`.
  - States:
    - `PREPARING`: continue waiting until `timeoutMs`.
    - `READY`: return `WorkspaceReadyFacts(worktreePath = ...)` containing the exact path to the worktree.
    - `FAILED` / Error: throw `WorkspaceProvisioningFailedException(reason, details)` with actionable details.
  - Strict Rule: NEVER falls back silently to another directory (e.g. `/tmp` or fallback root). If not ready or failed, throws immediately.

### 2.4 Run Orchestration & Code Step Root Guarantee
- Anchor: `SessionRunService.kt` / `CapabilityExecutionService.kt` / `WorkUnitEnvironmentService.kt`
  1. **Before root case creation**: Invoke `WorkspacePrecheckService.performPrecheck(...)`.
  2. **After root case creation**: Invoke `WorkspaceStateWaiter.awaitWorkspaceReady(rootCaseId)`.
  3. **Code Step `repoRoot` Binding**:
     - Retrieve the validated worktree path from `WorkspaceReadyFacts`.
     - Explicitly assign `repoRoot = worktreePath` on the execution context / `WorkUnitEnvironment` for all code steps in the run family.
     - Verify that every build, test, and code verification step targets `repoRoot`.

---

## 3. Implementation Steps & File Touch List

1. **New Files**:
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspacePrecheckException.kt`
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspaceStatus.kt`
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspacePrecheckService.kt`
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workspace/WorkspaceStateWaiter.kt`
   - `factory/factory-service/src/test/kotlin/io/whozoss/factory/workspace/WorkspacePrecheckServiceTest.kt`
   - `factory/factory-service/src/test/kotlin/io/whozoss/factory/workspace/WorkspaceStateWaiterTest.kt`

2. **Modified Files (Anchor Points Only)**:
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt`: Add pre-check call before creation, await workspace state after root case creation, and bind `repoRoot`.
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityExecutionService.kt`: Minimal touches to ensure code steps receive and enforce `repoRoot` pointing to the run's worktree. Do NOT touch Lot E result/amendment/routing logic.
   - `factory/factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/AgentOsExecutionAdapter.kt` & `DefaultAgentOsExecutionAdapter.kt`: Add helper methods for querying namespace Git association and resource binding status if missing.

---

## 4. Test Strategy & Verification

### Unit & Integration Tests:
- `WorkspacePrecheckServiceTest`:
  - Test namespace inaccessible (403/404) -> throws `NAMESPACE_INACCESSIBLE`.
  - Test Git workspaces disabled -> throws `GIT_WORKSPACES_DISABLED`.
  - Test Git configuration missing -> throws `GIT_CONFIG_MISSING`.
  - Test unresolvable workstream -> throws `WORKSTREAM_UNRESOLVABLE`.
  - Test agent availability check -> throws `REQUIRED_AGENTS_MISSING` when agent not enabled.
- `WorkspaceStateWaiterTest`:
  - Test `PREPARING` transitioning to `READY` within timeout -> returns worktree path.
  - Test `PREPARING` timing out -> throws timeout exception.
  - Test workspace provisioning failure -> throws actionable error, never falls back to another folder.
- `CodeStepWorktreeExecutionTest`:
  - Verify code steps execute specifically on `worktreePath` across all attempts in the case family.

### Regression Verification:
- Run Nx affected tests:
  `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
