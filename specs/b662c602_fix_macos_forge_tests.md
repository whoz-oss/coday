# Plan: Fix 3 MacOS local validation test failures in Kotlin port (W6a scope)

## Overview & Goal
Align the Kotlin port behavior in `factory-service` (`src/test/kotlin/io/whozoss/factory/forge/`) strictly with the reference Node/TS implementation (`factory/src/application/forge-bmad/` and `factory/src/domain/forge-bmad/`), fix all 3 failing tests (and potential symlink / evidence hash / exception handling bugs) in `ForgeSpecReader`, `ForgeHumanDecision`, `ForgeGateService`, `ForgeStoryOperations`, `ForgeFrontOracleResolution`, and `StoryOperationService`, and verify that `./gradlew clean test` (or `./gradlew test --rerun-tasks` when executed inside `factory-service`) passes completely without regression.

---

## Root Cause Analysis & Findings

### Failure 1 & 2: `ForgeGatesIntegrationTest`
1. **Evidence Hash Mismatch**:
   - In `ForgeHumanDecision.kt`, `computeG1EvidenceSetHash` creates a map: `mapOf("policyVersion" to policyVersion, "evidence" to evidence)`.
   - When serialized with `canonicalG1`, `ForgeJson.canonical` formats Kotlin maps/lists.
   - Node `computeG1EvidenceSetHash` in `factory/src/domain/forge-bmad/forge-human-decision.ts` returns `"sha256:" + createHash('sha256')...`.
   - In Kotlin `ForgeHumanDecision.kt`, `computeG1EvidenceSetHash` returns `ForgeJson.sha256(...)` which prefixes `"sha256:"`.
   - However, in `ForgeHumanDecision.kt`:
     `val evidence = events.filter { ... (event["event"] == "gate_started" && event["runId"] == runId && event["gate"] == "G1" && asInt(event["attempt"]) == attempt) }`
     Notice that `event["attempt"]` in JSON parsed from Jackson might be `Int` or `Long` or `Double` or `String`. `asInt(event["attempt"]) == attempt` works.
   - However, check how `events` are parsed and canonicalized. In Kotlin, `ForgeJson.canonical(value)` processes maps/lists. But `ForgeLedgerStore` or `ledgerStore.parse` might read events as `LinkedHashMap<String, Any?>` where `attempt` is `Int`.
   - Critical difference between Node and Kotlin in `ForgeGatesIntegrationTest.kt`:
     In `approveG1`:
     `val evidence = ForgeHumanDecision.computeG1EvidenceSetHash(events, runId, 1)`
     In `G2 blocks until G1 is approved then passes and is idempotent`:
     `approveG1(rootsMap, runId, created.second)`
     In `recordHumanDecision` (`ForgeRunService.kt`):
     `val evidenceSetHash = ForgeHumanDecision.computeG1EvidenceSetHash(events, runId, asInt(gate["attempt"]), ForgeHumanDecision.G1_POLICY_VERSION)`
     `if (decision["evidenceSetHash"] != evidenceSetHash)` -> throws `ForgeCodedException("G1_DECISION_INVALID")`!
     Wait! In `ForgeGatesIntegrationTest.kt`, line 141 and line 169 throw `java.lang.IllegalArgumentException`!
     Why `IllegalArgumentException` instead of `ForgeCodedException`?
     Let's check where `IllegalArgumentException` comes from:
     - `Path.of(...)` when passed null or invalid path?
     - `ForgeRoots.fromMap(roots)`: `roots["repoRoot"] as String` or `roots["orchestratorRoot"] as String`.
     In `ForgeGatesIntegrationTest.kt`:
     `val rootsMap = mapOf("runStoreRoot" to roots(root).runStoreRoot)`
     Notice that `rootsMap` in `evaluateG2(rootsMap, runId, specPath.toString())` has ONLY `"runStoreRoot"`.
     `ForgeRoots.fromMap(rootsMap)`:
     In `ForgeRoots.kt`:
     ```kotlin
     fun fromMap(map: Map<String, Any?>): ForgeRoots {
         val orchestratorRoot = map["orchestratorRoot"] as? String ?: map["repoRoot"] as? String ?: ""
         val repoRoot = map["repoRoot"] as? String ?: orchestratorRoot
         val runStoreRoot = map["runStoreRoot"] as? String ?: ""
         ...
     }
     ```
     If `repoRoot` is `""`, `Path.of("")` is current directory!
     Then in `ForgeSpecReader.loadForgeSpec(specPath, resolved.repoRoot, ...)`:
     `specPath` is `/var/folders/.../epic-spec.md` (or temp dir). `repoRoot` is `""`.
     `inside(path.toString(), repoRoot)`: `Path.of("").relativize(Path.of("/var/folders/..."))` -> `isAbsolute` is false, but relative path `../../..` or whatever!
     Wait! Why was `rootsMap` created as `mapOf("runStoreRoot" to roots(root).runStoreRoot)`?
     In `ForgeGatesIntegrationTest.kt`:
     `roots(root)` returns `ForgeRoots(orchestratorRoot = root.toString(), repoRoot = root.toString(), runStoreRoot = root.resolve("runs").toString(), ...)`
     If `rootsMap` is created as `mapOf("runStoreRoot" to roots(root).runStoreRoot)` or `roots(root).toMap()`, wait!
     Let's check how TS handles `roots`:
     In TS `forge-g2.ts`, `roots` is `{ repoRoot, runStoreRoot, forgeRoot? }`.
     In Kotlin `ForgeGatesIntegrationTest.kt`:
     `val rootsMap = mapOf("runStoreRoot" to roots(root).runStoreRoot)` -> missing `repoRoot`!
     Wait! In `ForgeGatesIntegrationTest.kt`:
     Line 115: `val rootsMap = mapOf("runStoreRoot" to roots(root).runStoreRoot)`
     Wait, in test 1 (`G1 decision is recorded...`):
     `runService.recordHumanDecision(rootsMap, runId, decision, "actor-1", "authority-1")` works because `recordHumanDecision` only uses `runStoreRoot`!
     In test 3 (`G2 blocks until...`):
     Line 141: `val blocked = gateService.evaluateG2(rootsMap, runId, specPath.toString())`
     When `gateService.evaluateG2` is called:
     `resolved = ForgeRoots.fromMap(rootsMap)` -> `resolved.repoRoot` is `""`!
     Then `loadForgeSpec(specPath, "", null, ...)` is called!
     Inside `loadForgeSpec`:
     `resolveFile(specPath, "G2_SPEC_PATH_INVALID")` returns `Path.of(specPath).toRealPath()`.
     Then `inside(path.toString(), repoRoot)`: `Path.of("").relativize(Path.of("/private/var/..."))` -> `rel` is `private/var/...` (doesn't start with `..` and isn't absolute!).
     Wait! Is `rel` for `Path.of("").relativize(Path.of("/private/var/..."))` throwing `IllegalArgumentException`?
     YES! In Java `Path.relativize`:
     `Path.of("").relativize(Path.of("/private/var/..."))` throws `java.lang.IllegalArgumentException: 'other' is different type of Path` (one is relative `""`, one is absolute `/private/var/...`)!

