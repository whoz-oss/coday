// GENERATED FILE — DO NOT EDIT. Source: factory/src/entrypoints/factory-operational.ts

// ../src/lib/active-case.ts
import { unlinkSync, writeFileSync } from "node:fs";
var registry = /* @__PURE__ */ new Map();
var observabilityFile = process.env.FACTORY_ACTIVE_CASE_FILE ?? null;
function registerActiveCase(caseId, label) {
  if (registry.has(caseId)) return;
  registry.set(caseId, label ?? caseId);
  if (observabilityFile) {
    try {
      writeFileSync(observabilityFile, caseId, "utf8");
    } catch {
    }
  }
}
function unregisterActiveCase(caseId) {
  registry.delete(caseId);
  if (observabilityFile && registry.size === 0) {
    try {
      unlinkSync(observabilityFile);
    } catch {
    }
  }
}
function getActiveCaseIds() {
  return [...registry.keys()];
}
function setActiveCaseId(caseId) {
  registerActiveCase(caseId);
}
function clearActiveCaseId(caseId) {
  if (caseId != null) {
    unregisterActiveCase(caseId);
  }
}
function getActiveCaseId() {
  const first = registry.keys().next();
  return first.done ? null : first.value;
}

// ../src/lib/registry.ts
import { appendFileSync, mkdirSync } from "node:fs";
import { join, dirname } from "node:path";
import { randomBytes } from "node:crypto";
import { fileURLToPath } from "node:url";
var RUNS_DIR = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "runs");
var currentRun = null;
var closedRunIds = /* @__PURE__ */ new Set();
function getCurrentRun() {
  return currentRun;
}
function generateRunId() {
  const timestamp = (/* @__PURE__ */ new Date()).toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
  return `${timestamp}-${randomBytes(2).toString("hex")}`;
}
function appendLine(filePath, record) {
  appendFileSync(filePath, `${JSON.stringify(record)}
`, "utf8");
}
function createRun(workflowName, opts = {}) {
  mkdirSync(RUNS_DIR, { recursive: true });
  const runId = generateRunId();
  const filePath = join(RUNS_DIR, `${runId}.jsonl`);
  const record = {
    kind: "run_start",
    runId,
    workflow: workflowName,
    startedAt: (/* @__PURE__ */ new Date()).toISOString()
  };
  if (opts.namespaceId) record.namespaceId = opts.namespaceId;
  appendLine(filePath, record);
  const run = { runId, filePath, _startedAt: Date.now() };
  if (opts.namespaceId) run.namespaceId = opts.namespaceId;
  currentRun = run;
  return run;
}
function startPhase(run, name, kind) {
  appendLine(run.filePath, {
    kind: "phase",
    name,
    phaseKind: kind,
    status: "fail",
    startedAt: (/* @__PURE__ */ new Date()).toISOString()
  });
  return { name, _startedAt: Date.now(), run };
}
function endPhase(phase, status, facts) {
  appendLine(phase.run.filePath, {
    kind: "phase_end",
    name: phase.name,
    status,
    durationMs: Date.now() - phase._startedAt,
    facts
  });
}
function passPhase(phase, facts = {}) {
  endPhase(phase, "pass", facts);
}
function failPhase(phase, facts = {}) {
  endPhase(phase, "fail", facts);
}
function endRun(run, status, facts = {}) {
  const record = {
    kind: "run_end",
    status,
    durationMs: Date.now() - run._startedAt,
    endedAt: (/* @__PURE__ */ new Date()).toISOString()
  };
  if (Object.keys(facts).length > 0) record.facts = facts;
  appendLine(run.filePath, record);
}
function endCurrentRunOnce(status, facts = {}) {
  const run = currentRun;
  if (!run || closedRunIds.has(run.runId)) return false;
  endRun(run, status, facts);
  closedRunIds.add(run.runId);
  return true;
}

// ../src/application/shutdown.ts
function createShutdownController(deps) {
  let initiated = false;
  let completed = false;
  return {
    markCompleted() {
      completed = true;
    },
    async handle(signal) {
      if (initiated || completed) return;
      initiated = true;
      deps.warn(`[shutdown] ${signal} re\xE7u \u2014 arr\xEAt gracieux en cours.`);
      deps.rejectPendingGates();
      const caseIds = deps.activeCaseIds();
      const hadActiveCases = caseIds.length > 0;
      await Promise.allSettled(
        caseIds.map(async (caseId) => {
          try {
            await deps.caseTerminator.terminate(caseId);
            deps.warn(`[shutdown] Case ${caseId} tu\xE9.`);
          } catch (error) {
            deps.warn(`[shutdown] Erreur lors du kill du case ${caseId} : ${String(error)}`);
          }
        })
      );
      if (!completed) {
        try {
          deps.endCurrentRunOnce("fail", { checkoutMayBeIntermediate: hadActiveCases, terminatedBySignal: signal });
        } catch (error) {
          deps.warn(`[shutdown] Erreur lors de la finalisation du run : ${String(error)}`);
        }
      }
      deps.warn("[shutdown] Sortie.");
      deps.exit(1);
    }
  };
}

// ../src/adapters/agentos-http-case-terminator.ts
function createAgentOsHttpCaseTerminator(options) {
  const fetchImpl = options.fetchImpl ?? fetch;
  const timeoutMs = options.timeoutMs ?? 5e3;
  return {
    async terminate(caseId) {
      const response = await fetchImpl(`${options.baseUrl}/api/cases/${encodeURIComponent(caseId)}/kill`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-External-User-Id": options.userId },
        signal: AbortSignal.timeout(timeoutMs)
      });
      if (!response.ok) throw new Error(`AgentOS kill ${caseId} returned HTTP ${response.status}`);
    }
  };
}

// ../src/adapters/process-shutdown.ts
function installSigtermHandler(controller, processPort = process) {
  processPort.once("SIGTERM", () => {
    void controller.handle("SIGTERM");
  });
}
function processExit(processPort = process) {
  return (code) => processPort.exit(code);
}
export {
  clearActiveCaseId,
  createAgentOsHttpCaseTerminator,
  createRun,
  createShutdownController,
  endCurrentRunOnce,
  endRun,
  failPhase,
  getActiveCaseId,
  getActiveCaseIds,
  getCurrentRun,
  installSigtermHandler,
  passPhase,
  processExit,
  registerActiveCase,
  setActiveCaseId,
  startPhase,
  unregisterActiveCase
};
