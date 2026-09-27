# Architectural Plan: W8.1 — Module `factory-verification-core`

## 1. Overview & Goal

The goal of Wave W8.1 is to create `factory-verification-core`: a **pure Kotlin library** containing the deterministic verification primitives ported from the existing Node validation instrument under `factory/` (`factory/src/application/oracle/*`, `factory/src/domain/oracle/*`, `factory/lib/registry.ts`, `factory/lib/plan.mjs`, `factory/lib/domains.mjs`).

### Architectural Invariant (Strict Constraint)
- `factory-verification-core` is a **pure library**, NOT a service, NOT a plugin, NOT an executable application.
- **ZERO framework dependencies**: No Spring (Core, Boot, Data, JDBC), no HTTP client frameworks (OkHttp/Ktor/HttpClient), no PF4J, no database drivers.
- **Allowed dependencies**: Kotlin stdlib (`kotlin-stdlib`), JDK 25 standard APIs (`java.lang.ProcessBuilder`, `java.nio.file.*`, `java.security.MessageDigest`, `java.time.*`), Jackson (`jackson-databind`, `jackson-module-kotlin` for JSON/JSONL serialization/deserialization) + JUnit 5 / Kotest / MockK for testing.
- **Self-contained by construction**: It compiles and runs even if Spring, Postgres, or AgentOS are offline or unavailable.
- **Do NOT touch `factory-service`**, `factory-sdk`, `factory-forge-plugin`, or legacy Node files in `factory/` in this wave.

---

## 2. Plan Director Document (Step 1)

As requested, the master plan document for W8 was written and committed to:
`plans/2026-09-27-factory-instrument.md`

It details the objective, invariants (autonomy, fail-by-default, single source of truth authority rule), and waves W8.1 through W8.7.

---

## 3. Library Setup (Step 2)

Create the standard Kotlin library module `factory-verification-core/` at the repository root.

### Files to create:
1. `factory-verification-core/build.gradle.kts`:
   - Plugins: `dev.nx.gradle.project-graph`, `kotlin("jvm")`, `maven-publish`.
   - Toolchain Java 25, compatibility target Java 25.
   - Dependencies: `kotlin-stdlib`, `jackson-databind`, `jackson-module-kotlin`.
   - Test dependencies: `bundles.testing.common` (JUnit5/Kotest/MockK), `junit-platform-launcher`.
2. `factory-verification-core/settings.gradle.kts`:
   - `rootProject.name = "factory-verification-core"`
3. `factory-verification-core/project.json`:
   - Written by hand: `{"name": "factory-verification-core", "sourceRoot": "factory-verification-core/src", "tags": ["type:lib", "platform:jvm", "scope:lib"], "targets": {"build": {"dependsOn": []}, "test": {"dependsOn": ["build"]}}}`

---

## 4. Deterministic Primitives Porting (Step 3)

All code will be in package `io.whozoss.factory.verification.*`.

### 3a. `OracleExecutor` (`io.whozoss.factory.verification.oracle`)

Port from `factory/src/application/oracle/oracle-executor.ts` and `oracle-command.ts`:
- **Models**:
  - `RunCommandResult(exitCode: Int, stdout: String, stderr: String, durationMs: Long, timedOut: Boolean)`
  - `BoundedOutput(excerpt: String, truncated: Boolean)` (Max limit = 16,384 chars excerpt, 100,000 max capture limit or similar).
  - `OracleExecutionClassification(classification: Classification, outcome: Outcome)` where `Classification` is `CLEAN`, `PRODUCT_REGRESSION`, `EMPTY_SUCCESS`, `ORACLE_INFRASTRUCTURE`, and `Outcome` is `PASS`, `FAIL`, `INDETERMINATE`.
  - `TaskOutcomes(upToDate: Int, fromCache: Int, skipped: Int, executed: Int, summaryFound: Boolean, ...)`
  - `OracleExecutionResult`: includes classification, outcome, exitCode, signal, timedOut, durationMs, spawnError, counts, stdout, stderr.
- **Task outcome counter** (`countTaskOutcomes(output: String)`):
  - Parses Gradle (`> Task ... [UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE]`) and Nx output (`> nx run ... [existing outputs match the cache]`, summary lines).
- **Execution mechanisms**:
  - **Shell command execution**: using `ProcessBuilder` with `sh -c` / `cmd.exe /c` for domain string templates (e.g. `./gradlew ...`, `pnpm nx ...`).
  - **Argv execution**: using `ProcessBuilder(argv)` directly without shell wrapper (for JSON oracle definitions).