2. **Symlink / `toRealPath()` in Path normalization**:
   On macOS, temporary directories created with `Files.createTempDirectory(...)` are located under `/var/folders/...`, which is a symlink to `/private/var/folders/...`.
   In `ForgeSpecReader.kt`:
   `resolveFile` calls `specPath.toRealPath()` returning `/private/var/folders/...`.
   `inside(child, root)` calls `Path.of(root).relativize(Path.of(child))`.
   If `root` is `/var/folders/...` (not resolved with `toRealPath()`) and `child` is `/private/var/folders/...` (resolved with `toRealPath()`), `Path.of("/var/folders/...").relativize(Path.of("/private/var/folders/..."))` produces `../../private/var/folders/...` which starts with `..`!
   Therefore `inside(...)` returns `false`!
   And `loadForgeSpec` throws `ForgeCodedException("G2_SPEC_OUTSIDE_ROOT")`!
   Furthermore, if `root` is empty `""` (relative) and `child` is `/private/...` (absolute), `Path.of("").relativize(...)` throws `java.lang.IllegalArgumentException`!

   **Fix for `ForgeSpecReader.kt` & `inside`**:
   `inside(child, root)` MUST canonicalize both `child` and `root` consistently using real path (or absolute normalized path if file doesn't exist).
   ```kotlin
   private fun canonicalPath(p: String): Path {
       if (p.isBlank()) return Path.of("").toAbsolutePath().normalize()
       val path = Path.of(p)
       return if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
   }

   private fun inside(child: String, root: String): Boolean {
       if (root.isBlank()) return false
       val rootPath = canonicalPath(root)
       val childPath = canonicalPath(child)
       val rel = rootPath.relativize(childPath).toString()
       return rel.isEmpty() || (!rel.startsWith("..") && !Path.of(rel).isAbsolute)
   }
   ```
   And in `ForgeGatesIntegrationTest.kt`:
   `rootsMap` should include `repoRoot` (i.e. `roots(root).toMap()`), matching the roots helper in test setup!
   Let's check `roots(root).toMap()` or ensuring `rootsMap` in `ForgeGatesIntegrationTest` passes `repoRoot` properly: `roots(root).toMap()`.

### Failure 3: `ForgeStoryOperationsTest`
1. In `ForgeStoryOperationsTest.kt` line 185:
   `the oracle campaign records and projects a passing campaign`
   In line 191:
   `"roots":{"repoRoot":"$root","runStoreRoot":"$runStoreRoot"}`
   When `service.executeStoryOracles(...)` runs:
   `repoRoot = rootsRepo(events, epicRunId)` which yields `$root` (e.g. `/var/folders/...`).
   `specPath` in `g2["spec"]["path"]` was recorded during `evaluateG2` as `/private/var/folders/.../spec.md`.
   When `StoryOperationService` calls `ForgeSpecReader.loadForgeSpec(specPath, repoRoot, ...)`:
   `loadForgeSpec` checks `inside(path.toString(), repoRoot)`.
   Because `path` was canonicalized to `/private/var/...` and `repoRoot` was `/var/...`, `inside` returned `false`, throwing `ForgeCodedException("G2_SPEC_OUTSIDE_ROOT")`!
   With `canonicalPath` in `ForgeSpecReader.inside`, `/var/...` and `/private/var/...` both resolve to `/private/var/...`, so `inside` evaluates to `true`!

2. **Nx Owner project discovery in `resolveOwnerProjectConfigs`**:
   In `ForgeFrontOracleResolution.kt`:
   `resolveOwnerProjectConfigs(files, repoRoot)`:
   `val root = Path.of(repoRoot).toAbsolutePath().normalize()`
   On macOS, `repoRoot` might be `/var/folders/...` while `absolute` file path might be resolved via symlinks.
   Using `canonicalPath(repoRoot)` ensures `root` is canonicalized.
   Also, in `ForgeStoryOperationsTest.kt`:
   Does `ForgeStoryOperationsTest` create `project.json` for `src/a.ts` in the test?
   Wait! In `ForgeStoryOperationsTest.kt`, line 185:
   `frontResolver` is passed as a lambda parameter to `service.executeStoryOracles(...)`:
   `val frontResolver: (FrontOraclePlanInput) -> Map<String, Any?> = { _ -> mapOf(...) }`
   In `StoryOperationService.executeStoryOracles(...)`:
   `frontPlan = frontResolver(FrontOraclePlanInput(...))`
   Because `frontResolver` is custom in this test, `ForgeFrontOracleResolution.resolveFrontOraclePlan` is bypassed!
   So the failure in line 185 was strictly caused by `loadForgeSpec` throwing `G2_SPEC_OUTSIDE_ROOT` due to macOS symlinks (`/var` vs `/private/var`)!

---

## Detailed Plan of Changes

### 1. `ForgeSpecReader.kt` (Path & Symlink Confinement)
- Update `inside(child: String, root: String): Boolean` to resolve both paths canonicalizing symlinks (`toRealPath()` if exists, otherwise `toAbsolutePath().normalize()`).
- Guard against empty or blank `root` strings to prevent `IllegalArgumentException` from `relativize`.
- Ensure all paths returned by `loadForgeSpec` and `readStorySpec` are canonical string representations.

### 2. `ForgeGatesIntegrationTest.kt` & Test Root Setup
- In `ForgeGatesIntegrationTest.kt`, update `rootsMap` definition in tests where G2 and G2-US are evaluated so that `repoRoot`, `orchestratorRoot`, `runStoreRoot`, and `runStorePolicy` are present in `rootsMap`:
  `val rootsMap = roots(root).toMap()` (or `mapOf("repoRoot" to root.toString(), "runStoreRoot" to roots(root).runStoreRoot, ...)`).
- Ensure `roots(root)` helper in `ForgeGatesIntegrationTest.kt` returns a `ForgeRoots` object whose `toMap()` contains all necessary entries (`repoRoot`, `runStoreRoot`, `orchestratorRoot`, `runStorePolicy`).

### 3. `ForgeHumanDecision.kt` & `ForgeGateService.kt` Audit
- Verify `ForgeHumanDecision.computeG1EvidenceSetHash`:
  Ensure filtering logic for events matches Node TS `forge-human-decision.ts` exactly:
  `(event["event"] == "run_started" && event["runId"] == runId)`
  `(event["event"] == "story_run_created" && event["parentRunId"] == runId)`
  `(event["event"] == "gate_started" && event["runId"] == runId && event["gate"] == "G1" && asInt(event["attempt"]) == attempt)`
  And canonical JSON hashing matches TS sha256 formatting (`"sha256:<hex>"`).
- Check `ForgeGateService.kt` for any unsafe casts or missing map checks that could throw `IllegalArgumentException` or `ClassCastException` instead of returning the domain `recorded`/`blocked`/`conflict` response structure or throwing `ForgeCodedException`.
- Verify `evaluateG2` and `evaluateG2US` return types and handling when `loadForgeSpec` or `readStorySpec` fails or when spec hash changes.

### 4. `ForgeFrontOracleResolution.kt` & `StoryOperationService.kt`
- Canonicalize `repoRoot` in `ForgeFrontOracleResolution.resolveOwnerProjectConfigs` so `root` path comparison uses `toRealPath()`/`toAbsolutePath().normalize()`.
- Ensure exception handling in `StoryOperationService.executeStoryOracles` catches `ForgeCodedException` and maps error codes properly.

---

## Verification Plan

1. Run single tests first (from `/work/app/factory-service`):
   `./gradlew test --tests "io.whozoss.factory.forge.ForgeGatesIntegrationTest"`
   `./gradlew test --tests "io.whozoss.factory.forge.ForgeStoryOperationsTest"`
2. Run full test suite for `factory-service`:
   `./gradlew clean test`
3. Verify zero failures, zero warnings/regressions in the existing tests (`ForgeLedgerIntegrationTest`, `ForgeDomainUnitTest`, `EnvironmentLifecycleControllerTest`, etc.).
4. Confirm test architecture: All tests in `src/test/kotlin/io/whozoss/factory/forge/` extend `DomainIntegrationTest`. No new `@SpringBootTest` annotations added.

---

## File Touch List
- `factory-service/src/main/kotlin/io/whozoss/factory/forge/infrastructure/ForgeSpecReader.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/forge/domain/ForgeFrontOracleResolution.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/forge/service/ForgeGateService.kt`
- `factory-service/src/main/kotlin/io/whozoss/factory/forge/service/ForgeRunService.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/forge/ForgeGatesIntegrationTest.kt`
- `factory-service/src/test/kotlin/io/whozoss/factory/forge/ForgeStoryOperationsTest.kt`

---

## Copy Plan to `specs/`
Copy the completed `plan.md` to `specs/b662c602_fix_macos_forge_tests.md`.
