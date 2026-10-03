# Plan - W8.2 : Session Definition Model & Capability Resolution

This plan covers wave **W8.2** of the Factory Instrument & Control Plane evolution (based on `factory-kotlin-w8.1`).

---

## Executive Summary

W8.2 enriches the declarative **Session Definition** model and establishes **Capability Resolution** (`CapabilityResolver`) in `factory-service`, while keeping `factory-verification-core` as a pure, framework-free Kotlin library.

Key decisions & boundaries enforced:
1. **Roadmap updated**: `plans/2026-09-27-factory-instrument.md` is updated to reflect declarative session steps (`id`, `name`, `dependsOn`, `responsibility{kind, name}`), destination repo whitelist manifest (`factory/verification.json`), AgentOS HTTP capability (W8.3), and human interactions (`human_interactions`).
2. **Session Format & DAG Validation**: `WorkflowStepDefinition` and `WorkflowDefinitionValidator` strictly enforce minimal declarative format (`id`, `name`, `dependsOn`, `responsibility{kind ∈ {agent, code, human}, name}`). NO orchestration properties allowed (e.g. `gate`, `retry`, `onFailure`, `briefTemplate`). Adds validation for unique step IDs, valid dependencies, no self-dependencies, no DAG cycles, valid step responsibility kinds, and valid names.
3. **Target Repository Verification Manifest (`factory/verification.json`)**: Built inside `factory-verification-core` (pure Kotlin stdlib + Jackson, ZERO framework dependencies). Parsed from `<target_repo_root>/factory/verification.json`. Serves as both resolution AND strict whitelist boundary (`VERIFICATION_NOT_DECLARED` error if name missing from manifest). Uses `OracleExecutor.runCommand` to execute verified scripts with default or specified timeouts.
4. **Capability Resolution (`CapabilityResolver`)**: Introduced in `factory-service` (`io.whozoss.factory.capability`). Dispatches by step responsibility kind:
   - `code` -> loads target repo manifest `factory/verification.json`, resolves command, executes via `factory-verification-core` (`OracleExecutor`), records code transition / evidence / verdict.
   - `agent` -> Returns `NOT_IMPLEMENTED_YET` (leaves `AgentTurnCapability` port to be connected in W8.3).
   - `human` -> Opens/queries human interaction via existing `HumanInteractionRepository` / `human_interactions`.

---

## Subsystem Changes

### 1. Roadmap & Plan Update (`plans/2026-09-27-factory-instrument.md`)

- **File to modify**: `plans/2026-09-27-factory-instrument.md`
- **Changes**:
  - Update section 6 (Découpage en vagues W8.1 → W8.7) and detailed wave descriptions to reflect:
    - **W8.2**: Declarative Session Definition format validation (pure DAG, no orchestration logic in JSON) + target repo verification manifest (`factory/verification.json`) whitelist parsing & execution in `factory-verification-core` + `CapabilityResolver` in `factory-service` (`code` capability execution end-to-end, `human` interactions, `agent` placeholder port).
    - **W8.3**: Agent-turn handlers (`AgentTurnCapability`, HTTP AgentOS integration, step-result binding, quiescence) & full DAG sequence orchestration.
    - **W8.4**: Projection lanes cockpit, import, and workflow metrics.
    - Followed by W6b / cleanup.
- **Verification**: Git commit dedicated to roadmap update as required by task instructions.

---

### 2. Session Definition Model & Validation (`factory-service`)