- **Process management**:
  - Timeout enforcement: scheduled thread / timeout timer. When timed out, process destruction via `process.destroyForcibly()` (and `process.descendants().forEach { it.destroyForcibly() }` on Java 9+ ProcessHandle for process group cleanup).
  - Capture stdout/stderr concurrently in bounded buffers to prevent process blocking.
- **Classification logic**:
  - `spawnError` or `timedOut` or non-zero signal -> `ORACLE_INFRASTRUCTURE` / `INDETERMINATE`
  - `exitCode != 0` -> `PRODUCT_REGRESSION` / `FAIL`
  - `requireWork == true` and `counts.executed == 0` -> `EMPTY_SUCCESS` / `INDETERMINATE` (empty success guard A8!)
  - Otherwise (`exitCode == 0` and work executed) -> `CLEAN` / `PASS`
  - **Verdict constraint**: `exitCode == 0` is the primary check, NEVER derived from text output.

### 3b. `WorkspaceSnapshot` (`io.whozoss.factory.verification.snapshot`)

Port from `factory/src/application/oracle/oracle-executor.ts` (`snapshotDiff`, `diffSince`) and `factory/src/domain/oracle/oracle.ts` (`diffSnapshots`):
- **Snapshot capture**:
  - Runs `git diff HEAD --name-only` and `git ls-files --others --exclude-standard` in target `cwd`.
  - Reads each file content and computes SHA-256 digest (`java.security.MessageDigest`).
  - Sentinel: if a file disappeared or is unreadable during hash computation, assign sentinel `'unreadable'` (or `'missing'`).
  - Store as `OracleSnapshot(modified: Map<String, String>, untracked: Map<String, String>)`.
- **Diff computation**:
  - `diff(before: OracleSnapshot, after: OracleSnapshot): OracleSnapshotDelta`
  - Returns modified file paths and untracked file paths whose fingerprint changed or appeared.
  - Helper `wroteNothing(before, after)`: checks if 0 modified and 0 untracked changes occurred.

### 3c. `RunRegistry` (`io.whozoss.factory.verification.registry`)

Port from `factory/src/lib/registry.ts`:
- **JSONL Append-Only Writer**:
  - Methods:
    - `createRun(workflowName: String, namespaceId: String? = null): Run` -> writes `{"kind":"run_start","runId":"...","workflow":"...","startedAt":"...","namespaceId":"..."}`
    - `startPhase(run: Run, name: String, kind: PhaseKind): Phase` -> writes `{"kind":"phase","name":"...","phaseKind":"...","status":"fail","startedAt":"..."}` (**STRICT FAIL BY DEFAULT INVARIANT**)
    - `passPhase(phase: Phase, facts: Map<String, Any> = emptyMap())` -> writes `{"kind":"phase_end","name":"...","status":"pass","durationMs":...,"facts":{...}}`
    - `failPhase(phase: Phase, facts: Map<String, Any> = emptyMap())` -> writes `{"kind":"phase_end","name":"...","status":"fail","durationMs":...,"facts":{...}}`
    - `endRun(run: Run, status: RunStatus, facts: Map<String, Any> = emptyMap())` -> writes `{"kind":"run_end","status":"...","durationMs":...,"endedAt":"...","facts":{...}}`
- **Byte-compatibility**: Fields, timestamps (ISO-8601), and structure must match the exact JSONL format read by `LegacyRunService`. `facts` map is written under key `facts` and never overwritten.

### 3d. `PlanParsing` (`io.whozoss.factory.verification.plan`)

Port from `factory/lib/plan.mjs`:
- `extractJsonFragment(text: String): String?`
  - 3 passes: ```json ... ```, ``` ... ```, or first balanced `{ ... }`.
- `isSafePath(path: String): Boolean`
  - Rejects if path is absolute (`Path.of(path).isAbsolute`) or contains `..` components.
- `parsePlan(agentMessage: String): PlanParseResult`
  - Parses JSON to `Plan(files: List<String>, doneWhen: String, steps: List<String>?)`.
  - Validates `files` is non-empty, all paths in `files` pass `isSafePath`, `doneWhen` is non-empty.
- `checkPlanFiles(repoRoot: Path, plan: Plan): PlanCheckResult`
  - Checks if every path in `plan.files` exists relative to `repoRoot` (`Files.exists(repoRoot.resolve(path))`).