- **Files to modify**:
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowModels.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowDefinition.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/WorkflowDefinitionValidatorTest.kt` (or new test class)
- **Changes**:
  - Ensure `WorkflowStepResponsibility` and `WorkflowStepDefinition` strictly deserialize and validate minimal format:
    - `id`: required string (safe ID regex)
    - `name`: required string
    - `dependsOn`: required list of string step IDs
    - `responsibility`: required map with `kind` (`agent`, `code`, `human`) and optional/required `name`.
  - Validate:
    - No unknown fields on steps or responsibilities. Reject any orchestration fields (`gate`, `retry`, `onFailure`, `briefTemplate`).
    - Standard validations: unique step IDs (`DUPLICATE_STEP_ID`), existing targets in `dependsOn` (`MISSING_DEPENDENCY`), no self-dependencies (`SELF_DEPENDENCY`), no cycles (`DEPENDENCY_CYCLE`), valid responsibility kind (`INVALID_RESPONSIBILITY`).
  - Add comprehensive unit tests covering:
    - Valid minimal declarative session definitions with mixed `agent`, `code`, `human` steps.
    - Rejection of definitions containing orchestration fields.
    - Duplicate IDs, missing dependency targets, self-dependencies, DAG cycles, and invalid responsibility kinds/names.

---

### 3. Verification Manifest in Target Repo (`factory-verification-core`)

- **Directory/Files to create**:
  - `factory-verification-core/src/main/kotlin/io/whozoss/factory/verification/manifest/VerificationManifest.kt`
  - `factory-verification-core/src/main/kotlin/io/whozoss/factory/verification/manifest/VerificationManifestParser.kt`
  - `factory-verification-core/src/main/kotlin/io/whozoss/factory/verification/manifest/VerificationRunner.kt` (or helper object)
  - `factory-verification-core/src/test/kotlin/io/whozoss/factory/verification/manifest/VerificationManifestTest.kt`
- **Model Details**:
  - `VerificationEntry(val command: String, val timeoutMs: Long? = null)`
  - `VerificationManifest(val schemaVersion: String = "1", val verifications: Map<String, VerificationEntry> = emptyMap())`
  - `VerificationResolutionResult`:
    - `Success(val name: String, val entry: VerificationEntry)`
    - `NotDeclared(val name: String, val error: String = "VERIFICATION_NOT_DECLARED")`
- **Behavior & Whitelist**:
  - Parser reads `<repo_root>/factory/verification.json`. If missing or invalid, throws/returns explicit error.
  - Verification resolution: `resolveVerification(name: String): VerificationEntry?` — returns `null` if `name` is not in `verifications` map.
  - Execution via `OracleExecutor`:
    - When resolved, executes `OracleExecutor.runCommand(command, cwd = repoRoot, timeoutMs = entry.timeoutMs ?: defaultTimeoutMs)`.
    - Verdict is `exitCode == 0`.
    - If non-declared name is requested, execution is blocked immediately with `VERIFICATION_NOT_DECLARED`.
- **Framework Invariant Verification**:
  - Check `factory-verification-core/build.gradle.kts`: MUST remain pure Kotlin stdlib + Jackson (no Spring, no HTTP, no DB).
- **Unit Tests**:
  - Valid manifest parsing.
  - Non-declared verification name rejection (`VERIFICATION_NOT_DECLARED`).
  - Timeout override vs default timeout.
  - Execution of deterministic fixture shell scripts in temp directories (pass with `exit 0`, fail with `exit 1`).

---

### 4. Capability Resolution in Factory Service (`factory-service`)

- **Directory/Files to create**:
  - `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityResolver.kt`
  - `factory-service/src/main/kotlin/io/whozoss/factory/capability/AgentTurnCapability.kt` (interface port)
  - `factory-service/src/main/kotlin/io/whozoss/factory/capability/NoOpAgentTurnCapability.kt` (default implementation returning `NOT_IMPLEMENTED_YET`)
  - `factory-service/src/main/kotlin/io/whozoss/factory/capability/CapabilityModels.kt`
  - `factory-service/src/test/kotlin/io/whozoss/factory/capability/CapabilityResolverTest.kt` (integration/unit tests extending `DomainIntegrationTest`)
- **Capability Routing Logic**:
  - `resolveAndExecute(scope: TenantScope, step: WorkflowStepDefinition, repoRoot: Path, ...)`:
    - **`kind == ResponsibilityKind.CODE`**:
      1. Calls `VerificationManifestParser.load(repoRoot)`.
      2. Resolves `step.responsibility.name`. If `null` or not in manifest, fails with error code `VERIFICATION_NOT_DECLARED`.
      3. Executes verified command using `OracleExecutor.runCommand`.
      4. Records code transition / evidence / outcome (verdict = `exitCode == 0`).
      5. Returns `CapabilityResult.CodeResult(exitCode, stdout, stderr, verdict)`.
    - **`kind == ResponsibilityKind.AGENT`**:
      1. Invokes `AgentTurnCapability.executeAgentTurn(...)`.
      2. Default implementation returns `CapabilityResult.NotImplemented("Agent turn execution is scheduled for W8.3")`.
    - **`kind == ResponsibilityKind.HUMAN`**:
      1. Integrates with `HumanInteractionRepository` / `WorkflowService` to create/check open checkpoint (`HumanInteractionRecord`).
      2. Returns `CapabilityResult.HumanInteractionOpened` or current status.
- **Integration Tests**:
  - Extend `DomainIntegrationTest` (no `@SpringBootTest` duplication).
  - Test end-to-end code step execution against a fixture target directory containing `factory/verification.json` and executable script.
  - Test rejection of code step referencing undeclared verification name.
  - Test human step checkpoint creation via `HumanInteractionRepository`.
  - Test agent step returning `NOT_IMPLEMENTED_YET`.

---

## Plan of Commits

1. **Commit 1**: `docs: update factory-instrument roadmap for W8.2 declarative sessions and capability resolution`
2. **Commit 2**: `feat(workflow): enforce strict declarative session format and DAG validation`
3. **Commit 3**: `feat(verification): add target repo verification manifest and whitelist execution in verification-core`
4. **Commit 4**: `feat(capability): implement CapabilityResolver for code, human, and agent steps in factory-service`

---

## Verification & Validation Commands

1. In `factory-verification-core`:
   ```bash
   cd factory-verification-core && ./gradlew clean test
   ```
2. In `factory-service`:
   ```bash
   cd factory-service && ./gradlew clean test
   ```
3. Root full validation (if needed):
   ```bash
   cd /work/app && ./factory-verification-core/gradlew -p factory-verification-core test && ./factory-service/gradlew -p factory-service test
   ```