- `compareClaims(announcedFiles: List<String>, actualModifiedFiles: List<String>): ClaimsComparison`
  - Compares files announced in the plan vs actual files modified in snapshot diff (unannounced edits vs unfulfilled claims).

### 3e. `Domains` (`io.whozoss.factory.verification.domain`)

Port from `factory/lib/domains.mjs`:
- **Domain resolution**:
  - Standard Back domain oracle: `./gradlew :agentos-service:build --rerun-tasks --console=plain` in `cwd = REPO_ROOT/agentos`.
  - Standard Front domain oracles: Angular/Nx build + test commands.
  - Environment variable overrides: `FACTORY_COMMAND_FRONT_BUILD`, `FACTORY_COMMAND_FRONT`, `FACTORY_COMMAND_BACK`, `FACTORY_CWD_BACK`, `FACTORY_CWD_FRONT`, `FACTORY_ROOT`.
  - Resolution logic returns list of target oracle command definitions with cwd and command templates.

---

## 5. Ports / Abstractions for Future Waves (Step 4)

Define interfaces in `io.whozoss.factory.verification.ports`:
- `AgentTurnRunner`: interface with method `runTurn(...)` (to be implemented in W8.2 for AgentOS communication).
- `ReviewGateClient`: interface for review gate approval/rejection (to be implemented in W8.3).
- **NO network, HTTP, or AgentOS implementation code** in W8.1 — interfaces only.

---

## 6. Unit Testing Strategy

Pure unit tests under `factory-verification-core/src/test/kotlin/`:
- **OracleExecutorTest**:
  - Test command execution with dummy process or echo/sh scripts.
  - Test exit code 0 vs non-zero classification (`CLEAN` vs `PRODUCT_REGRESSION`).
  - Test empty success detection (`EMPTY_SUCCESS`).
  - Test process timeout handling and process termination (`ORACLE_INFRASTRUCTURE`).
  - Test bounded stdout/stderr output capture.
- **WorkspaceSnapshotTest**:
  - Test git snapshot diff against a temporary git repo created during test using `java.nio.file.Files.createTempDirectory`.
  - Test SHA-256 fingerprint change detection and `wroteNothing`.
- **RunRegistryTest**:
  - Test JSONL file creation and line format.
  - Verify "fail by default" invariant (phase written with `status: "fail"` on `startPhase`).
  - Verify exact byte-compatible JSON structure for `run_start`, `phase`, `phase_end`, `run_end`.
- **PlanParsingTest**:
  - Test `extractJsonFragment` on markdown responses.
  - Test `isSafePath` rejecting absolute paths and `..`.
  - Test `parsePlan`, `checkPlanFiles`, and `compareClaims`.
- **DomainsTest**:
  - Test default command resolution and env var overrides (`FACTORY_COMMAND_*`, `FACTORY_ROOT`).

---

## 7. Execution & Verification Steps

1. Commit master plan document `plans/2026-09-27-factory-instrument.md`.
2. Create `factory-verification-core/` module structure (`build.gradle.kts`, `settings.gradle.kts`, `project.json`).
3. Implement primitives in `src/main/kotlin/io/whozoss/factory/verification/`.
4. Implement unit tests in `src/test/kotlin/io/whozoss/factory/verification/`.
5. Run clean test build:
   `./gradlew :factory-verification-core:clean :factory-verification-core:test`
6. Verify 100% green unit tests and verify `build.gradle.kts` has zero framework dependencies.

---

## 8. File Structure Summary

```
factory-verification-core/
├── build.gradle.kts
├── settings.gradle.kts
├── project.json
└── src/
    ├── main/kotlin/io/whozoss/factory/verification/
    │   ├── oracle/
    │   │   ├── OracleExecutor.kt
    │   │   ├── TaskOutcomeCounter.kt
    │   │   └── OracleModels.kt
    │   ├── snapshot/
    │   │   └── WorkspaceSnapshot.kt
    │   ├── registry/
    │   │   └── RunRegistry.kt
    │   ├── plan/
    │   │   └── PlanParsing.kt
    │   ├── domain/
    │   │   └── DomainResolver.kt
    │   └── ports/
    │       ├── AgentTurnRunner.kt
    │       └── ReviewGateClient.kt
    └── test/kotlin/io/whozoss/factory/verification/
        ├── oracle/OracleExecutorTest.kt
        ├── snapshot/WorkspaceSnapshotTest.kt
        ├── registry/RunRegistryTest.kt
        ├── plan/PlanParsingTest.kt
        └── domain/DomainResolverTest.kt
```
