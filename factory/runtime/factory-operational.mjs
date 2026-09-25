// GENERATED FILE — DO NOT EDIT. Source: factory/src/entrypoints/factory-operational.ts
var __defProp = Object.defineProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};

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
function appendLine(filePath, record2) {
  appendFileSync(filePath, `${JSON.stringify(record2)}
`, "utf8");
}
function createRun(workflowName, opts = {}) {
  mkdirSync(RUNS_DIR, { recursive: true });
  const runId = generateRunId();
  const filePath = join(RUNS_DIR, `${runId}.jsonl`);
  const record2 = {
    kind: "run_start",
    runId,
    workflow: workflowName,
    startedAt: (/* @__PURE__ */ new Date()).toISOString()
  };
  if (opts.namespaceId) record2.namespaceId = opts.namespaceId;
  appendLine(filePath, record2);
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
  const record2 = {
    kind: "run_end",
    status,
    durationMs: Date.now() - run._startedAt,
    endedAt: (/* @__PURE__ */ new Date()).toISOString()
  };
  if (Object.keys(facts).length > 0) record2.facts = facts;
  appendLine(run.filePath, record2);
}
function endCurrentRunOnce(status, facts = {}) {
  const run = currentRun;
  if (!run || closedRunIds.has(run.runId)) return false;
  endRun(run, status, facts);
  closedRunIds.add(run.runId);
  return true;
}

// ../src/domain/workflow/workflow-definition.ts
import { createHash } from "node:crypto";
var WORKFLOW_DEFINITION_SCHEMA_VERSION = "1";
var WORKFLOW_DEFINITION_RESPONSIBILITIES = Object.freeze(["human", "agent", "code"]);
var WORKFLOW_DEFINITION_ERROR_CODES = Object.freeze({
  INVALID_DEFINITION: "INVALID_DEFINITION",
  INVALID_SCHEMA_VERSION: "INVALID_SCHEMA_VERSION",
  INVALID_VALUE: "INVALID_VALUE",
  DUPLICATE_STEP_ID: "DUPLICATE_STEP_ID",
  MISSING_DEPENDENCY: "MISSING_DEPENDENCY",
  SELF_DEPENDENCY: "SELF_DEPENDENCY",
  DEPENDENCY_CYCLE: "DEPENDENCY_CYCLE",
  INVALID_RESPONSIBILITY: "INVALID_RESPONSIBILITY"
});
var SAFE_ID = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/;
var SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
var DEFINITION_FIELDS = /* @__PURE__ */ new Set(["schemaVersion", "workflowType", "version", "title", "trustedExecution", "steps"]);
var TRUSTED_EXECUTION_FIELDS = /* @__PURE__ */ new Set(["allowedPaths"]);
var STEP_FIELDS = /* @__PURE__ */ new Set(["id", "name", "responsibility", "dependsOn"]);
var RESPONSIBILITY_FIELDS = /* @__PURE__ */ new Set(["kind", "name"]);
var KINDS = new Set(WORKFLOW_DEFINITION_RESPONSIBILITIES);
function failure(code, path, details = {}) {
  return { ok: false, error: { code, path, details } };
}
function text(value, path, options = {}) {
  const { safe: safe2 = false, maximum = 256 } = options;
  if (typeof value !== "string" || !value.trim() || value.length > maximum || safe2 && !SAFE_ID.test(value))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, path);
  return { ok: true, value };
}
function validateWorkflowDefinition(input) {
  if (!input || typeof input !== "object" || Array.isArray(input))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_DEFINITION, "$");
  const record2 = input;
  if (Object.keys(record2).some((field) => !DEFINITION_FIELDS.has(field)))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "$", { reason: "unknown_field" });
  if (record2.schemaVersion !== WORKFLOW_DEFINITION_SCHEMA_VERSION)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_SCHEMA_VERSION, "schemaVersion");
  const type = text(record2.workflowType, "workflowType", { safe: true });
  if (!type.ok) return type;
  const version = record2.version;
  if (typeof version !== "string" || !SEMVER.test(version))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "version");
  const title = text(record2.title, "title");
  if (!title.ok) return title;
  let trustedExecution;
  if (record2.trustedExecution !== void 0) {
    const rawTrusted = record2.trustedExecution;
    if (!rawTrusted || typeof rawTrusted !== "object" || Array.isArray(rawTrusted) || Object.keys(rawTrusted).some((field) => !TRUSTED_EXECUTION_FIELDS.has(field)) || !Array.isArray(rawTrusted.allowedPaths) || rawTrusted.allowedPaths.length === 0)
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "trustedExecution");
    const allowedPaths = [];
    const rawAllowedPaths = rawTrusted.allowedPaths;
    for (let index = 0; index < rawAllowedPaths.length; index++) {
      const path = rawAllowedPaths[index];
      if (typeof path !== "string" || !path || path.startsWith("/") || path.includes("\\") || path.split("/").includes("..") || path.includes("\0"))
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `trustedExecution.allowedPaths[${index}]`);
      allowedPaths.push(path);
    }
    trustedExecution = { allowedPaths };
  }
  if (!Array.isArray(record2.steps) || record2.steps.length === 0 || record2.steps.length > 500)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "steps");
  const ids = /* @__PURE__ */ new Set();
  const steps = [];
  const rawSteps = record2.steps;
  for (let index = 0; index < rawSteps.length; index++) {
    const raw = rawSteps[index];
    const base = `steps[${index}]`;
    if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((field) => !STEP_FIELDS.has(field)))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, base);
    const step = raw;
    const id2 = text(step.id, `${base}.id`, { safe: true });
    if (!id2.ok) return id2;
    if (ids.has(id2.value))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: id2.value });
    ids.add(id2.value);
    const name = text(step.name, `${base}.name`);
    if (!name.ok) return name;
    const responsibility = step.responsibility;
    if (!responsibility || typeof responsibility !== "object" || Array.isArray(responsibility) || Object.keys(responsibility).some((field) => !RESPONSIBILITY_FIELDS.has(field)) || !KINDS.has(responsibility.kind))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_RESPONSIBILITY, `${base}.responsibility`);
    const responsibilityRecord = responsibility;
    const responsibilityName = text(responsibilityRecord.name, `${base}.responsibility.name`);
    if (!responsibilityName.ok) return responsibilityName;
    if (!Array.isArray(step.dependsOn))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn`);
    const dependencies = [];
    const seen = /* @__PURE__ */ new Set();
    const rawDependencies = step.dependsOn;
    for (let dependencyIndex = 0; dependencyIndex < rawDependencies.length; dependencyIndex++) {
      const dependency = text(rawDependencies[dependencyIndex], `${base}.dependsOn[${dependencyIndex}]`, {
        safe: true
      });
      if (!dependency.ok) return dependency;
      if (seen.has(dependency.value))
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn[${dependencyIndex}]`, {
          reason: "duplicate_dependency"
        });
      seen.add(dependency.value);
      dependencies.push(dependency.value);
    }
    steps.push({
      id: id2.value,
      name: name.value,
      responsibility: { kind: responsibilityRecord.kind, name: responsibilityName.value },
      dependsOn: dependencies
    });
  }
  for (const step of steps)
    for (const dependency of step.dependsOn) {
      if (dependency === step.id)
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.SELF_DEPENDENCY, `steps.${step.id}.dependsOn`);
      if (!ids.has(dependency))
        return failure(WORKFLOW_DEFINITION_ERROR_CODES.MISSING_DEPENDENCY, `steps.${step.id}.dependsOn`, {
          target: dependency
        });
    }
  const graph = new Map(steps.map((step) => [step.id, step.dependsOn]));
  const visiting = /* @__PURE__ */ new Set();
  const visited = /* @__PURE__ */ new Set();
  function cyclic(id2) {
    if (visiting.has(id2)) return true;
    if (visited.has(id2)) return false;
    visiting.add(id2);
    for (const dependency of graph.get(id2) ?? []) if (cyclic(dependency)) return true;
    visiting.delete(id2);
    visited.add(id2);
    return false;
  }
  for (const step of steps)
    if (cyclic(step.id)) return failure(WORKFLOW_DEFINITION_ERROR_CODES.DEPENDENCY_CYCLE, "steps");
  return {
    ok: true,
    definition: {
      schemaVersion: WORKFLOW_DEFINITION_SCHEMA_VERSION,
      workflowType: type.value,
      version,
      title: title.value,
      ...trustedExecution ? { trustedExecution } : {},
      steps
    }
  };
}
function canonicalizeValue(value) {
  if (Array.isArray(value)) return value.map((entry) => canonicalizeValue(entry));
  if (value !== null && typeof value === "object") {
    const record2 = value;
    return Object.fromEntries(
      Object.keys(record2).sort().map((key) => [key, canonicalizeValue(record2[key])])
    );
  }
  return value;
}
function canonicalizeWorkflowDefinition(definition) {
  return JSON.stringify(canonicalizeValue(definition));
}
function hashWorkflowDefinition(definition) {
  return createHash("sha256").update(canonicalizeWorkflowDefinition(definition), "utf8").digest("hex");
}

// ../src/domain/workflow/workflow-instance.ts
import { createHash as createHash2 } from "node:crypto";
var WORKFLOW_GOVERNANCE_MODE = "governed";
function independentWorkflowRelations(workflowId) {
  return { rootWorkflowId: workflowId };
}
function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value !== null && typeof value === "object") {
    const record2 = value;
    return Object.fromEntries(
      Object.keys(record2).sort().map((key) => [key, canonicalize(record2[key])])
    );
  }
  return value;
}
function workflowStartCommandHash(command, definition) {
  const relations = command.relations ?? independentWorkflowRelations(command.workflowId);
  return createHash2("sha256").update(
    JSON.stringify(
      canonicalize({
        workflowId: command.workflowId,
        workflowType: command.workflowType,
        title: command.title,
        relations: { ...relations },
        definitionVersion: definition.version,
        definitionHash: definition.definitionHash
      })
    )
  ).digest("hex");
}
function createWorkflowInstance(command, definition, controllerExecution, observedAt = (/* @__PURE__ */ new Date()).toISOString()) {
  const steps = definition.steps.map((step) => ({
    id: step.id,
    name: step.name,
    status: step.dependsOn.length === 0 ? "ready" : "pending",
    dependsOn: [...step.dependsOn],
    responsibility: { ...step.responsibility }
  }));
  const instance = {
    governanceMode: WORKFLOW_GOVERNANCE_MODE,
    workflowId: command.workflowId,
    workflowType: definition.workflowType,
    definitionVersion: definition.version,
    definitionHash: definition.definitionHash,
    revision: 1,
    title: command.title,
    status: "ready",
    steps: steps.map(({ id: id2, status }) => ({ id: id2, status })),
    relations: { ...command.relations ?? independentWorkflowRelations(command.workflowId) },
    controllerExecution: { ...controllerExecution, observedAt },
    environmentRef: null,
    deliveryRef: null,
    createdAt: observedAt,
    updatedAt: observedAt
  };
  const projection = {
    schemaVersion: "2",
    workflowId: instance.workflowId,
    workflowType: instance.workflowType,
    title: instance.title,
    status: instance.status,
    steps
  };
  return { instance, projection, creationCommandHash: workflowStartCommandHash(command, definition) };
}

// ../src/domain/workflow/workflow-transition-policy.ts
import { createHash as createHash3, randomUUID } from "node:crypto";
var WORKFLOW_STATUSES = Object.freeze([
  "pending",
  "ready",
  "running",
  "waiting_human",
  "blocked",
  "completed",
  "failed",
  "cancelled"
]);
var WORKFLOW_TRANSITIONS = Object.freeze({
  pending: Object.freeze(["ready"]),
  ready: Object.freeze(["running", "blocked", "failed", "cancelled"]),
  running: Object.freeze(["waiting_human", "blocked", "completed", "failed", "cancelled"]),
  waiting_human: Object.freeze(["running", "blocked", "failed", "cancelled"]),
  blocked: Object.freeze(["ready", "running", "failed", "cancelled"]),
  completed: Object.freeze([]),
  failed: Object.freeze([]),
  cancelled: Object.freeze([])
});
var SAFE_ID2 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var FIELDS = /* @__PURE__ */ new Set([
  "requestId",
  "workflowId",
  "stepId",
  "expectedRevision",
  "requestedStatus",
  "evidenceIds",
  "idempotencyKey"
]);
function deny(code, reason, extra = {}) {
  return { allowed: false, code, reason, ...extra };
}
function invalidTransitionRequest() {
  return { ok: false, error: { code: "INVALID_TRANSITION_REQUEST" } };
}
function isWorkflowStatus(value) {
  return typeof value === "string" && WORKFLOW_STATUSES.includes(value);
}
function validateWorkflowTransitionRequest(input, expectedWorkflowId) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return invalidTransitionRequest();
  const record2 = input;
  if (Object.keys(record2).some((key) => !FIELDS.has(key))) return invalidTransitionRequest();
  if (record2.requestId !== void 0) return { ok: false, error: { code: "UNTRUSTED_REQUEST_ID" } };
  const workflowId = record2.workflowId;
  const stepId = record2.stepId;
  const expectedRevision = record2.expectedRevision;
  const requestedStatus = record2.requestedStatus;
  const evidenceIds = record2.evidenceIds;
  const idempotencyKey = record2.idempotencyKey;
  if (workflowId !== expectedWorkflowId || !SAFE_ID2.test(String(workflowId ?? "")) || !SAFE_ID2.test(String(stepId ?? "")))
    return invalidTransitionRequest();
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1 || !isWorkflowStatus(requestedStatus))
    return invalidTransitionRequest();
  if (!Array.isArray(evidenceIds) || evidenceIds.length > 100 || new Set(evidenceIds).size !== evidenceIds.length || evidenceIds.some((id2) => typeof id2 !== "string" || !SAFE_ID2.test(id2)))
    return invalidTransitionRequest();
  if (idempotencyKey !== void 0 && (typeof idempotencyKey !== "string" || !idempotencyKey || idempotencyKey.length > 128 || /[\r\n]/.test(idempotencyKey)))
    return invalidTransitionRequest();
  return {
    ok: true,
    value: {
      requestId: randomUUID(),
      workflowId,
      stepId,
      expectedRevision,
      requestedStatus,
      evidenceIds: [...evidenceIds],
      ...idempotencyKey ? { idempotencyKey } : {}
    }
  };
}
function transitionSemanticHash(request) {
  return createHash3("sha256").update(
    JSON.stringify({
      workflowId: request.workflowId,
      stepId: request.stepId,
      expectedRevision: request.expectedRevision,
      requestedStatus: request.requestedStatus,
      evidenceIds: [...request.evidenceIds].sort()
    })
  ).digest("hex");
}
function transitionScopeHash(namespaceId, request, execution2) {
  return createHash3("sha256").update(
    JSON.stringify({
      namespaceId,
      workflowId: request.workflowId,
      stepId: request.stepId,
      source: {
        kind: execution2.kind,
        runtimeId: execution2.runtimeId,
        agentId: execution2.agentId,
        actorId: execution2.actorId,
        caseId: execution2.caseId,
        threadId: execution2.threadId
      },
      idempotencyKey: request.idempotencyKey
    })
  ).digest("hex");
}
function evaluateHumanCheckpointOpen({
  request,
  snapshot,
  definition,
  execution: execution2
}) {
  if (!snapshot) return deny("WORKFLOW_NOT_FOUND", "workflow_not_found");
  if (snapshot.governanceMode !== "governed" || snapshot.instance?.governanceMode !== "governed")
    return deny("WORKFLOW_NOT_GOVERNED", "workflow_not_governed");
  if (!definition) return deny("WORKFLOW_DEFINITION_NOT_FOUND", "definition_not_found");
  const instance = snapshot.instance;
  if (instance.workflowType !== definition.workflowType || instance.definitionVersion !== definition.version || instance.definitionHash !== definition.definitionHash || snapshot.definitionVersion !== definition.version || snapshot.definitionHash !== definition.definitionHash)
    return deny("WORKFLOW_DEFINITION_MISMATCH", "definition_identity_mismatch");
  const declared = definition.steps.find((step) => step.id === request.stepId);
  const current = instance.steps.find((step) => step.id === request.stepId);
  if (!declared || !current) return deny("STEP_NOT_FOUND", "step_not_found");
  if (request.expectedRevision !== snapshot.revision || instance.revision !== snapshot.revision)
    return deny("REVISION_CONFLICT", "revision_mismatch");
  if (declared.responsibility?.kind !== "human") return deny("ACTOR_NOT_AUTHORIZED", "step_is_not_human_owned");
  if (current.status !== "ready") return deny("ILLEGAL_TRANSITION", "human_step_is_not_ready");
  const missing = declared.dependsOn.filter(
    (id2) => instance.steps.find((step) => step.id === id2)?.status !== "completed"
  );
  if (missing.length)
    return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", { missingEvidence: missing });
  if (request.requestedStatus !== "waiting_human" || request.evidenceIds.length !== 0)
    return deny("ACTOR_NOT_AUTHORIZED", "human_gate_opener_can_only_open_checkpoint");
  const factoryHumanGate = execution2.kind === "factory-human-gate" && execution2.runtimeId === "factory-dashboard" && execution2.agentId === "factory-runner" && execution2.actorId === void 0;
  const controller = instance.controllerExecution ?? snapshot.controllerExecution;
  const originalController = controller && controller.kind === execution2.kind && controller.runtimeId === execution2.runtimeId && controller.agentId === execution2.agentId && controller.caseId === execution2.caseId && controller.threadId === execution2.threadId;
  if (!factoryHumanGate && !originalController) return deny("ACTOR_NOT_AUTHORIZED", "execution_cannot_open_human_gate");
  return { allowed: true };
}
function evaluateHumanResolutionTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution: execution2
}) {
  if (execution2.kind === "factory-human-gate" || execution2.kind !== "factory-human" || execution2.runtimeId !== "factory-dashboard" || typeof execution2.actorId !== "string" || execution2.actorId.length === 0)
    return deny("ACTOR_NOT_AUTHORIZED", "human_resolution_requires_authenticated_human");
  const current = snapshot?.instance?.steps?.find((step) => step.id === request.stepId);
  if (current?.status !== "waiting_human") return deny("INTERACTION_STALE", "human_step_is_not_waiting");
  if (!["completed", "failed"].includes(request.requestedStatus))
    return deny("ILLEGAL_TRANSITION", "human_resolution_target_not_allowed");
  if (request.requestedStatus === "completed") {
    const present = snapshot;
    const bridged = {
      ...present,
      instance: {
        ...present.instance,
        steps: present.instance.steps.map(
          (step) => step.id === request.stepId ? { ...step, status: "running" } : step
        )
      }
    };
    const decision = evidence.find(
      (item) => request.evidenceIds.includes(item.evidenceId) && item.kind === "human-decision" && item.outcome === "pass" && item.source?.kind === "factory-human" && item.source?.actorId === execution2.actorId
    );
    if (!decision)
      return deny("PASS_EVIDENCE_REQUIRED", "matching_human_decision_required", {
        missingEvidence: ["human-decision:pass"]
      });
    const evaluated = evaluateWorkflowTransition({
      request: { ...request, requestedStatus: "completed" },
      snapshot: bridged,
      definition,
      evidence,
      execution: { ...execution2, kind: "factory-human-resolution" }
    });
    return !evaluated.allowed && evaluated.code === "ACTOR_NOT_AUTHORIZED" && evaluated.reason === "runtime_cannot_transition_step_responsibility" ? { allowed: true } : evaluated;
  }
  const completion = evaluateWorkflowTransition({
    request: { ...request, requestedStatus: "failed" },
    snapshot,
    definition,
    evidence,
    execution: execution2
  });
  if (!completion.allowed) return completion;
  const selected = request.evidenceIds.map((id2) => evidence.find((item) => item.evidenceId === id2)).filter((item) => Boolean(item));
  return selected.some(
    (item) => item.kind === "human-decision" && item.outcome === "fail" && item.source?.kind === "factory-human" && item.source?.actorId === execution2.actorId
  ) ? { allowed: true } : deny("FAIL_EVIDENCE_REQUIRED", "matching_human_decision_fail_required", {
    missingEvidence: ["human-decision:fail"]
  });
}
function evaluateWorkflowTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution: execution2
}) {
  if (!snapshot) return deny("WORKFLOW_NOT_FOUND", "workflow_not_found");
  if (snapshot.governanceMode !== "governed" || snapshot.instance?.governanceMode !== "governed")
    return deny("WORKFLOW_NOT_GOVERNED", "workflow_not_governed");
  if (!definition) return deny("WORKFLOW_DEFINITION_NOT_FOUND", "definition_not_found");
  const instance = snapshot.instance;
  if (instance.workflowType !== definition.workflowType || instance.definitionVersion !== definition.version || instance.definitionHash !== definition.definitionHash || snapshot.definitionVersion !== definition.version || snapshot.definitionHash !== definition.definitionHash)
    return deny("WORKFLOW_DEFINITION_MISMATCH", "definition_identity_mismatch");
  const declared = definition.steps.find((step) => step.id === request.stepId);
  const current = instance.steps.find((step) => step.id === request.stepId);
  if (!declared || !current) return deny("STEP_NOT_FOUND", "step_not_found");
  if (request.expectedRevision !== snapshot.revision || instance.revision !== snapshot.revision)
    return deny("REVISION_CONFLICT", "revision_mismatch");
  if (!WORKFLOW_TRANSITIONS[current.status]?.includes(request.requestedStatus))
    return deny("ILLEGAL_TRANSITION", "transition_not_allowed");
  if (["ready", "running", "completed"].includes(request.requestedStatus)) {
    const missing = declared.dependsOn.filter(
      (id2) => instance.steps.find((step) => step.id === id2)?.status !== "completed"
    );
    if (missing.length)
      return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", { missingEvidence: missing });
  }
  const factoryOracle = declared.responsibility.kind === "code" && execution2.kind === "factory-oracle" && execution2.runtimeId === "factory-dashboard";
  const factoryHuman = declared.responsibility.kind === "human" && execution2.kind === "factory-human" && execution2.runtimeId === "factory-dashboard" && typeof execution2.actorId === "string" && execution2.actorId.length > 0;
  const factoryRetry = declared.responsibility.kind === "agent" && current.status === "blocked" && request.requestedStatus === "ready" && execution2.kind === "factory-control-plane" && execution2.runtimeId === "factory-dashboard" && execution2.agentId === "factory-runner" && typeof execution2.actorId === "string" && execution2.actorId.length > 0;
  if (declared.responsibility.kind !== "agent" && !factoryOracle && !factoryHuman)
    return deny("ACTOR_NOT_AUTHORIZED", "runtime_cannot_transition_step_responsibility");
  if (declared.responsibility.kind === "agent" && !factoryRetry && declared.responsibility.name && declared.responsibility.name !== execution2.agentId)
    return deny("ACTOR_NOT_AUTHORIZED", "agent_responsibility_mismatch");
  if (factoryHuman && current.status !== "waiting_human") return deny("INTERACTION_STALE", "human_step_is_not_waiting");
  const selected = [];
  for (const id2 of request.evidenceIds) {
    const item = evidence.find((candidate) => candidate.evidenceId === id2);
    if (!item) return deny("EVIDENCE_NOT_FOUND", "evidence_not_found", { missingEvidence: [id2] });
    if (item.namespaceId !== execution2.namespaceId || item.workflowId !== request.workflowId || item.stepId !== request.stepId)
      return deny("EVIDENCE_SCOPE_MISMATCH", "evidence_scope_mismatch");
    selected.push(item);
  }
  if (request.requestedStatus === "blocked" && declared.responsibility.kind === "agent") {
    const negative = selected.find(
      (item) => item.kind === "agent-result" && ["fail", "indeterminate"].includes(item.outcome ?? "") && item.source?.kind === execution2.kind && item.source?.runtimeId === execution2.runtimeId && item.source?.agentId === execution2.agentId && item.source?.caseId === execution2.caseId && item.source?.threadId === execution2.threadId
    );
    if (!negative)
      return deny("NEGATIVE_EVIDENCE_REQUIRED", "matching_agent_result_negative_required", {
        missingEvidence: ["agent-result:fail-or-indeterminate"]
      });
  }
  if (request.requestedStatus === "ready" && current.status === "blocked") {
    const controller = instance.controllerExecution ?? snapshot.controllerExecution;
    if (execution2.kind !== "factory-control-plane" || execution2.runtimeId !== "factory-dashboard" || execution2.agentId !== "factory-runner" || typeof execution2.actorId !== "string" || execution2.actorId.length === 0 || !controller || controller.caseId !== execution2.caseId)
      return deny("ACTOR_NOT_AUTHORIZED", "manual_retry_requires_factory_controller_and_human_actor");
    const retry = selected.find(
      (item) => item.kind === "human-decision" && item.outcome === "pass" && item.source?.kind === "factory-human" && typeof item.source?.actorId === "string" && item.source.actorId.length > 0
    );
    if (!retry)
      return deny("RETRY_EVIDENCE_REQUIRED", "trusted_manual_retry_evidence_required", {
        missingEvidence: ["human-decision:pass"]
      });
  }
  if (request.requestedStatus === "completed") {
    if (factoryHuman) {
      const decision = selected.find(
        (item) => item.kind === "human-decision" && item.outcome === "pass" && item.source?.kind === "factory-human" && item.source?.actorId === execution2.actorId
      );
      if (!decision)
        return deny("PASS_EVIDENCE_REQUIRED", "matching_human_decision_required", {
          missingEvidence: ["human-decision:pass"]
        });
    } else if (factoryOracle) {
      const pass2 = selected.find(
        (item) => item.kind === "oracle-result" && item.outcome === "pass" && item.source?.kind === "factory-oracle" && item.facts?.oracleId === declared.responsibility.name
      );
      if (!pass2)
        return deny("PASS_EVIDENCE_REQUIRED", "matching_oracle_result_pass_required", {
          missingEvidence: ["oracle-result:pass"]
        });
    } else {
      if (selected.some((item) => item.kind === "agent-result" && ["fail", "indeterminate"].includes(item.outcome ?? "")))
        return deny("EVIDENCE_NEGATIVE", "agent_result_not_pass");
      const pass2 = selected.find(
        (item) => item.kind === "agent-result" && item.outcome === "pass" && item.source?.kind === execution2.kind && item.source?.runtimeId === execution2.runtimeId && item.source?.agentId === execution2.agentId && item.source?.caseId === execution2.caseId && item.source?.threadId === execution2.threadId
      );
      if (!pass2)
        return deny("PASS_EVIDENCE_REQUIRED", "matching_agent_result_pass_required", {
          missingEvidence: ["agent-result:pass"]
        });
    }
  }
  return { allowed: true };
}
function applyWorkflowTransition(snapshot, definition, request, observedAt = (/* @__PURE__ */ new Date()).toISOString()) {
  const previous = new Map(snapshot.instance.steps.map((step) => [step.id, step.status]));
  previous.set(request.stepId, request.requestedStatus);
  if (request.requestedStatus === "completed") {
    for (const step of definition.steps)
      if (previous.get(step.id) === "pending" && step.dependsOn.every((id2) => previous.get(id2) === "completed"))
        previous.set(step.id, "ready");
  }
  const statuses = [...previous.values()];
  const status = statuses.every((status2) => status2 === "completed") ? "completed" : statuses.some((status2) => status2 === "failed") ? "failed" : statuses.some((status2) => status2 === "waiting_human") ? "waiting_human" : statuses.some((status2) => status2 === "blocked") ? "blocked" : statuses.some((status2) => status2 === "running") ? "running" : statuses.some((status2) => status2 === "ready") ? "ready" : "pending";
  const revision = snapshot.revision + 1;
  const instance = {
    ...snapshot.instance,
    revision,
    status,
    steps: snapshot.instance.steps.map((step) => ({ ...step, status: previous.get(step.id) })),
    updatedAt: observedAt
  };
  const projection = {
    ...snapshot.projection,
    status,
    steps: snapshot.projection.steps.map((step) => ({ ...step, status: previous.get(step.id) }))
  };
  return { ...snapshot, instance, projection, revision };
}
function applyHumanCheckpointOpen(snapshot, definition, request, observedAt = (/* @__PURE__ */ new Date()).toISOString()) {
  const statuses = new Map(snapshot.instance.steps.map((step) => [step.id, step.status]));
  statuses.set(request.stepId, "waiting_human");
  const revision = snapshot.revision + 1;
  const instance = {
    ...snapshot.instance,
    revision,
    status: "waiting_human",
    steps: snapshot.instance.steps.map((step) => ({ ...step, status: statuses.get(step.id) })),
    updatedAt: observedAt
  };
  const projection = {
    ...snapshot.projection,
    status: "waiting_human",
    steps: snapshot.projection.steps.map((step) => ({ ...step, status: statuses.get(step.id) }))
  };
  return { ...snapshot, instance, projection, revision };
}

// ../src/domain/evidence/workflow-evidence.ts
import { randomUUID as randomUUID2 } from "node:crypto";
var WORKFLOW_EVIDENCE_KINDS = Object.freeze([
  "agent-result",
  "artifact",
  "oracle-result",
  "human-decision"
]);
var WORKFLOW_EVIDENCE_OUTCOMES = Object.freeze(["pass", "fail", "indeterminate"]);
var WORKFLOW_EVIDENCE_LIMITS = Object.freeze({
  idempotencyKey: 128,
  artifactRef: 1024,
  facts: 32,
  factKey: 64,
  factValue: 256
});
var SAFE_ID3 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var HASH = /^sha256:[0-9a-f]{64}$/;
var INPUT_FIELDS = /* @__PURE__ */ new Set([
  "workflowId",
  "stepId",
  "kind",
  "outcome",
  "artifactRef",
  "artifactHash",
  "facts",
  "idempotencyKey"
]);
var FACT_KEYS = /* @__PURE__ */ new Set([
  "resultCode",
  "category",
  "attempt",
  "durationMs",
  "itemCount",
  "oracleId",
  "oracleVersion",
  "oracleHash",
  "commandId",
  "cwdId",
  "exitCode",
  "signal",
  "timedOut",
  "classification",
  "executed",
  "fromCache",
  "upToDate",
  "skipped",
  "outputHash",
  "outputTruncated",
  "interactionId",
  "actionId",
  "decisionTextHash",
  "briefHash",
  "claimsHash",
  "diffHash",
  "reviewPackageHash",
  "finalizationTurns"
]);
function invalid(path, reason = "invalid_value") {
  return { ok: false, error: { code: "INVALID_EVIDENCE", path, reason } };
}
function boundedText(value, maximum) {
  return typeof value === "string" && value.length > 0 && value.length <= maximum && !/[\r\n]/.test(value);
}
function isEvidenceKind(value) {
  return typeof value === "string" && WORKFLOW_EVIDENCE_KINDS.includes(value);
}
function isEvidenceOutcome(value) {
  return typeof value === "string" && WORKFLOW_EVIDENCE_OUTCOMES.includes(value);
}
function validateWorkflowEvidenceInput(input, expectedWorkflowId) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return invalid("$", "not_object");
  const record2 = input;
  if (Object.keys(record2).some((field) => !INPUT_FIELDS.has(field))) return invalid("$", "unknown_field");
  if (record2.workflowId !== expectedWorkflowId || !SAFE_ID3.test(String(record2.workflowId ?? "")))
    return invalid("workflowId");
  if (!SAFE_ID3.test(String(record2.stepId ?? ""))) return invalid("stepId");
  if (!isEvidenceKind(record2.kind)) return invalid("kind");
  if (record2.idempotencyKey !== void 0 && !boundedText(record2.idempotencyKey, WORKFLOW_EVIDENCE_LIMITS.idempotencyKey))
    return invalid("idempotencyKey");
  if (record2.kind === "artifact") {
    if (record2.outcome !== void 0 || record2.facts !== void 0) return invalid("$", "artifact_fields");
    if (!boundedText(record2.artifactRef, WORKFLOW_EVIDENCE_LIMITS.artifactRef)) return invalid("artifactRef");
    if (!HASH.test(String(record2.artifactHash ?? ""))) return invalid("artifactHash");
    return {
      ok: true,
      value: {
        workflowId: record2.workflowId,
        stepId: record2.stepId,
        kind: "artifact",
        artifactRef: record2.artifactRef,
        artifactHash: record2.artifactHash,
        ...record2.idempotencyKey ? { idempotencyKey: record2.idempotencyKey } : {}
      }
    };
  }
  if (["oracle-result", "human-decision"].includes(record2.kind) && record2.outcome === void 0)
    return invalid("outcome");
  if (record2.artifactRef !== void 0 || record2.artifactHash !== void 0) return invalid("$", "agent_result_fields");
  if (record2.outcome !== void 0 && !isEvidenceOutcome(record2.outcome)) return invalid("outcome");
  if (!record2.facts || typeof record2.facts !== "object" || Array.isArray(record2.facts)) return invalid("facts");
  const entries = Object.entries(record2.facts);
  if (entries.length === 0 || entries.length > WORKFLOW_EVIDENCE_LIMITS.facts) return invalid("facts");
  for (const [key, value] of entries) {
    if (!FACT_KEYS.has(key) || key.length > WORKFLOW_EVIDENCE_LIMITS.factKey)
      return invalid(`facts.${key}`, "unsupported_fact");
    if (!(typeof value === "boolean" || typeof value === "number" && Number.isSafeInteger(value) || boundedText(value, WORKFLOW_EVIDENCE_LIMITS.factValue)))
      return invalid(`facts.${key}`);
  }
  return {
    ok: true,
    value: {
      workflowId: record2.workflowId,
      stepId: record2.stepId,
      kind: record2.kind,
      ...record2.outcome ? { outcome: record2.outcome } : {},
      facts: { ...record2.facts },
      ...record2.idempotencyKey ? { idempotencyKey: record2.idempotencyKey } : {}
    }
  };
}
function createWorkflowEvidence(validated, namespaceId, source, observedAt = (/* @__PURE__ */ new Date()).toISOString(), evidenceId = randomUUID2()) {
  const { idempotencyKey, ...rest } = validated;
  const record2 = {
    evidenceId,
    namespaceId,
    ...rest,
    source: Object.freeze({ ...source }),
    observedAt
  };
  return Object.freeze(record2);
}

// ../src/domain/interaction/workflow-human-interaction.ts
import { createHash as createHash4 } from "node:crypto";
var HUMAN_INTERACTION_KINDS = Object.freeze(["approval", "choice", "text"]);
var WORKFLOW_HUMAN_INTERACTION_STATUSES = Object.freeze(["opening", "open", "replied", "aborted"]);
var SAFE_ID4 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var KINDS2 = new Set(HUMAN_INTERACTION_KINDS);
function canonicalHumanInteractionInput(value) {
  if (Array.isArray(value)) return value.map((entry) => canonicalHumanInteractionInput(entry));
  if (value && typeof value === "object") {
    const record2 = value;
    return Object.fromEntries(
      Object.keys(record2).sort().map((key) => [key, canonicalHumanInteractionInput(record2[key])])
    );
  }
  return value;
}
function humanInteractionSemanticHash(input) {
  return createHash4("sha256").update(
    JSON.stringify(
      canonicalHumanInteractionInput({
        workflowId: input.workflowId,
        stepId: input.stepId,
        expectedRevision: input.expectedRevision,
        kind: input.kind,
        prompt: input.prompt,
        actions: input.actions,
        interactionType: input.interactionType,
        reasonCode: input.reasonCode
      })
    )
  ).digest("hex");
}
function validateHumanInteractionOpenInput(input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return null;
  const record2 = input;
  const rawActions = record2.actions;
  const actionsValid = Array.isArray(rawActions) && rawActions.length === 2 && new Set(rawActions.map((action) => action?.id)).size === 2 && rawActions.every((action) => {
    if (!action || typeof action !== "object" || Array.isArray(action)) return false;
    const candidate = action;
    return SAFE_ID4.test(typeof candidate.id === "string" ? candidate.id : "") && typeof candidate.label === "string" && !!candidate.label && candidate.label.length <= 128 && WORKFLOW_STATUSES.includes(candidate.requestedStatus);
  });
  if (!SAFE_ID4.test(String(record2.workflowId ?? "")) || !SAFE_ID4.test(String(record2.stepId ?? "")) || !KINDS2.has(record2.kind) || !Number.isSafeInteger(record2.expectedRevision) || record2.expectedRevision < 1 || typeof record2.prompt !== "string" || !record2.prompt || record2.prompt.length > 2e3 || !actionsValid || typeof record2.idempotencyKey !== "string" || !record2.idempotencyKey || record2.idempotencyKey.length > 128 || /[\r\n]/.test(record2.idempotencyKey) || record2.interactionId !== void 0 && !SAFE_ID4.test(String(record2.interactionId)))
    return null;
  return {
    workflowId: record2.workflowId,
    stepId: record2.stepId,
    expectedRevision: record2.expectedRevision,
    kind: record2.kind,
    prompt: record2.prompt,
    actions: rawActions.map((action) => ({
      id: action.id,
      label: action.label,
      requestedStatus: action.requestedStatus
    })),
    idempotencyKey: record2.idempotencyKey,
    ...record2.interactionId ? { interactionId: record2.interactionId } : {},
    ...record2.interactionType ? { interactionType: record2.interactionType } : {},
    ...record2.reasonCode ? { reasonCode: record2.reasonCode } : {}
  };
}
function openedInteractionRevision(event) {
  return event.interaction?.revision ?? event.revision;
}

// ../src/domain/agent-attempt/agent-step-attempt.ts
var AGENT_STEP_ATTEMPT_STATUSES = Object.freeze([
  "starting",
  "running",
  "succeeded",
  "failed",
  "indeterminate",
  "interrupted"
]);
var AGENT_STEP_ATTEMPT_TERMINAL_STATUSES = Object.freeze([
  "succeeded",
  "failed",
  "indeterminate",
  "interrupted"
]);
var AGENT_STEP_ATTEMPT_TRANSITIONS = Object.freeze({
  starting: Object.freeze(["running", "failed", "interrupted"]),
  running: Object.freeze(["succeeded", "failed", "indeterminate", "interrupted"])
});
var AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS = Object.freeze([
  "workflowId",
  "workflowRevisionAtStart",
  "stepId",
  "attemptNumber",
  "namespaceId",
  "runtimeId",
  "agentName",
  "briefHash",
  "startedAt"
]);
var SAFE_ID5 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var BRIEF_HASH = /^sha256:[0-9a-f]{64}$/;
function isAgentStepAttemptStatus(value) {
  return typeof value === "string" && AGENT_STEP_ATTEMPT_STATUSES.includes(value);
}
function isAgentStepAttemptTerminal(value) {
  return typeof value === "string" && AGENT_STEP_ATTEMPT_TERMINAL_STATUSES.includes(value);
}
function isValidAgentStepAttemptInstant(value) {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}
function validateAgentStepAttempt(attempt) {
  const record2 = attempt ?? {};
  if (!attempt || typeof attempt !== "object" || !SAFE_ID5.test(String(record2.attemptId ?? "")) || !SAFE_ID5.test(String(record2.workflowId ?? "")) || !SAFE_ID5.test(String(record2.stepId ?? "")) || !SAFE_ID5.test(String(record2.namespaceId ?? "")) || typeof record2.runtimeId !== "string" || !record2.runtimeId || typeof record2.agentName !== "string" || !record2.agentName || !BRIEF_HASH.test(String(record2.briefHash ?? "")) || !Number.isSafeInteger(record2.workflowRevisionAtStart) || record2.workflowRevisionAtStart < 1 || !Number.isSafeInteger(record2.attemptNumber) || record2.attemptNumber < 1 || !isAgentStepAttemptStatus(record2.status) || !isValidAgentStepAttemptInstant(record2.startedAt))
    throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record2.caseId !== null && typeof record2.caseId !== "string") throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  const terminal = isAgentStepAttemptTerminal(record2.status);
  if (terminal !== isValidAgentStepAttemptInstant(record2.finishedAt) || !terminal && record2.finishedAt !== null)
    throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record2.status === "starting" && record2.caseId !== null) throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record2.status !== "starting" && !record2.caseId) throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  return attempt;
}

// ../src/domain/agent-attempt/agent-step-result.ts
import { createHash as createHash5, timingSafeEqual } from "node:crypto";
var AGENT_STEP_RESULT_STATUSES = Object.freeze(["PASS", "FAIL"]);
var AGENT_STEP_RESULT_LIMITS = Object.freeze({
  summary: 2e3,
  modifiedFiles: 1e3,
  modifiedFileLength: 1024,
  artifacts: 8,
  artifactKind: 128,
  artifactContentBytes: 262144,
  findings: 100,
  findingCode: 128,
  findingSummary: 1e3,
  findingFile: 1024
});
var SAFE_ID6 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var BUSINESS_FIELDS = /* @__PURE__ */ new Set(["status", "summary", "artifacts", "claims", "findings"]);
var ARTIFACT_FIELDS = /* @__PURE__ */ new Set(["kind", "encoding", "content"]);
var FINDING_FIELDS = /* @__PURE__ */ new Set(["severity", "code", "summary", "file", "line"]);
var FINDING_SEVERITIES = /* @__PURE__ */ new Set(["info", "warning", "error", "blocking"]);
function isSafeAgentStepResultId(value) {
  return SAFE_ID6.test(String(value ?? ""));
}
function sha256(value) {
  return `sha256:${createHash5("sha256").update(value).digest("hex")}`;
}
function canonicalizeAgentStepResult(value) {
  if (Array.isArray(value)) return value.map((entry) => canonicalizeAgentStepResult(entry));
  if (value !== null && typeof value === "object") {
    const record2 = value;
    return Object.fromEntries(
      Object.keys(record2).sort().map((key) => [key, canonicalizeAgentStepResult(record2[key])])
    );
  }
  return value;
}
function canonicalAgentStepResultJson(value) {
  return JSON.stringify(canonicalizeAgentStepResult(value));
}
function hashAgentStepResult(value) {
  return sha256(canonicalAgentStepResultJson(value));
}
function hashStructuredAgentResult(value) {
  return sha256(canonicalAgentStepResultJson(value));
}
function hashAgentBrief(brief) {
  return sha256(brief);
}
function safeEqual(a, b) {
  try {
    const x = Buffer.from(a);
    const y = Buffer.from(b);
    return x.length === y.length && timingSafeEqual(x, y);
  } catch {
    return false;
  }
}
function agentStepAttemptKey(namespaceId, storageId, attemptId) {
  return `${namespaceId}\0${storageId}\0${attemptId}`;
}
function validateAgentStepResultBusiness(value) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some((key) => !BUSINESS_FIELDS.has(key)))
    return false;
  const record2 = value;
  if (!["PASS", "FAIL"].includes(record2.status) || typeof record2.summary !== "string" || record2.summary.length === 0 || record2.summary.length > AGENT_STEP_RESULT_LIMITS.summary)
    return false;
  const claims = record2.claims;
  if (!claims || typeof claims !== "object" || Array.isArray(claims) || Object.keys(claims).some((key) => key !== "modifiedFiles") || !Array.isArray(claims.modifiedFiles) || claims.modifiedFiles.length > AGENT_STEP_RESULT_LIMITS.modifiedFiles || claims.modifiedFiles.some(
    (file) => typeof file !== "string" || file.length === 0 || file.length > AGENT_STEP_RESULT_LIMITS.modifiedFileLength
  ))
    return false;
  const artifacts = record2.artifacts;
  if (artifacts !== void 0 && (!Array.isArray(artifacts) || artifacts.length > AGENT_STEP_RESULT_LIMITS.artifacts || artifacts.some(
    (artifact2) => !artifact2 || typeof artifact2 !== "object" || Array.isArray(artifact2) || Object.keys(artifact2).some((key) => !ARTIFACT_FIELDS.has(key)) || typeof artifact2.kind !== "string" || artifact2.kind.length === 0 || artifact2.kind.length > AGENT_STEP_RESULT_LIMITS.artifactKind || artifact2.encoding !== "markdown" || typeof artifact2.content !== "string" || artifact2.content.length === 0 || Buffer.byteLength(artifact2.content, "utf8") > AGENT_STEP_RESULT_LIMITS.artifactContentBytes
  )))
    return false;
  const findings = record2.findings;
  if (findings !== void 0 && (!Array.isArray(findings) || findings.length > AGENT_STEP_RESULT_LIMITS.findings || findings.some((finding) => {
    if (!finding || typeof finding !== "object" || Array.isArray(finding)) return true;
    const entry = finding;
    if (Object.keys(entry).some((key) => !FINDING_FIELDS.has(key))) return true;
    if (!FINDING_SEVERITIES.has(entry.severity)) return true;
    if (typeof entry.code !== "string" || entry.code.length === 0 || entry.code.length > AGENT_STEP_RESULT_LIMITS.findingCode)
      return true;
    if (typeof entry.summary !== "string" || entry.summary.length === 0 || entry.summary.length > AGENT_STEP_RESULT_LIMITS.findingSummary)
      return true;
    if (entry.file !== void 0 && (typeof entry.file !== "string" || entry.file.length === 0 || entry.file.length > AGENT_STEP_RESULT_LIMITS.findingFile))
      return true;
    if (entry.line !== void 0 && (!Number.isSafeInteger(entry.line) || entry.line < 1)) return true;
    return false;
  })))
    return false;
  return true;
}

// ../src/domain/environment/work-unit-environment.ts
import { isAbsolute, normalize, resolve } from "node:path";
var WORK_UNIT_ENVIRONMENT_STATES = Object.freeze([
  "provisioning",
  "active",
  "completed",
  "abandoned",
  "error",
  "removed"
]);
var WORK_UNIT_ENVIRONMENT_ERROR_CODES = Object.freeze({
  INVALID_ENVIRONMENT: "INVALID_ENVIRONMENT",
  INVALID_SCHEMA_VERSION: "INVALID_SCHEMA_VERSION",
  INVALID_ID: "INVALID_ID",
  INVALID_UUID: "INVALID_UUID",
  INVALID_PATH: "INVALID_PATH",
  INVALID_REF: "INVALID_REF",
  INVALID_SHA: "INVALID_SHA",
  INVALID_INSTANT: "INVALID_INSTANT",
  INVALID_STATE: "INVALID_STATE"
});
var SAFE = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/;
var UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SHA = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var REF = /^(?!\/|.*(?:\.\.|@\{|\\|[ ~^:?*\[]|\/\/|\.$|\.lock(?:\/|$)))(?!.*\/$)[A-Za-z0-9._\/-]+$/;
var STATES = new Set(WORK_UNIT_ENVIRONMENT_STATES);
function fail(code, path) {
  return { ok: false, error: { code, path } };
}
function safe(value, path, optional = false) {
  if (optional && value === void 0) return { ok: true };
  return typeof value === "string" && SAFE.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ID, path);
}
function validateNamespaceId(value, path = "namespaceId") {
  return typeof value === "string" && UUID.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, path);
}
function validateCanonicalAbsolutePath(value, path = "path") {
  if (typeof value !== "string" || !isAbsolute(value) || normalize(value) !== value || resolve(value) !== value)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, path);
  return { ok: true };
}
function validateGitRef(value, path = "ref") {
  return typeof value === "string" && value.length <= 255 && REF.test(value) ? { ok: true } : fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_REF, path);
}
function validateIsoInstant(value, path = "instant") {
  if (typeof value !== "string") return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path);
  const milliseconds = Date.parse(value);
  if (!Number.isFinite(milliseconds)) return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path);
  if (new Date(milliseconds).toISOString() !== value)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_INSTANT, path);
  return { ok: true };
}
function validateWorkUnitEnvironment(input) {
  if (!input || typeof input !== "object" || Array.isArray(input))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_ENVIRONMENT, "$");
  const record2 = input;
  if (record2.schemaVersion !== "1")
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SCHEMA_VERSION, "schemaVersion");
  for (const field of ["environmentId", "workUnitId", "createdBy"]) {
    const result = safe(record2[field], field);
    if (!result.ok) return result;
  }
  const workflow = safe(record2.workflowId, "workflowId", true);
  if (!workflow.ok) return workflow;
  for (const field of ["businessRef", "businessType"]) {
    const result = safe(record2[field], field, true);
    if (!result.ok) return result;
  }
  const namespace = validateNamespaceId(record2.namespaceId);
  if (!namespace.ok) return namespace;
  if (record2.parentCaseId !== void 0 && !UUID.test(record2.parentCaseId))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, "parentCaseId");
  for (const field of ["repoRoot", "worktreePath"]) {
    const result = validateCanonicalAbsolutePath(record2[field], field);
    if (!result.ok) return result;
  }
  if (record2.repoRoot === record2.worktreePath)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, "worktreePath");
  for (const field of ["integrationBranch", "branch"]) {
    const result = validateGitRef(record2[field], field);
    if (!result.ok) return result;
  }
  if (record2.baseCommit !== null && !SHA.test(record2.baseCommit ?? ""))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, "baseCommit");
  if (record2.baseCommit === null && record2.lifecycleState !== "provisioning")
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, "baseCommit");
  const createdAt = validateIsoInstant(record2.createdAt, "createdAt");
  if (!createdAt.ok) return createdAt;
  if (!STATES.has(record2.lifecycleState))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "lifecycleState");
  if (record2.lifecycleState === "active" && !record2.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "parentCaseId");
  if (record2.lifecycleState === "provisioning" && record2.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "parentCaseId");
  const environment = {
    schemaVersion: "1",
    environmentId: record2.environmentId,
    workUnitId: record2.workUnitId,
    ...record2.workflowId ? { workflowId: record2.workflowId } : {},
    namespaceId: record2.namespaceId,
    ...record2.parentCaseId ? { parentCaseId: record2.parentCaseId } : {},
    ...record2.businessRef ? { businessRef: record2.businessRef } : {},
    ...record2.businessType ? { businessType: record2.businessType } : {},
    repoRoot: record2.repoRoot,
    integrationBranch: record2.integrationBranch,
    branch: record2.branch,
    worktreePath: record2.worktreePath,
    baseCommit: record2.baseCommit,
    createdAt: record2.createdAt,
    createdBy: record2.createdBy,
    lifecycleState: record2.lifecycleState
  };
  return { ok: true, environment };
}

// ../src/infrastructure/storage/storage-kernel.ts
import { createHash as createHash6, randomBytes as randomBytes2 } from "node:crypto";
import { appendFile, mkdir, open, readFile, rename, rm } from "node:fs/promises";
import { dirname as dirname2, join as join2 } from "node:path";
var STORAGE_FORMAT_VERSION = 1;
var STORAGE_KERNEL_ERROR_CODES = Object.freeze({
  INVALID_PATH: "INVALID_PATH",
  LOCK_HELD: "LOCK_HELD",
  READ_FAILED: "READ_FAILED",
  WRITE_FAILED: "WRITE_FAILED",
  CORRUPT_RECORD: "CORRUPT_RECORD",
  UNSUPPORTED_FORMAT_VERSION: "UNSUPPORTED_FORMAT_VERSION"
});
var StorageKernelError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "StorageKernelError";
    this.code = code;
    this.details = details;
  }
};
function storageErrorCode(error2) {
  const code = error2?.code;
  return typeof code === "string" ? code : void 0;
}
function isNotFoundError(error2) {
  return storageErrorCode(error2) === "ENOENT";
}
function wrapStorageError(code, details = {}) {
  return (cause) => cause instanceof StorageKernelError ? cause : new StorageKernelError(code, details, cause);
}
async function syncDirectory(directoryPath) {
  const directory = await open(directoryPath, "r");
  try {
    await directory.sync();
  } finally {
    await directory.close();
  }
}
function atomicTemporaryPath(filePath) {
  return `${filePath}.tmp-${process.pid}-${randomBytes2(6).toString("hex")}`;
}
async function atomicWriteJson(filePath, value) {
  await mkdir(dirname2(filePath), { recursive: true });
  const temporary = atomicTemporaryPath(filePath);
  const handle = await open(temporary, "wx", 384);
  try {
    await handle.writeFile(`${JSON.stringify(value)}
`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, filePath);
  await syncDirectory(dirname2(filePath));
}
async function appendDurableJson(filePath, value, options = {}) {
  if (options.ensureDirectory) await mkdir(dirname2(filePath), { recursive: true });
  await appendFile(filePath, `${JSON.stringify(value)}
`, { encoding: "utf8", mode: 384 });
  const handle = await open(filePath, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}
async function readJsonLines(filePath) {
  let text2;
  try {
    text2 = await readFile(filePath, "utf8");
  } catch (error2) {
    if (isNotFoundError(error2)) return [];
    throw error2;
  }
  return text2.split("\n").filter(Boolean).map((line) => JSON.parse(line));
}
function canonicalize2(value) {
  if (Array.isArray(value)) return value.map((entry) => canonicalize2(entry));
  if (value !== null && typeof value === "object") {
    const record2 = value;
    return Object.fromEntries(
      Object.keys(record2).sort().map((key) => [key, canonicalize2(record2[key])])
    );
  }
  return value;
}
function canonicalJson(value) {
  return JSON.stringify(canonicalize2(value));
}
function computeCanonicalHash(value) {
  return createHash6("sha256").update(canonicalJson(value), "utf8").digest("hex");
}
var KeyedLock = class {
  locks = /* @__PURE__ */ new Map();
  run(key, action) {
    const prior = this.locks.get(key) ?? Promise.resolve();
    const operation = prior.then(() => action());
    const tail = operation.then(
      () => void 0,
      () => void 0
    );
    this.locks.set(key, tail);
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key);
    });
  }
  /** Number of keys with an in-flight or queued action (diagnostics only). */
  get size() {
    return this.locks.size;
  }
};
function createKeyedLock() {
  return new KeyedLock();
}
var DEFAULT_PROCESS_LOCK_FILE = ".process.lock";
async function acquireProcessLock(dataRoot, options = {}) {
  if (typeof dataRoot !== "string" || dataRoot.length === 0)
    throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.INVALID_PATH, { dataRoot });
  const lockPath = join2(dataRoot, options.lockFileName ?? DEFAULT_PROCESS_LOCK_FILE);
  await mkdir(dataRoot, { recursive: true });
  let handle;
  try {
    handle = await open(lockPath, "wx", 384);
  } catch (error2) {
    if (storageErrorCode(error2) === "EEXIST")
      throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.LOCK_HELD, { path: lockPath }, error2);
    throw error2;
  }
  try {
    await handle.writeFile(`${JSON.stringify({ pid: process.pid, acquiredAt: (/* @__PURE__ */ new Date()).toISOString() })}
`, "utf8");
    await handle.sync();
  } catch (error2) {
    await handle.close();
    await rm(lockPath, { force: true });
    throw error2;
  }
  let released = false;
  return {
    path: lockPath,
    pid: process.pid,
    async release() {
      if (released) return;
      released = true;
      try {
        await handle.close();
      } finally {
        await rm(lockPath, { force: true });
      }
    }
  };
}
async function withProcessLock(dataRoot, action, options = {}) {
  const lock = await acquireProcessLock(dataRoot, options);
  try {
    return await action(lock);
  } finally {
    await lock.release();
  }
}
function withFormatVersion(value, formatVersion = STORAGE_FORMAT_VERSION) {
  return { ...value, formatVersion };
}
function readFormatVersion(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const raw = value.formatVersion;
  return typeof raw === "number" && Number.isSafeInteger(raw) ? raw : null;
}
function assertSupportedFormatVersion(value, supported = STORAGE_FORMAT_VERSION) {
  const version = readFormatVersion(value);
  if (version !== null && version > supported)
    throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.UNSUPPORTED_FORMAT_VERSION, { version, supported });
  return version;
}

// ../src/ports/persistence/workflow-definition-repository.ts
var WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES = Object.freeze({
  WORKFLOW_DEFINITION_NOT_FOUND: "WORKFLOW_DEFINITION_NOT_FOUND",
  INVALID_DEFINITION_FILE: "INVALID_DEFINITION_FILE",
  DEFINITION_PATH_MISMATCH: "DEFINITION_PATH_MISMATCH",
  DEFINITION_COLLISION: "DEFINITION_COLLISION"
});

// ../src/adapters/persistence/filesystem-workflow-definition-repository.ts
var WorkflowDefinitionRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "WorkflowDefinitionRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var FilesystemWorkflowDefinitionRepository = class {
  constructor(registry2) {
    this.registry = registry2;
  }
  list() {
    return this.registry.list();
  }
  get(workflowType, version) {
    return this.registry.get(workflowType, version);
  }
  resolveUnique(workflowType) {
    return this.registry.resolveUnique(workflowType);
  }
};
function createFilesystemWorkflowDefinitionRepository(registry2) {
  return new FilesystemWorkflowDefinitionRepository(registry2);
}

// ../src/adapters/persistence/filesystem-workflow-instance-repository.ts
var WorkflowInstanceRepositoryError = class extends Error {
  code;
  details;
  decision;
  constructor(code, details = {}, decision) {
    super(code);
    this.name = "WorkflowInstanceRepositoryError";
    this.code = code;
    this.details = details;
    this.decision = decision;
  }
};
var FilesystemWorkflowInstanceRepository = class {
  constructor(store) {
    this.store = store;
  }
  #initialized = null;
  /** Ensures the store's root directories exist before a mutating operation. */
  async #ensureInitialized() {
    if (!this.store.initialize) return;
    this.#initialized ??= Promise.resolve(this.store.initialize()).then(() => void 0);
    await this.#initialized;
  }
  async list(namespaceId) {
    const snapshots = await this.store.list(namespaceId);
    return snapshots.map((snapshot) => snapshot.projection);
  }
  async get(namespaceId, workflowId) {
    const snapshot = await this.store.read(namespaceId, workflowId);
    return snapshot ? { instance: snapshot.instance, projection: snapshot.projection } : null;
  }
  async create(namespaceId, command, definition, controllerExecution) {
    await this.#ensureInitialized();
    const result = await this.store.start(namespaceId, command, definition, controllerExecution);
    return this.#requireSnapshot(result, "WORKFLOW_INSTANCE_CREATE_FAILED");
  }
  async transition(namespaceId, workflowId, transition) {
    await this.#ensureInitialized();
    const input = transition ?? {};
    const options = input.fault || input.policy ? { fault: input.fault, policy: input.policy } : void 0;
    const result = await this.store.transition(
      namespaceId,
      input.request,
      input.definition,
      input.evidence ?? [],
      input.execution,
      options
    );
    const snapshot = this.#requireSnapshot(result, "WORKFLOW_INSTANCE_TRANSITION_FAILED");
    return snapshot;
  }
  async remove(namespaceId, workflowId, actor) {
    await this.#ensureInitialized();
    this.#requireOk(await this.store.remove(namespaceId, workflowId, actor), "WORKFLOW_INSTANCE_REMOVE_FAILED");
  }
  async restore(namespaceId, workflowId, actor) {
    await this.#ensureInitialized();
    this.#requireOk(await this.store.restore(namespaceId, workflowId, actor), "WORKFLOW_INSTANCE_RESTORE_FAILED");
  }
  async purge(namespaceId, workflowId, actor) {
    await this.#ensureInitialized();
    this.#requireOk(await this.store.purge(namespaceId, workflowId, actor), "WORKFLOW_INSTANCE_PURGE_FAILED");
  }
  #requireSnapshot(result, fallbackCode) {
    this.#requireOk(result, fallbackCode);
    const snapshot = result.snapshot;
    if (!snapshot) throw new WorkflowInstanceRepositoryError(fallbackCode, {}, result.decision);
    return { instance: snapshot.instance, projection: snapshot.projection };
  }
  #requireOk(result, fallbackCode) {
    if (result?.ok) return;
    const code = result?.error?.code ?? fallbackCode;
    throw new WorkflowInstanceRepositoryError(code, result?.error?.details ?? {}, result?.decision);
  }
};
function createFilesystemWorkflowInstanceRepository(store) {
  return new FilesystemWorkflowInstanceRepository(store);
}

// ../src/adapters/persistence/filesystem-workflow-evidence-repository.ts
var FilesystemWorkflowEvidenceRepository = class {
  constructor(store) {
    this.store = store;
  }
  list(namespaceId, storageId, filter) {
    return this.store.list(namespaceId, storageId, filter);
  }
  record(namespaceId, storageId, input, source) {
    return this.store.record(namespaceId, storageId, input, source);
  }
};
function createFilesystemWorkflowEvidenceRepository(store) {
  return new FilesystemWorkflowEvidenceRepository(store);
}

// ../src/adapters/persistence/filesystem-workflow-human-interaction-repository.ts
var WorkflowHumanInteractionRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "WorkflowHumanInteractionRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var FilesystemWorkflowHumanInteractionRepository = class {
  constructor(store) {
    this.store = store;
  }
  list(namespaceId, storageId, options) {
    return this.store.list(namespaceId, storageId, options);
  }
  events(namespaceId, storageId) {
    return this.store.events(namespaceId, storageId);
  }
  async reconcileOpen(namespaceId, storageId, input, snapshot, options) {
    const result = await this.store.reconcileOpen(namespaceId, storageId, input, snapshot, options);
    if (!result?.interaction)
      throw new WorkflowHumanInteractionRepositoryError("INTERACTION_RECOVERY_NOT_FOUND", { namespaceId, storageId });
    return result.interaction;
  }
  async recordOpen(namespaceId, storageId, input, options) {
    const transition = options?.transition;
    if (!transition)
      throw new WorkflowHumanInteractionRepositoryError("HUMAN_INTERACTION_TRANSITION_REQUIRED", {
        namespaceId,
        storageId
      });
    const result = await this.store.open(namespaceId, storageId, input, transition);
    const idempotent = Boolean(result.transition?.idempotent);
    return { created: !idempotent, idempotent, interaction: result.interaction };
  }
  async recordTransition(namespaceId, storageId, interactionId, reply, actorId, evidenceId, transitionRequestId, options) {
    const action = options?.action;
    if (!action)
      throw new WorkflowHumanInteractionRepositoryError("HUMAN_INTERACTION_ACTION_REQUIRED", {
        namespaceId,
        storageId,
        interactionId
      });
    const result = await this.store.transact(namespaceId, storageId, interactionId, action);
    return result.interaction;
  }
};
function createFilesystemWorkflowHumanInteractionRepository(store) {
  return new FilesystemWorkflowHumanInteractionRepository(store);
}

// ../src/adapters/persistence/agent-step-attempt-store.ts
import { join as join3 } from "node:path";
var AgentStepAttemptStore = class {
  constructor(dataRoot) {
    this.dataRoot = dataRoot;
    this.locks = createKeyedLock();
  }
  locks;
  path(namespaceId, storageId) {
    return join3(this.dataRoot, "workflows", namespaceId, storageId, "agent-step-attempts.jsonl");
  }
  async list(namespaceId, storageId) {
    return readJsonLines(this.path(namespaceId, storageId));
  }
  async append(namespaceId, storageId, attempt) {
    validateAgentStepAttempt(attempt);
    if (attempt.namespaceId !== namespaceId) throw new Error("AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH");
    const key = `${namespaceId}\0${storageId}`;
    return this.locks.run(key, async () => {
      const events = await this.list(namespaceId, storageId);
      const previous = events.filter((event) => event.attemptId === attempt.attemptId).at(-1);
      if (previous) {
        if (AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS.some((field) => previous[field] !== attempt[field]))
          throw new Error("AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT");
        const allowed = AGENT_STEP_ATTEMPT_TRANSITIONS[previous.status] ?? [];
        if (!allowed.includes(attempt.status)) throw new Error("INVALID_AGENT_STEP_ATTEMPT_TRANSITION");
      } else if (attempt.status !== "starting") throw new Error("AGENT_STEP_ATTEMPT_MUST_START");
      await appendDurableJson(this.path(namespaceId, storageId), attempt, { ensureDirectory: true });
      return attempt;
    });
  }
};

// ../src/adapters/persistence/filesystem-agent-step-attempt-repository.ts
var FilesystemAgentStepAttemptRepository = class {
  constructor(store) {
    this.store = store;
  }
  list(namespaceId, storageId) {
    return this.store.list(namespaceId, storageId);
  }
  append(namespaceId, storageId, attempt) {
    return this.store.append(namespaceId, storageId, attempt);
  }
};
function createFilesystemAgentStepAttemptRepository(store) {
  return new FilesystemAgentStepAttemptRepository(store);
}

// ../src/adapters/persistence/agent-step-result-store.ts
import { randomBytes as randomBytes3, randomUUID as randomUUID3 } from "node:crypto";
import { readdir } from "node:fs/promises";
import { join as join4 } from "node:path";
var IDENTITY_FIELDS = ["attemptId", "workflowId", "stepId", "namespaceId", "caseId", "agentName"];
var CAPABILITY_MATCH_FIELDS = [...IDENTITY_FIELDS, "briefHash"];
var BRIEF_HASH2 = /^sha256:[0-9a-f]{64}$/;
var AgentStepResultStore = class {
  constructor(dataRoot, { clock = () => /* @__PURE__ */ new Date(), ttlMs = 15 * 60 * 1e3 } = {}) {
    this.dataRoot = dataRoot;
    this.clock = clock;
    this.ttlMs = ttlMs;
    this.locks = createKeyedLock();
  }
  clock;
  ttlMs;
  locks;
  capabilityIndex = /* @__PURE__ */ new Map();
  attemptIndex = /* @__PURE__ */ new Map();
  indexLoaded = false;
  initializePromise = null;
  path(namespaceId, storageId) {
    return join4(this.dataRoot, "workflows", namespaceId, storageId, "agent-step-results.jsonl");
  }
  async list(namespaceId, storageId) {
    return readJsonLines(this.path(namespaceId, storageId));
  }
  indexLedger(namespaceId, storageId, events) {
    for (const event of events) {
      if (event.type === "capability-issued") {
        const entry = { namespaceId, storageId, event, result: null };
        this.capabilityIndex.set(event.tokenHash, entry);
        this.attemptIndex.set(agentStepAttemptKey(namespaceId, storageId, event.attemptId), entry);
      } else if (event.type === "result-submitted") {
        const entry = this.attemptIndex.get(agentStepAttemptKey(namespaceId, storageId, event.attemptId));
        if (entry && !entry.result) entry.result = event;
      }
    }
  }
  async initialize() {
    if (this.indexLoaded) return;
    if (this.initializePromise) return this.initializePromise;
    const promise = (async () => {
      const workflows = join4(this.dataRoot, "workflows");
      let namespaces;
      try {
        namespaces = await readdir(workflows, { withFileTypes: true });
      } catch (error2) {
        if (error2?.code === "ENOENT") {
          this.indexLoaded = true;
          return;
        }
        throw error2;
      }
      for (const ns of namespaces.filter((entry) => entry.isDirectory())) {
        let stores;
        try {
          stores = await readdir(join4(workflows, ns.name), { withFileTypes: true });
        } catch {
          continue;
        }
        for (const storage of stores.filter((entry) => entry.isDirectory()))
          this.indexLedger(ns.name, storage.name, await this.list(ns.name, storage.name));
      }
      this.indexLoaded = true;
    })().finally(() => {
      this.initializePromise = null;
    });
    this.initializePromise = promise;
    return promise;
  }
  async issue(namespaceId, storageId, identity) {
    for (const key2 of IDENTITY_FIELDS)
      if (!isSafeAgentStepResultId(identity[key2])) throw new Error("INVALID_RESULT_CAPABILITY_IDENTITY");
    if (identity.namespaceId !== namespaceId || !BRIEF_HASH2.test(identity.briefHash ?? ""))
      throw new Error("INVALID_RESULT_CAPABILITY_IDENTITY");
    await this.initialize();
    const key = agentStepAttemptKey(namespaceId, storageId, identity.attemptId);
    return this.locks.run(key, async () => {
      const existing = this.attemptIndex.get(key);
      if (existing) {
        const same = CAPABILITY_MATCH_FIELDS.every((field) => existing.event[field] === identity[field]);
        throw new Error(same ? "RESULT_CAPABILITY_ALREADY_ISSUED" : "RESULT_CAPABILITY_IDENTITY_CONFLICT");
      }
      const token = randomBytes3(32).toString("base64url");
      const now = this.clock();
      const record2 = {
        type: "capability-issued",
        capabilityId: randomUUID3(),
        tokenHash: sha256(token),
        ...identity,
        issuedAt: now.toISOString(),
        expiresAt: new Date(now.getTime() + this.ttlMs).toISOString(),
        submissionBudget: 1
      };
      const entry = { namespaceId, storageId, event: record2, result: null };
      await appendDurableJson(this.path(namespaceId, storageId), record2, { ensureDirectory: true });
      this.capabilityIndex.set(record2.tokenHash, entry);
      this.attemptIndex.set(key, entry);
      return { token, expiresAt: record2.expiresAt };
    });
  }
  async resolve(token) {
    if (typeof token !== "string" || token.length < 32 || token.length > 256) return null;
    await this.initialize();
    const digest4 = sha256(token);
    const located = this.capabilityIndex.get(digest4);
    return located && safeEqual(located.event.tokenHash, digest4) ? located : null;
  }
  async submit(token, business, observed = {}) {
    if (!validateAgentStepResultBusiness(business)) return { ok: false, code: "RESULT_SCHEMA_INVALID" };
    const located = await this.resolve(token);
    if (!located) return { ok: false, code: "RESULT_CAPABILITY_INVALID" };
    const { namespaceId, storageId, event: issued } = located;
    if (observed.attemptId !== issued.attemptId || observed.caseId !== issued.caseId || observed.agentName !== issued.agentName)
      return { ok: false, code: "RESULT_IDENTITY_MISMATCH" };
    const resultHash = sha256(canonicalAgentStepResultJson(business));
    const key = agentStepAttemptKey(namespaceId, storageId, issued.attemptId);
    return this.locks.run(key, async () => {
      const entry = this.attemptIndex.get(key) ?? located;
      const existing = entry.result;
      if (existing)
        return existing.resultHash === resultHash ? { ok: true, idempotent: true, result: existing } : { ok: false, code: "RESULT_SEMANTIC_COLLISION" };
      if (this.clock().getTime() > Date.parse(issued.expiresAt)) return { ok: false, code: "RESULT_CAPABILITY_EXPIRED" };
      const result = {
        type: "result-submitted",
        resultId: randomUUID3(),
        attemptId: issued.attemptId,
        workflowId: issued.workflowId,
        stepId: issued.stepId,
        namespaceId: issued.namespaceId,
        caseId: issued.caseId,
        agentName: issued.agentName,
        briefHash: issued.briefHash,
        status: business.status,
        summary: business.summary,
        artifacts: business.artifacts ?? [],
        claims: business.claims,
        findings: business.findings ?? [],
        submittedAt: this.clock().toISOString(),
        resultHash
      };
      await appendDurableJson(this.path(namespaceId, storageId), result, { ensureDirectory: true });
      entry.result = result;
      this.attemptIndex.set(key, entry);
      return { ok: true, idempotent: false, result };
    });
  }
  async getByAttempt(namespaceId, storageId, attemptId) {
    await this.initialize();
    return this.attemptIndex.get(agentStepAttemptKey(namespaceId, storageId, attemptId))?.result ?? null;
  }
};

// ../src/adapters/persistence/filesystem-agent-step-result-repository.ts
var FilesystemAgentStepResultRepository = class {
  constructor(store) {
    this.store = store;
  }
  issue(namespaceId, storageId, identity) {
    return this.store.issue(namespaceId, storageId, identity);
  }
  submit(token, business, observed) {
    return this.store.submit(token, business, observed);
  }
  getByAttempt(namespaceId, storageId, attemptId) {
    return this.store.getByAttempt(namespaceId, storageId, attemptId);
  }
  list(namespaceId, storageId) {
    return this.store.list(namespaceId, storageId);
  }
};
function createFilesystemAgentStepResultRepository(store) {
  return new FilesystemAgentStepResultRepository(store);
}

// ../src/adapters/persistence/filesystem-oracle-execution-repository.ts
var FilesystemOracleExecutionRepository = class {
  constructor(registry2) {
    this.registry = registry2;
  }
  async list() {
    return this.registry.list();
  }
  async get(id2) {
    return this.registry.get(id2);
  }
};
function createFilesystemOracleExecutionRepository(registry2) {
  return new FilesystemOracleExecutionRepository(registry2);
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
          } catch (error2) {
            deps.warn(`[shutdown] Erreur lors du kill du case ${caseId} : ${String(error2)}`);
          }
        })
      );
      if (!completed) {
        try {
          deps.endCurrentRunOnce("fail", { checkoutMayBeIntermediate: hadActiveCases, terminatedBySignal: signal });
        } catch (error2) {
          deps.warn(`[shutdown] Erreur lors de la finalisation du run : ${String(error2)}`);
        }
      }
      deps.warn("[shutdown] Sortie.");
      deps.exit(1);
    }
  };
}

// ../src/adapters/agentos/agentos-http-case-terminator.ts
function createAgentOsHttpCaseTerminator(options) {
  const fetchImpl = options.fetchImpl ?? fetch;
  const timeoutMs = options.timeoutMs ?? 5e3;
  return {
    async terminate(caseId) {
      const response2 = await fetchImpl(`${options.baseUrl}/api/cases/${encodeURIComponent(caseId)}/kill`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-External-User-Id": options.userId },
        signal: AbortSignal.timeout(timeoutMs)
      });
      if (!response2.ok) throw new Error(`AgentOS kill ${caseId} returned HTTP ${response2.status}`);
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

// ../src/ports/agent-runtime-gateway.ts
function asRuntimeExecutionId(value) {
  return value;
}

// ../src/adapters/agentos/agentos-http-client.ts
var DEFAULT_BASE_URL = "http://localhost:8124";
var DEFAULT_USER_ID = "benjamin.valdes";
var DEFAULT_TIMEOUT_MS = 15e3;
function createAgentOsHttpClient(config = {}) {
  const baseUrl = config.baseUrl ?? process.env.AGENTOS_URL ?? DEFAULT_BASE_URL;
  const userId = config.userId ?? process.env.FACTORY_USER ?? DEFAULT_USER_ID;
  const bindingSecret = config.bindingSecret ?? process.env.FACTORY_AGENTOS_BINDING_SECRET;
  const fetchImpl = config.fetchImpl ?? fetch;
  const timeoutMs = config.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  async function request(method, path, body) {
    const url = `${baseUrl}${path}`;
    const headers = {
      "Content-Type": "application/json",
      "X-External-User-Id": userId
    };
    const init = { method, headers, signal: AbortSignal.timeout(timeoutMs) };
    if (body !== void 0) {
      init.body = JSON.stringify(body);
    }
    const res = await fetchImpl(url, init);
    if (!res.ok) {
      let responseBody = "";
      try {
        responseBody = await res.text();
      } catch {
      }
      throw new Error(`AgentOS ${method} ${url} \u2192 HTTP ${res.status}
${responseBody}`);
    }
    return res;
  }
  return {
    baseUrl,
    userId,
    async createCase(namespaceId, title) {
      const res = await request("POST", "/api/cases", { namespaceId, title });
      return await res.json();
    },
    async postMessage(caseId, content, options) {
      const payload = { content };
      if (options?.answerToEventId) {
        payload.answerToEventId = options.answerToEventId;
      }
      await request("POST", `/api/cases/${encodeURIComponent(caseId)}/messages`, payload);
    },
    async bindFactoryStepResult(caseId, binding) {
      if (!bindingSecret) throw new Error("FACTORY_AGENTOS_BINDING_SECRET is required");
      const response2 = await fetchImpl(
        `${baseUrl}/internal/factory/cases/${encodeURIComponent(caseId)}/step-result-binding`,
        {
          method: "PUT",
          headers: {
            "content-type": "application/json",
            "x-factory-agentos-secret": bindingSecret
          },
          body: JSON.stringify(binding),
          signal: AbortSignal.timeout(timeoutMs)
        }
      );
      if (!response2.ok) {
        throw new Error(`AgentOS Factory binding rejected with HTTP ${response2.status}`);
      }
    },
    async getCase(caseId) {
      const res = await request("GET", `/api/cases/${encodeURIComponent(caseId)}`);
      return await res.json();
    },
    async listEvents(caseId) {
      const res = await request("GET", `/api/case-events/by-parentId/${encodeURIComponent(caseId)}`);
      return await res.json();
    },
    async killCase(caseId) {
      await request("POST", `/api/cases/${encodeURIComponent(caseId)}/kill`);
    },
    async interruptCase(caseId) {
      await request("POST", `/api/cases/${encodeURIComponent(caseId)}/interrupt`);
    },
    async listAgentConfigs(namespaceId) {
      const res = await request("GET", `/api/agent-configs/by-parentId/${encodeURIComponent(namespaceId)}`);
      return await res.json();
    },
    async listIntegrationConfigs(namespaceId) {
      const res = await request("GET", `/api/integration-configs?namespaceId=${encodeURIComponent(namespaceId)}`);
      return await res.json();
    }
  };
}

// ../src/adapters/agentos/agentos-capability-inspector.ts
import { realpathSync } from "node:fs";
var RESERVED_INTEGRATIONS = /* @__PURE__ */ new Set(["QUERY_USER", "CASE_FILE_EXCHANGE", "NAMESPACE_FILE_EXCHANGE", "FACTORY"]);
function normalizeRoot(p) {
  return p.replace(/\/+$/, "");
}
function formatAgentInventory(agents) {
  if (agents.length === 0) {
    return "Aucun agent dans ce namespace.";
  }
  const lines = agents.slice().sort((a, b) => a.name.localeCompare(b.name)).map((a) => {
    const subs = Array.isArray(a.subAgents) ? a.subAgents : [];
    const usable = a.enabled !== false && subs.length === 0;
    const marker = usable ? "  \u2713" : "  \u2717";
    const notes = [];
    if (a.enabled === false) notes.push("d\xE9sactiv\xE9");
    if (subs.length > 0) notes.push(`subAgents=[${subs.join(", ")}]`);
    const suffix = notes.length > 0 ? `  (${notes.join(", ")})` : "";
    return `${marker} ${a.name}${suffix}`;
  });
  return [
    "Agents du namespace (\u2713 = utilisable comme r\xF4le de phase) :",
    ...lines,
    "",
    "Relancer avec FACTORY_AGENT=<nom>."
  ].join("\n");
}
function createAgentOsCapabilityInspector(deps) {
  const realpath4 = deps.realpath ?? realpathSync;
  async function inspectWorker(namespaceId, workerName) {
    let agents;
    try {
      agents = await deps.listAgentConfigs(namespaceId);
    } catch (err) {
      return { ok: false, reason: `Impossible de lister les agents : ${err}`, worker: null };
    }
    const agent = agents.find((a) => a.name === workerName);
    if (!agent) {
      return {
        ok: false,
        reason: `Agent "${workerName}" introuvable dans le namespace. Une @mention non r\xE9solue bascule silencieusement sur l'agent par d\xE9faut c\xF4t\xE9 AgentOS \u2014 le run aurait fait travailler quelqu'un d'autre sans le dire.

` + formatAgentInventory(agents),
        worker: null
      };
    }
    if (agent.enabled === false) {
      return {
        ok: false,
        reason: `Agent "${workerName}" est d\xE9sactiv\xE9. Une @mention qui ne r\xE9sout pas bascule silencieusement sur l'agent par d\xE9faut.

` + formatAgentInventory(agents),
        worker: agent
      };
    }
    if (Array.isArray(agent.subAgents) && agent.subAgents.length > 0) {
      return {
        ok: false,
        reason: `Agent "${workerName}" d\xE9clare subAgents=[${agent.subAgents.join(", ")}]. Un r\xF4le de phase ne d\xE9l\xE8gue pas : DelegationTool parall\xE9lise sans condition, ce qui rendrait l'ordonnancement de l'orchestrateur inop\xE9rant.

` + formatAgentInventory(agents),
        worker: agent
      };
    }
    return { ok: true, reason: null, worker: agent };
  }
  async function preflightWorkspace2(namespaceId, agent, repoRoot) {
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(["submit_step_result"])) {
      return { ok: false, reason: "FACTORY must grant exactly submit_step_result.", rootPath: null, integration: null };
    }
    const declared = Object.keys(agent.integrations ?? {}).filter((k) => !RESERVED_INTEGRATIONS.has(k));
    if (declared.length === 0) {
      return {
        ok: false,
        reason: `Agent "${agent.name}" ne d\xE9clare aucune int\xE9gration hors cl\xE9s r\xE9serv\xE9es : il n'a aucun outil d'\xE9criture et ne pourra rien modifier.`,
        rootPath: null,
        integration: null
      };
    }
    let configs;
    try {
      configs = await deps.listIntegrationConfigs(namespaceId);
    } catch (err) {
      return { ok: false, reason: `Impossible de lister les int\xE9grations : ${err}`, rootPath: null, integration: null };
    }
    const byName = new Map(configs.map((c) => [c.name, c]));
    const unverifiable = declared.filter((name) => {
      if (byName.has(name)) return false;
      const fromAgent = (agent.integrations ?? {})[name];
      if (fromAgent === null || Array.isArray(fromAgent) || typeof fromAgent !== "object") return false;
      return true;
    });
    if (unverifiable.length > 0) {
      return {
        ok: false,
        reason: `Int\xE9gration(s) d\xE9clar\xE9e(s) mais absente(s) de l'API : ${unverifiable.join(", ")}.
Elles sont probablement charg\xE9es depuis le disque ({configPath}/integrations/), o\xF9 leur rootPath n'est pas v\xE9rifiable par REST.
L'orchestrateur refuse de partir sans pouvoir garantir que l'agent \xE9crit dans l'arbre qu'il va compiler.`,
        rootPath: null,
        integration: null
      };
    }
    const fileAccess = declared.map((name) => byName.get(name)).filter((c) => c != null && c.integrationType === "FILE_ACCESS");
    if (fileAccess.length === 0) {
      return {
        ok: false,
        reason: `Agent "${agent.name}" n'a aucune int\xE9gration FILE_ACCESS : il ne peut rien \xE9crire.`,
        rootPath: null,
        integration: null
      };
    }
    const expected = normalizeRoot(repoRoot);
    for (const cfg of fileAccess) {
      const rootPath = cfg.parameters?.rootPath;
      if (!rootPath) {
        return {
          ok: false,
          reason: `L'int\xE9gration "${cfg.name}" n'a pas de rootPath.`,
          rootPath: null,
          integration: null
        };
      }
      if (normalizeRoot(rootPath) !== expected) {
        return {
          ok: false,
          reason: `Colocalisation rompue sur l'int\xE9gration "${cfg.name}".
  rootPath de l'agent   : ${rootPath}
  racine de l'orchestrateur : ${repoRoot}
L'agent \xE9crirait dans un arbre et l'oracle en compilerait un autre : le verdict porterait sur un travail invisible.`,
          rootPath,
          integration: cfg
        };
      }
      if (cfg.parameters?.readOnly === true) {
        return {
          ok: false,
          reason: `L'int\xE9gration "${cfg.name}" est en readOnly : l'agent ne peut rien \xE9crire.`,
          rootPath,
          integration: cfg
        };
      }
    }
    const first = fileAccess[0];
    const firstRoot = first?.parameters?.rootPath;
    return {
      ok: true,
      reason: null,
      rootPath: firstRoot ? normalizeRoot(firstRoot) : null,
      integration: first ?? null
    };
  }
  async function preflightWritableWorkspace2(namespaceId, agent, repoRoot) {
    if (!Array.isArray(agent.integrations?.QUERY_USER) || agent.integrations.QUERY_USER.length !== 0) {
      return {
        ok: false,
        reason: "QUERY_USER must be explicitly disabled with an empty allowlist.",
        rootPath: null,
        integration: null
      };
    }
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(["submit_step_result"])) {
      return { ok: false, reason: "FACTORY must grant exactly submit_step_result.", rootPath: null, integration: null };
    }
    const names = Object.keys(agent.integrations ?? {}).filter((name) => !RESERVED_INTEGRATIONS.has(name));
    if (names.length !== 1) {
      return {
        ok: false,
        reason: `Editor must declare exactly one non-reserved integration; found: ${names.join(", ") || "(none)"}.`,
        rootPath: null,
        integration: null
      };
    }
    let configs;
    try {
      configs = await deps.listIntegrationConfigs(namespaceId);
    } catch (error2) {
      return { ok: false, reason: `Unable to list integrations: ${error2}`, rootPath: null, integration: null };
    }
    const target = names[0];
    const integration = configs.find((config) => config.name === target);
    let actual = null;
    const declaredRoot = integration?.parameters?.rootPath;
    if (declaredRoot) {
      try {
        actual = realpath4(declaredRoot);
      } catch {
        actual = null;
      }
    }
    if (!integration || integration.integrationType !== "FILE_ACCESS" || !actual || normalizeRoot(actual) !== normalizeRoot(repoRoot) || integration.parameters?.readOnly !== false) {
      return {
        ok: false,
        reason: "FILE_ACCESS must use canonical repoRoot with readOnly:false.",
        rootPath: declaredRoot ?? null,
        integration: null
      };
    }
    return { ok: true, reason: null, rootPath: normalizeRoot(actual), integration };
  }
  async function preflightReadOnlyWorkspace2(namespaceId, agent, repoRoot) {
    const declared = Object.keys(agent.integrations ?? {});
    if (!Array.isArray(agent.integrations?.QUERY_USER) || agent.integrations.QUERY_USER.length !== 0) {
      return {
        ok: false,
        reason: "QUERY_USER must be explicitly disabled with an empty allowlist for automated analysis.",
        rootPath: null,
        integration: null
      };
    }
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(["submit_step_result"])) {
      return { ok: false, reason: "FACTORY must grant exactly submit_step_result.", rootPath: null, integration: null };
    }
    const nonReserved = declared.filter((name) => !RESERVED_INTEGRATIONS.has(name));
    if (nonReserved.length !== 1) {
      return {
        ok: false,
        reason: `Read-only analyst must declare exactly one non-reserved integration; found: ${nonReserved.join(", ") || "(none)"}.`,
        rootPath: null,
        integration: null
      };
    }
    let configs;
    try {
      configs = await deps.listIntegrationConfigs(namespaceId);
    } catch (error2) {
      return { ok: false, reason: `Unable to list integrations: ${error2}`, rootPath: null, integration: null };
    }
    const target = nonReserved[0];
    const integration = configs.find((config) => config.name === target);
    if (!integration || integration.integrationType !== "FILE_ACCESS") {
      return {
        ok: false,
        reason: `Read-only analyst integration ${target} must resolve to FILE_ACCESS.`,
        rootPath: null,
        integration: null
      };
    }
    const rootPath = integration.parameters?.rootPath;
    let canonicalRoot = null;
    if (rootPath) {
      try {
        canonicalRoot = realpath4(rootPath);
      } catch {
        canonicalRoot = null;
      }
    }
    if (!canonicalRoot || normalizeRoot(canonicalRoot) !== normalizeRoot(repoRoot) || integration.parameters?.readOnly !== true) {
      return {
        ok: false,
        reason: "FILE_ACCESS must use the canonical target repoRoot with readOnly:true.",
        rootPath: rootPath ?? null,
        integration: null
      };
    }
    return {
      ok: true,
      reason: null,
      rootPath: normalizeRoot(canonicalRoot),
      integration
    };
  }
  return { inspectWorker, preflightWorkspace: preflightWorkspace2, preflightWritableWorkspace: preflightWritableWorkspace2, preflightReadOnlyWorkspace: preflightReadOnlyWorkspace2 };
}

// ../src/adapters/agentos/agentos-event-translator.ts
var CASE_STATUS_EVENT = "CaseStatusEvent";
var QUIESCENT_STATUSES = ["IDLE", "KILLED", "ERROR"];
function asString(value) {
  return typeof value === "string" ? value : null;
}
function sliceAfterId(events, baselineId) {
  if (!baselineId) return { events, anchored: true };
  const index = events.findIndex((e) => e.id === baselineId);
  if (index < 0) return { events, anchored: false };
  return { events: events.slice(index + 1), anchored: true };
}
function findStatusEvent(events, statuses, fromIndex = 0) {
  for (let i = fromIndex; i < events.length; i++) {
    const e = events[i];
    if (e && e.type === CASE_STATUS_EVENT && typeof e.status === "string" && statuses.includes(e.status)) {
      return { event: e, index: i };
    }
  }
  return null;
}
function findLastStatusEvent(events, statuses) {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e && e.type === CASE_STATUS_EVENT && typeof e.status === "string" && statuses.includes(e.status)) {
      return { event: e, index: i };
    }
  }
  return null;
}
function findUnansweredQuestions(allEvents) {
  const answered = new Set(
    allEvents.filter((e) => e.type === "AnswerEvent").map((e) => e.questionId).filter((id2) => typeof id2 === "string")
  );
  return allEvents.filter((e) => e.type === "QuestionEvent" && !answered.has(e.id));
}
function collectAgentsSelected(events) {
  const names = events.filter((e) => e.type === "AgentSelectedEvent").map((e) => e.agentName).filter((name) => typeof name === "string" && name.length > 0);
  return [...new Set(names)];
}
function countType(events, type) {
  return events.filter((e) => e.type === type).length;
}
function extractMessageContent(content) {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((part) => typeof part?.content === "string" ? part.content : "").join("");
}
function extractLastAgentMessage(events) {
  const last = events.filter((e) => e.type === "MessageEvent" && e.actor?.role === "AGENT").at(-1);
  if (!last) return "";
  return extractMessageContent(last.content);
}
function collectLlmModels(events) {
  const seen = /* @__PURE__ */ new Set();
  const result = [];
  for (const e of events) {
    if (e.type !== "AgentRunningEvent" && e.type !== "AgentFinishedEvent") continue;
    const llmProvider = asString(e.llmProvider);
    const llmModel = asString(e.llmModel);
    if (llmProvider == null && llmModel == null) continue;
    const agentName = asString(e.agentName);
    const key = JSON.stringify({ agentName, llmProvider, llmModel });
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({ agentName, llmProvider, llmModel });
  }
  return result;
}
function buildFailedToolCalls(toolResponseEvents) {
  const result = {};
  for (const e of toolResponseEvents) {
    if (e.success === false) {
      const name = asString(e.toolName) ?? "unknown";
      result[name] = (result[name] ?? 0) + 1;
    }
  }
  return result;
}
function toRuntimeEvent(event) {
  const base = {
    id: event.id,
    type: event.type,
    ...typeof event.timestamp === "string" ? { timestamp: event.timestamp } : {}
  };
  switch (event.type) {
    case "CaseStatusEvent":
      return { ...base, kind: "status", status: asString(event.status) ?? "" };
    case "MessageEvent":
      return {
        ...base,
        kind: "message",
        role: asString(event.actor?.role),
        content: extractMessageContent(event.content)
      };
    case "QuestionEvent":
      return { ...base, kind: "question", questionId: event.id, question: asString(event.question) ?? "" };
    case "AnswerEvent":
      return { ...base, kind: "answer", questionId: asString(event.questionId), answer: asString(event.answer) };
    case "AgentSelectedEvent":
      return { ...base, kind: "worker_selected", workerName: asString(event.agentName) };
    case "AgentFinishedEvent":
      return {
        ...base,
        kind: "worker_finished",
        workerName: asString(event.agentName),
        llmProvider: asString(event.llmProvider),
        llmModel: asString(event.llmModel)
      };
    case "AgentRunningEvent":
      return {
        ...base,
        kind: "worker_running",
        workerName: asString(event.agentName),
        llmProvider: asString(event.llmProvider),
        llmModel: asString(event.llmModel)
      };
    case "ToolResponseEvent":
      return {
        ...base,
        kind: "tool_response",
        toolName: asString(event.toolName),
        success: typeof event.success === "boolean" ? event.success : null
      };
    default:
      return { ...base, kind: "other" };
  }
}
function toRuntimeEvents(events) {
  return events.map(toRuntimeEvent);
}

// ../src/adapters/agentos/agentos-runtime-observer.ts
var DEFAULT_POLL_INTERVAL_MS = 2e3;
var DEFAULT_START_TIMEOUT_MS = 3e4;
var DEFAULT_WORK_TIMEOUT_MS = 10 * 60 * 1e3;
function defaultSleep(ms) {
  return new Promise((resolve10) => setTimeout(resolve10, ms));
}
function executionFailure(status, message, extra = {}) {
  return {
    status,
    caseStatus: null,
    message,
    events: [],
    agentsSelected: [],
    agentTurns: 0,
    toolCallCount: 0,
    failedToolCalls: {},
    killedByBudget: false,
    anchored: true,
    llmModels: [],
    ...extra
  };
}
function statusOf(event) {
  return typeof event.status === "string" ? event.status : "";
}
function buildTurnResult(turnEvents, allEvents, quiescentEvent, anchored) {
  const toolResponses = turnEvents.filter((e) => e.type === "ToolResponseEvent");
  const base = {
    caseStatus: statusOf(quiescentEvent),
    message: extractLastAgentMessage(turnEvents),
    events: toRuntimeEvents(turnEvents),
    agentsSelected: collectAgentsSelected(turnEvents),
    agentTurns: countType(turnEvents, "AgentFinishedEvent"),
    toolCallCount: toolResponses.length,
    failedToolCalls: buildFailedToolCalls(toolResponses),
    killedByBudget: false,
    anchored,
    llmModels: collectLlmModels(turnEvents)
  };
  const quiescence = statusOf(quiescentEvent);
  if (quiescence === "KILLED") return { ...base, status: "killed" };
  if (quiescence === "ERROR") return { ...base, status: "case_error" };
  const unanswered = findUnansweredQuestions(allEvents);
  const lastQuestion = unanswered.at(-1);
  if (lastQuestion) {
    const question = typeof lastQuestion.question === "string" ? lastQuestion.question : base.message;
    return { ...base, status: "pending_question", message: question };
  }
  return { ...base, status: "finished" };
}
function createAgentOsRuntimeObserver(deps) {
  const sleep = deps.sleep ?? defaultSleep;
  const now = deps.now ?? Date.now;
  const pollIntervalMs = deps.pollIntervalMs ?? DEFAULT_POLL_INTERVAL_MS;
  async function killQuietly(executionId) {
    try {
      await deps.killCase(executionId);
    } catch {
    }
  }
  return {
    async observe(executionId, options = {}) {
      const startTimeoutMs = options.startTimeoutMs ?? DEFAULT_START_TIMEOUT_MS;
      const workTimeoutMs = options.workTimeoutMs ?? DEFAULT_WORK_TIMEOUT_MS;
      const baselineId = options.baselineId ?? null;
      const startDeadline = now() + startTimeoutMs;
      let workDeadline = 0;
      let started = false;
      let runningIndex = -1;
      let anchored = true;
      for (; ; ) {
        await sleep(pollIntervalMs);
        let allEvents;
        try {
          allEvents = await deps.listEvents(executionId);
        } catch (err) {
          return executionFailure("error", String(err));
        }
        const sliced = sliceAfterId(allEvents, baselineId);
        const turnEvents = sliced.events;
        anchored = sliced.anchored;
        if (!started) {
          const running = findStatusEvent(turnEvents, ["RUNNING"]);
          if (running) {
            runningIndex = running.index;
            workDeadline = now() + workTimeoutMs;
            started = true;
          } else if (now() > startDeadline) {
            await killQuietly(executionId);
            return executionFailure("start_timeout", `Le case n'est pas pass\xE9 \xE0 RUNNING en ${startTimeoutMs}ms.`, {
              killedByBudget: true,
              anchored
            });
          } else {
            continue;
          }
        }
        const lastRunning = findLastStatusEvent(turnEvents, ["RUNNING"]);
        if (lastRunning && lastRunning.index > runningIndex) {
          runningIndex = lastRunning.index;
        }
        const quiescent = findStatusEvent(turnEvents, QUIESCENT_STATUSES, runningIndex + 1);
        if (quiescent) {
          return buildTurnResult(turnEvents, allEvents, quiescent.event, anchored);
        }
        if (started && now() > workDeadline) {
          await killQuietly(executionId);
          const toolResponses = turnEvents.filter((e) => e.type === "ToolResponseEvent");
          return {
            ...executionFailure("work_timeout", `L'agent n'a pas atteint la quiescence en ${workTimeoutMs}ms.`),
            events: toRuntimeEvents(turnEvents),
            agentsSelected: collectAgentsSelected(turnEvents),
            agentTurns: countType(turnEvents, "AgentFinishedEvent"),
            toolCallCount: toolResponses.length,
            failedToolCalls: buildFailedToolCalls(toolResponses),
            killedByBudget: true,
            anchored,
            llmModels: collectLlmModels(turnEvents)
          };
        }
      }
    }
  };
}

// ../src/adapters/agentos/agentos-runtime-adapter.ts
function createAgentOsRuntimeAdapter(config = {}) {
  const client = config.client ?? createAgentOsHttpClient(config);
  const inspector = createAgentOsCapabilityInspector({
    listAgentConfigs: (namespaceId) => client.listAgentConfigs(namespaceId),
    listIntegrationConfigs: (namespaceId) => client.listIntegrationConfigs(namespaceId),
    ...config.realpath ? { realpath: config.realpath } : {}
  });
  const observer = createAgentOsRuntimeObserver({
    listEvents: (executionId) => client.listEvents(executionId),
    killCase: (executionId) => client.killCase(executionId),
    ...config.pollIntervalMs !== void 0 ? { pollIntervalMs: config.pollIntervalMs } : {},
    ...config.sleep ? { sleep: config.sleep } : {},
    ...config.now ? { now: config.now } : {}
  });
  async function killQuietly(caseId) {
    try {
      await client.killCase(caseId);
    } catch {
    }
  }
  return {
    client,
    // ----- Port: capability inspection -------------------------------------
    inspectWorker(namespaceId, workerName) {
      return inspector.inspectWorker(namespaceId, workerName);
    },
    // ----- Port: execution lifecycle ---------------------------------------
    async startExecution(options) {
      const caseId = options.caseId ?? (await client.createCase(options.namespaceId, options.title ?? `factory ${options.workerName}`)).id;
      setActiveCaseId(caseId);
      try {
        await client.postMessage(caseId, `@${options.workerName} ${options.brief}`);
      } catch (err) {
        await killQuietly(caseId);
        clearActiveCaseId(caseId);
        throw err;
      }
      return asRuntimeExecutionId(caseId);
    },
    observeExecution(executionId, observerOptions) {
      return observer.observe(executionId, observerOptions);
    },
    bindResultChannel(executionId, binding) {
      return client.bindFactoryStepResult(executionId, binding);
    },
    answerQuestion(executionId, questionId, answer) {
      return client.postMessage(executionId, answer, { answerToEventId: questionId });
    },
    interruptExecution(executionId) {
      return client.interruptCase(executionId);
    },
    async terminateExecution(executionId) {
      try {
        await client.killCase(executionId);
      } finally {
        clearActiveCaseId(executionId);
      }
    },
    // ----- Legacy surface ---------------------------------------------------
    createCase(namespaceId, title) {
      return client.createCase(namespaceId, title);
    },
    postMessage(caseId, content) {
      return client.postMessage(caseId, content);
    },
    bindFactoryStepResult(caseId, binding) {
      return client.bindFactoryStepResult(caseId, binding);
    },
    getCase(caseId) {
      return client.getCase(caseId);
    },
    listEvents(caseId) {
      return client.listEvents(caseId);
    },
    killCase(caseId) {
      return client.killCase(caseId);
    },
    listAgents(namespaceId) {
      return client.listAgentConfigs(namespaceId);
    },
    async preflightAgent(namespaceId, agentName) {
      const result = await inspector.inspectWorker(namespaceId, agentName);
      return { ok: result.ok, reason: result.reason, agent: result.worker };
    },
    listIntegrations(namespaceId) {
      return client.listIntegrationConfigs(namespaceId);
    },
    async preflightWorkspace(namespaceId, agent, repoRoot) {
      const result = await inspector.preflightWorkspace(namespaceId, agent, repoRoot);
      return { ok: result.ok, reason: result.reason, rootPath: result.rootPath };
    },
    preflightWritableWorkspace(namespaceId, agent, repoRoot) {
      return inspector.preflightWritableWorkspace(namespaceId, agent, repoRoot).then((result) => ({
        ok: result.ok,
        reason: result.reason,
        rootPath: result.rootPath,
        integration: result.integration ?? null
      }));
    },
    preflightReadOnlyWorkspace(namespaceId, agent, repoRoot) {
      return inspector.preflightReadOnlyWorkspace(namespaceId, agent, repoRoot).then((result) => ({
        ok: result.ok,
        reason: result.reason,
        rootPath: result.rootPath,
        integration: result.integration ?? null
      }));
    },
    /**
     * Runs an agent turn and waits for execution quiescence.
     *
     * The execution id is published in the active-case registry as soon as the
     * function is entered, before any network call (A5): once an execution
     * exists and can run, SIGTERM must be able to kill it.
     */
    async runAgentTurn(caseId, agentName, brief, options = {}) {
      setActiveCaseId(caseId);
      try {
        let baselineId = null;
        try {
          const existing = await client.listEvents(caseId);
          baselineId = existing.at(-1)?.id ?? null;
          const lastStatus = existing.filter((e) => e.type === CASE_STATUS_EVENT).at(-1);
          if (lastStatus && typeof lastStatus.status === "string" && !QUIESCENT_STATUSES.includes(lastStatus.status)) {
            return executionFailure(
              "case_busy",
              `Le case est en statut ${lastStatus.status}. run() est auto-gard\xE9 c\xF4t\xE9 serveur : poster maintenant produirait un lancement silencieusement abandonn\xE9.`
            );
          }
        } catch (err) {
          return executionFailure("error", String(err));
        }
        try {
          await client.postMessage(caseId, `@${agentName} ${brief}`);
        } catch (err) {
          await killQuietly(caseId);
          return executionFailure("error", String(err));
        }
        const observeOptions = { baselineId };
        if (options.startTimeoutMs !== void 0) observeOptions.startTimeoutMs = options.startTimeoutMs;
        if (options.workTimeoutMs !== void 0) observeOptions.workTimeoutMs = options.workTimeoutMs;
        return await observer.observe(caseId, observeOptions);
      } finally {
        clearActiveCaseId(caseId);
      }
    }
  };
}

// ../src/application/agentos-operations.ts
var agentos_operations_exports = {};
__export(agentos_operations_exports, {
  bindFactoryStepResult: () => bindFactoryStepResult,
  createCase: () => createCase,
  getAgentOsRuntimeAdapter: () => getAgentOsRuntimeAdapter,
  getCase: () => getCase,
  killCase: () => killCase,
  listAgents: () => listAgents,
  listEvents: () => listEvents,
  listIntegrations: () => listIntegrations,
  postMessage: () => postMessage,
  preflightAgent: () => preflightAgent,
  preflightReadOnlyWorkspace: () => preflightReadOnlyWorkspace,
  preflightWorkspace: () => preflightWorkspace,
  preflightWritableWorkspace: () => preflightWritableWorkspace,
  runAgentTurn: () => runAgentTurn
});
var defaultAgentOsAdapter = null;
function getAgentOsRuntimeAdapter() {
  defaultAgentOsAdapter ??= createAgentOsRuntimeAdapter();
  return defaultAgentOsAdapter;
}
function createCase(namespaceId, title) {
  return getAgentOsRuntimeAdapter().createCase(namespaceId, title);
}
function postMessage(caseId, content) {
  return getAgentOsRuntimeAdapter().postMessage(caseId, content);
}
function bindFactoryStepResult(caseId, binding) {
  return getAgentOsRuntimeAdapter().bindFactoryStepResult(caseId, binding);
}
function getCase(caseId) {
  return getAgentOsRuntimeAdapter().getCase(caseId);
}
function listEvents(caseId) {
  return getAgentOsRuntimeAdapter().listEvents(caseId);
}
function killCase(caseId) {
  return getAgentOsRuntimeAdapter().killCase(caseId);
}
function listAgents(namespaceId) {
  return getAgentOsRuntimeAdapter().listAgents(namespaceId);
}
function preflightAgent(namespaceId, agentName) {
  return getAgentOsRuntimeAdapter().preflightAgent(namespaceId, agentName);
}
function listIntegrations(namespaceId) {
  return getAgentOsRuntimeAdapter().listIntegrations(namespaceId);
}
function preflightWorkspace(namespaceId, agent, repoRoot) {
  return getAgentOsRuntimeAdapter().preflightWorkspace(namespaceId, agent, repoRoot);
}
function preflightWritableWorkspace(namespaceId, agent, repoRoot) {
  return getAgentOsRuntimeAdapter().preflightWritableWorkspace(namespaceId, agent, repoRoot);
}
function preflightReadOnlyWorkspace(namespaceId, agent, repoRoot) {
  return getAgentOsRuntimeAdapter().preflightReadOnlyWorkspace(namespaceId, agent, repoRoot);
}
function runAgentTurn(caseId, agentName, brief, options) {
  return getAgentOsRuntimeAdapter().runAgentTurn(caseId, agentName, brief, options);
}

// ../src/application/agent-attempt/factory-agent-step-executor.ts
import { createHash as createHash7, randomUUID as randomUUID4 } from "node:crypto";
import { mkdir as mkdir2, open as open2, readFile as readFile2, rename as rename2, rm as rm2, stat } from "node:fs/promises";
import { isAbsolute as isAbsolute2, join as join5, relative, resolve as resolve2 } from "node:path";
var MAX_INLINE_ARTIFACT_BYTES = 256 * 1024;
var STRUCTURED_RESULT_FINALIZATION_BRIEF = "Do no new analysis or work. Perform no reads, writes, delegation, queryUser, or oracle calls. Using only the work already completed in this case, call FACTORY__submit_step_result exactly once. Your normal assistant message is non-authoritative.";
var diagnostic = (value, fallback) => String(value ?? fallback).replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").slice(0, 1e3);
var safeSegment = (value) => typeof value === "string" && /^[A-Za-z0-9._-]+$/.test(value) && value !== "." && value !== "..";
var inside = (root, target) => {
  const rel = relative(root, target);
  return rel === "" || !rel.startsWith("..") && !isAbsolute2(rel);
};
function defaultAgentStepOperations() {
  return {
    createCase,
    bindFactoryStepResult,
    preflightAgent,
    preflightReadOnlyWorkspace,
    preflightWritableWorkspace,
    runAgentTurn
  };
}
var artifactEvidenceIdempotencyKey = (attemptId, artifactPath) => `${attemptId}:artifact:${createHash7("sha256").update(String(artifactPath).split("\\").join("/")).digest("hex")}`;
function extractSingleJsonObject(message) {
  const text2 = String(message ?? "").trim();
  const fence = [...text2.matchAll(/```json\s*([\s\S]*?)\s*```/gi)];
  if (fence.length > 1) return null;
  if (fence.length === 1) {
    const match2 = fence[0];
    if (!match2) return null;
    const outside = (text2.slice(0, match2.index) + text2.slice(match2.index + match2[0].length)).trim();
    if (/[{}]/.test(outside)) return null;
    return match2[1]?.trim() ?? null;
  }
  const candidates = [];
  for (let start = 0; start < text2.length; start++) {
    if (text2[start] !== "{") continue;
    let depth = 0;
    let inString = false;
    let escape = false;
    for (let end = start; end < text2.length; end++) {
      const char = text2[end];
      if (inString) {
        if (escape) escape = false;
        else if (char === "\\") escape = true;
        else if (char === '"') inString = false;
        continue;
      }
      if (char === '"') {
        inString = true;
        continue;
      }
      if (char === "{") depth++;
      if (char === "}" && --depth === 0) {
        const candidate = text2.slice(start, end + 1);
        try {
          const value = JSON.parse(candidate);
          if (value && typeof value === "object" && !Array.isArray(value)) candidates.push(candidate);
        } catch {
        }
        start = end;
        break;
      }
    }
  }
  return candidates.length === 1 ? candidates[0] ?? null : null;
}
function parseAgentStepResult(message) {
  const text2 = extractSingleJsonObject(message);
  if (!text2) return { ok: false, code: "RESULT_NOT_JSON" };
  let value;
  try {
    value = JSON.parse(text2);
  } catch {
    return { ok: false, code: "RESULT_NOT_JSON" };
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return { ok: false, code: "RESULT_SCHEMA_INVALID" };
  const record2 = value;
  const claims = record2.claims;
  if (!["PASS", "FAIL"].includes(record2.status) || typeof record2.summary !== "string" || !claims || !Array.isArray(claims.modifiedFiles))
    return { ok: false, code: "RESULT_SCHEMA_INVALID" };
  if (record2.artifacts !== void 0 && !Array.isArray(record2.artifacts))
    return { ok: false, code: "RESULT_SCHEMA_INVALID" };
  return { ok: true, value: record2 };
}
async function verifyArtifacts(result, repoRoot, expectedKind) {
  const artifacts = result.artifacts ?? [];
  if (expectedKind && !artifacts.some((a) => a.kind === expectedKind))
    return { ok: false, code: "EXPECTED_ARTIFACT_MISSING" };
  const verified = [];
  for (const artifact2 of artifacts) {
    if (isAbsolute2(artifact2.path)) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    const absolute = resolve2(repoRoot, artifact2.path);
    if (relative(repoRoot, absolute).startsWith("..")) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    try {
      if (!(await stat(absolute)).isFile()) return { ok: false, code: "ARTIFACT_NOT_FILE" };
      const content = await readFile2(absolute);
      verified.push({ ...artifact2, hash: sha256(content) });
    } catch {
      return { ok: false, code: "ARTIFACT_MISSING" };
    }
  }
  return { ok: true, artifacts: verified };
}
async function materializeInlineArtifact({
  result,
  repoRoot,
  workflowId,
  stepId,
  attemptId,
  expectedKind,
  maxBytes = MAX_INLINE_ARTIFACT_BYTES
}) {
  const artifacts = result.artifacts ?? [];
  if (!expectedKind)
    return artifacts.length ? { ok: false, code: "INLINE_ARTIFACT_FORBIDDEN" } : { ok: true, artifacts: [] };
  if (artifacts.length !== 1)
    return { ok: false, code: artifacts.length ? "ARTIFACT_AMBIGUOUS" : "EXPECTED_ARTIFACT_MISSING" };
  const artifact2 = artifacts[0];
  if (!artifact2 || typeof artifact2 !== "object" || Array.isArray(artifact2))
    return { ok: false, code: "INLINE_ARTIFACT_SCHEMA_INVALID" };
  if ("path" in artifact2) return { ok: false, code: "ARTIFACT_PATH_FORBIDDEN" };
  if (Object.keys(artifact2).some((key) => !["kind", "encoding", "content"].includes(key)))
    return { ok: false, code: "INLINE_ARTIFACT_SCHEMA_INVALID" };
  if (artifact2.kind !== expectedKind) return { ok: false, code: "ARTIFACT_KIND_MISMATCH" };
  if (artifact2.encoding !== "markdown") return { ok: false, code: "ARTIFACT_ENCODING_INVALID" };
  if (typeof artifact2.content !== "string" || artifact2.content.length === 0)
    return { ok: false, code: "ARTIFACT_CONTENT_EMPTY" };
  const content = Buffer.from(artifact2.content, "utf8");
  if (content.length === 0) return { ok: false, code: "ARTIFACT_CONTENT_EMPTY" };
  if (content.byteLength > maxBytes) return { ok: false, code: "ARTIFACT_CONTENT_TOO_LARGE" };
  if (!safeSegment(workflowId) || !safeSegment(stepId) || !safeSegment(attemptId))
    return { ok: false, code: "ARTIFACT_PATH_INVALID" };
  const root = resolve2(repoRoot);
  const zone = resolve2(root, "forge", "factory-artifacts");
  const workflowDirectory = resolve2(zone, workflowId);
  const directory = resolve2(workflowDirectory, stepId);
  const finalPath = resolve2(directory, `${attemptId}.md`);
  if (!inside(root, zone) || !inside(zone, workflowDirectory) || !inside(workflowDirectory, directory) || !inside(directory, finalPath))
    return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
  await mkdir2(directory, { recursive: true });
  const artifactPath = relative(root, finalPath).split("\\").join("/");
  const verified = (persisted) => persisted.equals(content) ? { ok: true, artifacts: [{ kind: expectedKind, path: artifactPath, hash: sha256(persisted) }] } : { ok: false, code: "ARTIFACT_SEMANTIC_COLLISION" };
  try {
    return verified(await readFile2(finalPath));
  } catch (error2) {
    if (error2?.code !== "ENOENT")
      return { ok: false, code: "ARTIFACT_MATERIALIZATION_FAILED" };
  }
  const temporary = join5(directory, `.${stepId}.${randomUUID4()}.tmp`);
  let handle = null;
  try {
    handle = await open2(temporary, "wx", 384);
    await handle.writeFile(content);
    await handle.sync();
    await handle.close();
    handle = null;
    try {
      await rename2(temporary, finalPath);
    } catch (error2) {
      if (error2?.code !== "EEXIST") throw error2;
      return verified(await readFile2(finalPath));
    }
    const persisted = await readFile2(finalPath);
    if (!persisted.equals(content)) return { ok: false, code: "ARTIFACT_WRITE_MISMATCH" };
    return verified(persisted);
  } catch {
    return { ok: false, code: "ARTIFACT_MATERIALIZATION_FAILED" };
  } finally {
    if (handle) await handle.close().catch(() => {
    });
    await rm2(temporary, { force: true }).catch(() => {
    });
  }
}
async function executeAgentStepAttempt(input) {
  const {
    namespaceId,
    workflowId,
    definition,
    projectionStore,
    evidenceStore,
    attemptStore,
    resultStore,
    storageId,
    repoRoot,
    runtimeId = "factory-runner",
    brief,
    expectedArtifactKind,
    diffFiles = async () => [],
    agentOps = {}
  } = input;
  if (!resultStore) return { ok: false, code: "STRUCTURED_RESULT_STORE_UNAVAILABLE" };
  const ops = { ...defaultAgentStepOperations(), ...agentOps };
  const snapshot = await projectionStore.read(namespaceId, workflowId);
  const declared = definition.steps.find((s) => s.id === input.stepId);
  const current = snapshot?.instance?.steps.find((s) => s.id === input.stepId);
  if (!snapshot || !declared || declared.responsibility.kind !== "agent" || current?.status !== "ready")
    return { ok: false, code: "STEP_NOT_READY" };
  if (input.expectedRevision !== void 0 && snapshot.revision !== input.expectedRevision)
    return { ok: false, code: "REVISION_CONFLICT" };
  const agentName = declared.responsibility.name;
  const preflight = await ops.preflightAgent(namespaceId, agentName);
  if (!preflight.ok || preflight.agent?.name !== agentName || (preflight.agent?.subAgents?.length ?? 0) > 0)
    return {
      ok: false,
      code: "AGENT_PREFLIGHT_FAILED",
      details: diagnostic(
        preflight.reason,
        preflight.agent?.name !== agentName ? `Agent identity mismatch: expected ${agentName}, received ${preflight.agent?.name ?? "(missing)"}.` : `Agent ${agentName} has forbidden subAgents.`
      )
    };
  const writable = input.stepId === "frontend-implementation";
  const worker = preflight.agent;
  const workspace = await (writable ? ops.preflightWritableWorkspace : ops.preflightReadOnlyWorkspace)(
    namespaceId,
    worker,
    repoRoot
  );
  if (!workspace.ok)
    return {
      ok: false,
      code: "AGENT_PREFLIGHT_FAILED",
      details: diagnostic(workspace.reason, "Workspace capability preflight failed.")
    };
  const attemptNumber = (await attemptStore.list(namespaceId, storageId)).filter((a) => a.stepId === input.stepId).reduce((n, a) => Math.max(n, a.attemptNumber), 0) + 1;
  const attemptId = randomUUID4();
  const startedAt = (/* @__PURE__ */ new Date()).toISOString();
  const briefHash = sha256(brief);
  let attempt = {
    attemptId,
    workflowId,
    workflowRevisionAtStart: snapshot.revision,
    stepId: input.stepId,
    attemptNumber,
    namespaceId,
    runtimeId,
    caseId: null,
    agentName,
    briefHash,
    status: "starting",
    startedAt,
    finishedAt: null,
    evidenceId: null,
    failureCode: null
  };
  await attemptStore.append(namespaceId, storageId, attempt);
  let caseRecord;
  try {
    caseRecord = await ops.createCase(namespaceId, `Factory ${workflowId} ${input.stepId} attempt ${attemptNumber}`);
  } catch {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: "failed",
      finishedAt: (/* @__PURE__ */ new Date()).toISOString(),
      failureCode: "CASE_CREATION_FAILED"
    });
    return { ok: false, code: "CASE_CREATION_FAILED" };
  }
  attempt = { ...attempt, caseId: caseRecord.id, status: "running" };
  await attemptStore.append(namespaceId, storageId, attempt);
  const capability = await resultStore.issue(namespaceId, storageId, {
    attemptId,
    workflowId,
    stepId: input.stepId,
    namespaceId,
    caseId: caseRecord.id,
    agentName,
    briefHash
  });
  try {
    await ops.bindFactoryStepResult(caseRecord.id, {
      namespaceId,
      agentName,
      attemptId,
      runtimeId,
      capabilityToken: capability.token,
      expiresAt: capability.expiresAt
    });
  } catch {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: "indeterminate",
      finishedAt: (/* @__PURE__ */ new Date()).toISOString(),
      failureCode: "RESULT_BINDING_FAILED"
    });
    return { ok: false, code: "RESULT_BINDING_FAILED" };
  }
  const execution2 = { kind: "agentos", runtimeId, agentId: agentName, caseId: caseRecord.id, threadId: null };
  const runningRequest = validateWorkflowTransitionRequest(
    {
      workflowId,
      stepId: input.stepId,
      expectedRevision: snapshot.revision,
      requestedStatus: "running",
      evidenceIds: [],
      idempotencyKey: `${attemptId}:running`
    },
    workflowId
  );
  if (!runningRequest.ok) throw new Error("INVALID_RUNNING_TRANSITION");
  const running = await projectionStore.transition(namespaceId, runningRequest.value, definition, [], execution2);
  if (!running.ok) {
    await attemptStore.append(namespaceId, storageId, {
      ...attempt,
      status: "failed",
      finishedAt: (/* @__PURE__ */ new Date()).toISOString(),
      failureCode: running.error.code
    });
    return { ok: false, code: running.error.code };
  }
  let turn;
  try {
    turn = await ops.runAgentTurn(caseRecord.id, agentName, brief);
  } catch {
    turn = { status: "error", anchored: false, agentsSelected: [] };
  }
  let failureCode = null;
  let result = null;
  let artifacts = [];
  let finalizationAttempted = false;
  if (turn.status !== "finished") failureCode = `TURN_${turn.status.toUpperCase()}`;
  else if (!turn.anchored) failureCode = "HISTORY_NOT_ANCHORED";
  else if (turn.agentsSelected.length !== 1 || turn.agentsSelected[0] !== agentName)
    failureCode = "WORKER_IDENTITY_MISMATCH";
  else {
    result = await resultStore.getByAttempt(namespaceId, storageId, attemptId);
    if (!result) {
      finalizationAttempted = true;
      let finalizationTurn;
      try {
        finalizationTurn = await ops.runAgentTurn(caseRecord.id, agentName, STRUCTURED_RESULT_FINALIZATION_BRIEF);
      } catch {
        finalizationTurn = { status: "error", anchored: false, agentsSelected: [] };
      }
      if (finalizationTurn.status !== "finished")
        failureCode = `STRUCTURED_RESULT_FINALIZATION_${String(finalizationTurn.status).toUpperCase()}`;
      else if (!finalizationTurn.anchored) failureCode = "STRUCTURED_RESULT_FINALIZATION_HISTORY_NOT_ANCHORED";
      else if (finalizationTurn.agentsSelected.length !== 1 || finalizationTurn.agentsSelected[0] !== agentName)
        failureCode = "STRUCTURED_RESULT_FINALIZATION_WORKER_IDENTITY_MISMATCH";
      else {
        result = await resultStore.getByAttempt(namespaceId, storageId, attemptId);
        if (!result) failureCode = "STRUCTURED_RESULT_MISSING_AFTER_FINALIZATION";
      }
    }
    if (!failureCode) {
      const checked = writable ? await verifyArtifacts(result, repoRoot, void 0) : await materializeInlineArtifact({
        result,
        repoRoot,
        workflowId,
        stepId: input.stepId,
        attemptId,
        expectedKind: expectedArtifactKind
      });
      if (!checked.ok) failureCode = checked.code;
      else artifacts = checked.artifacts;
    }
  }
  if (!failureCode && writable) {
    if ((result.artifacts?.length ?? 0) > 0) failureCode = "INLINE_ARTIFACT_FORBIDDEN";
    if (!failureCode) {
      const actual = [...new Set(await diffFiles(repoRoot))].sort();
      const claimed = [...new Set(result.claims.modifiedFiles)].sort();
      if (JSON.stringify(actual) !== JSON.stringify(claimed)) failureCode = "CLAIMS_DIFF_MISMATCH";
      else if (actual.some((file) => !input.allowedPaths?.some((prefix) => file === prefix || file.startsWith(`${prefix}/`))))
        failureCode = "MODIFIED_FILE_OUT_OF_SCOPE";
    }
  }
  if (!failureCode && result.status !== "PASS") failureCode = "AGENT_REPORTED_FAIL";
  const evidenceSource = { ...execution2 };
  const artifactEvidence = [];
  if (!failureCode && input.stepId === "technical-review") {
    if (!Array.isArray(result.findings) || result.findings.length > 0)
      failureCode = "REVIEW_SCHEMA_INVALID";
  }
  for (const artifact2 of artifacts) {
    const validated = validateWorkflowEvidenceInput(
      {
        workflowId,
        stepId: input.stepId,
        kind: "artifact",
        artifactRef: artifact2.path,
        artifactHash: artifact2.hash,
        idempotencyKey: artifactEvidenceIdempotencyKey(attemptId, artifact2.path)
      },
      workflowId
    );
    if (!validated.ok) {
      failureCode = "INVALID_ARTIFACT_EVIDENCE";
      break;
    }
    artifactEvidence.push(
      (await evidenceStore.record(namespaceId, storageId, validated.value, evidenceSource)).evidence
    );
  }
  const outcome = failureCode ? result?.status === "FAIL" ? "fail" : "indeterminate" : "pass";
  const resultFacts = {
    resultCode: failureCode ?? "STRUCTURED_PASS",
    attempt: attemptNumber,
    itemCount: artifacts.length,
    briefHash,
    finalizationTurns: finalizationAttempted ? 1 : 0,
    ...result ? { claimsHash: sha256(canonicalAgentStepResultJson(result.claims)) } : {}
  };
  const validatedResult = validateWorkflowEvidenceInput(
    {
      workflowId,
      stepId: input.stepId,
      kind: "agent-result",
      outcome,
      facts: resultFacts,
      idempotencyKey: `${attemptId}:result`
    },
    workflowId
  );
  if (!validatedResult.ok) throw new Error("INVALID_AGENT_RESULT_EVIDENCE");
  const resultEvidence = (await evidenceStore.record(namespaceId, storageId, validatedResult.value, evidenceSource)).evidence;
  const status = failureCode ? outcome === "fail" ? "failed" : "indeterminate" : "succeeded";
  const finished = {
    ...attempt,
    status,
    finishedAt: (/* @__PURE__ */ new Date()).toISOString(),
    evidenceId: resultEvidence.evidenceId,
    failureCode
  };
  if (!isAgentStepAttemptTerminal(status)) throw new Error("NON_TERMINAL_ATTEMPT");
  await attemptStore.append(namespaceId, storageId, finished);
  if (failureCode) {
    const latest2 = await projectionStore.read(namespaceId, workflowId);
    const blocked2 = validateWorkflowTransitionRequest(
      {
        workflowId,
        stepId: input.stepId,
        expectedRevision: latest2.revision,
        requestedStatus: "blocked",
        evidenceIds: [resultEvidence.evidenceId],
        idempotencyKey: `${attemptId}:blocked`
      },
      workflowId
    );
    if (!blocked2.ok)
      return {
        ok: false,
        code: failureCode,
        reconciliationCode: "INVALID_NEGATIVE_TRANSITION",
        attempt: finished,
        evidence: resultEvidence
      };
    const transition2 = await projectionStore.transition(
      namespaceId,
      blocked2.value,
      definition,
      [resultEvidence],
      execution2
    );
    return transition2.ok ? { ok: false, code: failureCode, attempt: finished, evidence: resultEvidence, snapshot: transition2.snapshot } : {
      ok: false,
      code: failureCode,
      reconciliationCode: transition2.error.code,
      attempt: finished,
      evidence: resultEvidence
    };
  }
  const latest = await projectionStore.read(namespaceId, workflowId);
  const completed = validateWorkflowTransitionRequest(
    {
      workflowId,
      stepId: input.stepId,
      expectedRevision: latest.revision,
      requestedStatus: "completed",
      evidenceIds: [resultEvidence.evidenceId, ...artifactEvidence.map((e) => e.evidenceId)],
      idempotencyKey: `${attemptId}:completed`
    },
    workflowId
  );
  if (!completed.ok) throw new Error("INVALID_COMPLETION_TRANSITION");
  const transition = await projectionStore.transition(
    namespaceId,
    completed.value,
    definition,
    [resultEvidence, ...artifactEvidence],
    execution2
  );
  return transition.ok ? {
    ok: true,
    attempt: finished,
    result,
    artifacts,
    evidence: [resultEvidence, ...artifactEvidence],
    snapshot: transition.snapshot
  } : { ok: false, code: transition.error.code, attempt: finished };
}

// ../src/domain/oracle/oracle.ts
function countTaskOutcomes(output) {
  const plain = output.replace(/\u001b\[[0-9;]*m/g, "");
  const lines = plain.split("\n");
  let upToDate = 0;
  let fromCache = 0;
  let skipped = 0;
  let executed = 0;
  let nxTaskLines = 0;
  let cacheSummaryFound = false;
  let cacheSummaryFromCache = null;
  let cacheSummaryTotal = null;
  let successSummaryFound = false;
  let successSummaryTotal = null;
  for (const line of lines) {
    if (line.startsWith("> Task ")) {
      if (line.includes("UP-TO-DATE")) upToDate++;
      else if (line.includes("FROM-CACHE")) fromCache++;
      else if (line.includes("SKIPPED") || line.includes("NO-SOURCE")) skipped++;
      else executed++;
      continue;
    }
    if (line.startsWith("> nx run ")) {
      nxTaskLines++;
      if (line.includes("existing outputs match the cache")) fromCache++;
      else executed++;
      continue;
    }
    const cacheSummaryMatch = line.match(
      /Nx\s+read\s+the\s+output\s+from\s+the\s+cache\s+instead\s+of\s+running\s+the\s+command\s+for\s+(\d+)\s+out\s+of\s+(\d+)\s+tasks/
    );
    if (cacheSummaryMatch) {
      cacheSummaryFound = true;
      cacheSummaryFromCache = parseInt(cacheSummaryMatch[1] ?? "0", 10);
      cacheSummaryTotal = parseInt(cacheSummaryMatch[2] ?? "0", 10);
      continue;
    }
    const successMatch = line.match(/NX\s+Successfully\s+ran\s+target\s+\S+\s+for\s+(\d+)\s+projects?/);
    if (successMatch) {
      successSummaryFound = true;
      successSummaryTotal = parseInt(successMatch[1] ?? "0", 10);
      continue;
    }
  }
  const summaryFound = cacheSummaryFound || successSummaryFound;
  const summaryFromCache = cacheSummaryFound ? cacheSummaryFromCache : null;
  const summaryTotal = cacheSummaryFound ? cacheSummaryTotal : successSummaryFound ? successSummaryTotal : null;
  let summaryAbsenceReason = null;
  if (!summaryFound) summaryAbsenceReason = nxTaskLines === 0 ? "no-nx-tasks" : "fresh-run";
  let countMismatch = false;
  const lineTotal = upToDate + fromCache + skipped + executed;
  if (cacheSummaryFound) countMismatch = fromCache !== cacheSummaryFromCache || lineTotal !== cacheSummaryTotal;
  if (successSummaryFound) countMismatch = countMismatch || lineTotal !== successSummaryTotal;
  return {
    upToDate,
    fromCache,
    skipped,
    executed,
    summaryFound,
    summaryAbsenceReason,
    summaryFromCache,
    summaryTotal,
    countMismatch
  };
}
function diffSnapshots(before, after) {
  const modified = [];
  for (const [path, fingerprint] of after.modified) {
    if (before.modified.get(path) !== fingerprint) modified.push(path);
  }
  const untracked = [];
  for (const [path, fingerprint] of after.untracked) {
    if (before.untracked.get(path) !== fingerprint) untracked.push(path);
  }
  return { modified, untracked };
}

// ../src/domain/oracle/oracle-definition.ts
import { createHash as createHash8 } from "node:crypto";
var SAFE2 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var VERSION = /^\d+\.\d+\.\d+$/;
var FIELDS2 = /* @__PURE__ */ new Set([
  "schemaVersion",
  "id",
  "version",
  "domain",
  "argv",
  "cwd",
  "timeoutMs",
  "success",
  "applicable"
]);
var SUCCESS_FIELDS = /* @__PURE__ */ new Set(["rule", "requireWork"]);
var APPLICABLE_FIELDS = /* @__PURE__ */ new Set(["workflowTypes", "stepIds"]);
var SHELL_EXECUTABLES = /* @__PURE__ */ new Set([
  "sh",
  "bash",
  "zsh",
  "dash",
  "ksh",
  "cmd",
  "cmd.exe",
  "powershell",
  "powershell.exe",
  "pwsh",
  "pwsh.exe"
]);
var SHELL_FLAGS = /* @__PURE__ */ new Set(["-c", "--command", "/c", "-command", "-encodedcommand"]);
function invalid2() {
  throw new Error("INVALID_ORACLE_DEFINITION");
}
function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function canonicalOracleDefinition(value) {
  if (Array.isArray(value)) return value.map((entry) => canonicalOracleDefinition(entry));
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonicalOracleDefinition(value[key])])
    );
  }
  return value;
}
function validateOracleDefinition(value) {
  if (!isRecord(value)) invalid2();
  const candidate = value;
  if (Object.keys(candidate).some((key) => !FIELDS2.has(key))) invalid2();
  if (candidate.schemaVersion !== "1" || !SAFE2.test(String(candidate.id ?? "")) || !VERSION.test(String(candidate.version ?? "")) || !SAFE2.test(String(candidate.domain ?? "")))
    invalid2();
  const argv = candidate.argv;
  if (!Array.isArray(argv) || argv.length === 0 || argv.length > 32 || argv.some((entry) => typeof entry !== "string" || !entry || entry.length > 512 || /[\r\n\0]/.test(entry)))
    invalid2();
  const args = argv;
  const executableRaw = args[0];
  if (executableRaw === void 0) invalid2();
  const executable = executableRaw.replace(/\\/g, "/").split("/").at(-1)?.toLowerCase();
  if (executable === void 0 || SHELL_EXECUTABLES.has(executable) || args.some((arg, index) => index > 0 && SHELL_FLAGS.has(arg.toLowerCase())))
    invalid2();
  if (candidate.cwd !== "repo-root" || !Number.isSafeInteger(candidate.timeoutMs) || candidate.timeoutMs < 1 || candidate.timeoutMs > 36e5)
    invalid2();
  const success = candidate.success;
  if (!isRecord(success) || Object.keys(success).some((key) => !SUCCESS_FIELDS.has(key)) || success.rule !== "exit-code" || typeof success.requireWork !== "boolean")
    invalid2();
  const applicable = candidate.applicable;
  if (!isRecord(applicable) || Object.keys(applicable).some((key) => !APPLICABLE_FIELDS.has(key)) || !Array.isArray(applicable.workflowTypes) || applicable.workflowTypes.length === 0 || applicable.workflowTypes.some((entry) => !SAFE2.test(String(entry))) || !Array.isArray(applicable.stepIds) || applicable.stepIds.length === 0 || applicable.stepIds.some((entry) => !SAFE2.test(String(entry))))
    invalid2();
  return Object.freeze({
    schemaVersion: "1",
    id: candidate.id,
    version: candidate.version,
    domain: candidate.domain,
    argv: Object.freeze([...args]),
    cwd: "repo-root",
    timeoutMs: candidate.timeoutMs,
    success: Object.freeze({ rule: "exit-code", requireWork: success.requireWork }),
    applicable: Object.freeze({
      workflowTypes: Object.freeze([...applicable.workflowTypes]),
      stepIds: Object.freeze([...applicable.stepIds])
    })
  });
}
function hashOracleDefinition(definition) {
  return `sha256:${createHash8("sha256").update(JSON.stringify(canonicalOracleDefinition(definition))).digest("hex")}`;
}
function definitionIdentityFromFileName(fileName) {
  const base = fileName.split(/[\\/]/).at(-1) ?? fileName;
  return base.endsWith(".json") ? base.slice(0, -".json".length) : base;
}
var OracleDefinitionRegistryCore = class {
  constructor(source) {
    this.source = source;
    this.items = /* @__PURE__ */ new Map();
  }
  items;
  async initialize() {
    const files = [...await this.source.listFiles()].filter((name) => name.endsWith(".json")).sort();
    const next = /* @__PURE__ */ new Map();
    for (const file of files) {
      const definition = validateOracleDefinition(JSON.parse(await this.source.readFile(file)));
      if (definitionIdentityFromFileName(file) !== `${definition.id}@${definition.version}`)
        throw new Error("ORACLE_PATH_IDENTITY_MISMATCH");
      if (next.has(definition.id)) throw new Error("DUPLICATE_ORACLE_ID");
      next.set(definition.id, definition);
    }
    this.items = next;
    return this;
  }
  /** Every loaded definition, in registry insertion order. */
  list() {
    return [...this.items.values()];
  }
  get(id2) {
    return this.items.get(id2) ?? null;
  }
};

// ../src/application/oracle/oracle-definition-registry.ts
import { readFile as readFile3, readdir as readdir2 } from "node:fs/promises";
import { join as join6 } from "node:path";
function createFilesystemOracleDefinitionSource(root) {
  return {
    listFiles: () => readdir2(root),
    readFile: (fileName) => readFile3(join6(root, fileName), "utf8")
  };
}
var OracleDefinitionRegistry = class extends OracleDefinitionRegistryCore {
  constructor(root) {
    super(createFilesystemOracleDefinitionSource(root));
  }
};

// ../src/application/oracle/oracle-command.ts
import { existsSync, readFileSync } from "node:fs";
import { dirname as dirname3, join as join7 } from "node:path";
function resolveBuildHosts(ownerProjects, repoRoot) {
  const mapRaw = process.env.FACTORY_FRONT_BUILD_HOST_MAP;
  if (!mapRaw) {
    return {
      noHost: true,
      reason: "FACTORY_FRONT_BUILD_HOST_MAP is not set. Cannot resolve buildable host applications for owner projects: " + ownerProjects.join(", ") + `. Set this env var to a JSON map of owner project \u2192 host app(s). Example: '{"*":["aphrodite","admin","agentic-studio","copilot-chat"]}'. See factory/lib/domains.mjs for documentation.`,
      ownerProjects: [...ownerProjects]
    };
  }
  let hostMap;
  try {
    hostMap = JSON.parse(mapRaw);
  } catch (err) {
    return {
      noHost: true,
      reason: "FACTORY_FRONT_BUILD_HOST_MAP is not valid JSON: " + String(err) + ". Raw value: " + mapRaw.slice(0, 200),
      ownerProjects: [...ownerProjects]
    };
  }
  if (typeof hostMap !== "object" || hostMap === null || Array.isArray(hostMap)) {
    return {
      noHost: true,
      reason: "FACTORY_FRONT_BUILD_HOST_MAP must be a JSON object, got: " + typeof hostMap,
      ownerProjects: [...ownerProjects]
    };
  }
  const record2 = hostMap;
  const fallbackHosts = Array.isArray(record2["*"]) ? record2["*"] : [];
  const seen = /* @__PURE__ */ new Set();
  const hosts = [];
  for (const owner of ownerProjects) {
    const mapped = Array.isArray(record2[owner]) ? record2[owner] : fallbackHosts;
    for (const host of mapped) {
      if (typeof host === "string" && !seen.has(host)) {
        seen.add(host);
        hosts.push(host);
      }
    }
  }
  if (hosts.length === 0) {
    return {
      noHost: true,
      reason: "No buildable host found for owner projects: " + ownerProjects.join(", ") + '. The host map has no entry for these projects and no fallback ("*") is defined. Add entries to FACTORY_FRONT_BUILD_HOST_MAP.',
      ownerProjects: [...ownerProjects]
    };
  }
  const validHosts = [];
  const invalidHosts = [];
  for (const host of hosts) {
    const candidatePaths = [
      join7(repoRoot, "apps", host, "project.json"),
      join7(repoRoot, "frontend", "apps", host, "project.json"),
      join7(repoRoot, host, "project.json")
    ];
    let hasBuildTarget = false;
    let found = false;
    for (const candidate of candidatePaths) {
      if (existsSync(candidate)) {
        found = true;
        try {
          const json = JSON.parse(readFileSync(candidate, "utf8"));
          if (json.targets && (json.targets["build"] !== void 0 || json.targets["build-angular"] !== void 0)) {
            hasBuildTarget = true;
          }
        } catch {
        }
        break;
      }
    }
    if (!found) {
      console.warn(
        '[oracle-command] resolveBuildHosts: project.json not found for host "' + host + '" in conventional paths (' + candidatePaths.map((p) => p.replace(repoRoot, "<root>")).join(", ") + "). Accepting host tentatively \u2014 verify that it has a build target."
      );
      validHosts.push(host);
      continue;
    }
    if (hasBuildTarget) {
      validHosts.push(host);
    } else {
      invalidHosts.push(host);
      console.warn(
        '[oracle-command] resolveBuildHosts: host "' + host + '" has no `build` or `build-angular` target in its project.json. Excluding from build oracle scope. Update FACTORY_FRONT_BUILD_HOST_MAP to use a host with a real build target.'
      );
    }
  }
  if (validHosts.length === 0) {
    return {
      noHost: true,
      reason: "All resolved hosts (" + hosts.join(", ") + ") lack a `build` or `build-angular` target in their project.json. Owner projects: " + ownerProjects.join(", ") + ". Excluded hosts: " + invalidHosts.join(", ") + ". Update FACTORY_FRONT_BUILD_HOST_MAP to reference apps with real build targets.",
      ownerProjects: [...ownerProjects]
    };
  }
  return validHosts;
}
function resolveOwnerProjects(files, repoRoot) {
  const seen = /* @__PURE__ */ new Set();
  const projects = [];
  for (const file of files) {
    const absoluteFile = join7(repoRoot, file);
    let dir = dirname3(absoluteFile);
    while (dir.length >= repoRoot.length) {
      const candidate = join7(dir, "project.json");
      if (existsSync(candidate)) {
        try {
          const json = JSON.parse(readFileSync(candidate, "utf8"));
          if (json.name && typeof json.name === "string") {
            if (!seen.has(json.name)) {
              seen.add(json.name);
              projects.push(json.name);
            }
          }
        } catch {
        }
        break;
      }
      const parent = dirname3(dir);
      if (parent === dir) break;
      dir = parent;
    }
  }
  return projects;
}
function extractTarget(command) {
  const shortMatch = command.match(/(?:^|\s)-t\s+(\S+)/);
  if (shortMatch) return shortMatch[1] ?? null;
  const longMatch = command.match(/(?:^|\s)--target=(\S+)/);
  if (longMatch) return longMatch[1] ?? null;
  return null;
}
function buildOracleCommand(oracle, files, repoRoot) {
  if (oracle.buildHostArg) {
    const ownerProjects = files.length > 0 ? resolveOwnerProjects(files, repoRoot) : [];
    if (ownerProjects.length === 0 && files.length > 0) {
      return {
        noHost: true,
        reason: "No Nx owner project found for modified files: " + files.join(", ") + ". Modified files may be in root-level directories without a project.json.",
        ownerProjects: []
      };
    }
    if (ownerProjects.length === 0) {
      return {
        noHost: true,
        reason: "No files provided to build oracle. Cannot resolve build host applications.",
        ownerProjects: []
      };
    }
    const hostsResult = resolveBuildHosts(ownerProjects, repoRoot);
    if (!Array.isArray(hostsResult)) {
      return hostsResult;
    }
    if (oracle.command.includes("--projects=")) {
      return oracle.command;
    }
    return oracle.command + " --projects=" + hostsResult.join(",");
  }
  if (!oracle.filesArg) {
    return oracle.command;
  }
  if (files.length === 0) {
    return oracle.command;
  }
  const target = extractTarget(oracle.command);
  if (!target) {
    console.warn(
      "[oracle-command] Impossible d'extraire la cible Nx depuis la commande template : " + oracle.command + ". La commande template est retourn\xE9e sans modification. V\xE9rifier que FACTORY_COMMAND_FRONT contient `-t <cible>` ou `--target=<cible>`."
    );
    return oracle.command;
  }
  const projects = resolveOwnerProjects(files, repoRoot);
  if (projects.length === 0) {
    console.warn(
      "[oracle-command] Aucun projet Nx trouv\xE9 pour les fichiers modifi\xE9s : " + files.join(", ") + ". La commande template est retourn\xE9e sans modification."
    );
    return oracle.command;
  }
  return "pnpm nx run-many --target=" + target + " --projects=" + projects.join(",") + " --skip-nx-cache";
}

// ../src/application/oracle/oracle-executor.ts
import { spawn, spawnSync } from "node:child_process";
import { createHash as createHash9 } from "node:crypto";
import { readFileSync as readFileSync2 } from "node:fs";
import { realpath } from "node:fs/promises";
import { isAbsolute as isAbsolute3, join as join8 } from "node:path";
var MAX_OUTPUT_CHARS = 1e5;
var LIMIT = 16384;
function truncate(s) {
  if (s.length <= MAX_OUTPUT_CHARS) return s;
  return s.slice(0, MAX_OUTPUT_CHARS) + `
[... tronqu\xE9 \xE0 ${MAX_OUTPUT_CHARS} caract\xE8res]`;
}
function runCommand(command, { cwd, timeoutMs } = {}) {
  const start = Date.now();
  const result = spawnSync(command, {
    shell: true,
    ...cwd === void 0 ? {} : { cwd },
    ...timeoutMs === void 0 ? {} : { timeout: timeoutMs },
    encoding: "utf8",
    maxBuffer: 200 * 1024 * 1024
    // 200 MB pour éviter les troncatures internes
  });
  const durationMs = Date.now() - start;
  const errorCode = result.error?.code;
  const timedOut = result.signal === "SIGTERM" || errorCode === "ETIMEDOUT";
  return {
    exitCode: timedOut ? -1 : result.status ?? -1,
    stdout: truncate(result.stdout ?? ""),
    stderr: truncate(result.stderr ?? ""),
    durationMs,
    timedOut
  };
}
function contentFingerprint(cwd, relPath) {
  try {
    return createHash9("sha256").update(readFileSync2(join8(cwd, relPath))).digest("hex");
  } catch {
    return "unreadable";
  }
}
function snapshotDiff(cwd) {
  const diffResult = runCommand("git diff HEAD --name-only", { cwd });
  const untrackedResult = runCommand("git ls-files --others --exclude-standard", { cwd });
  const modified = /* @__PURE__ */ new Map();
  for (const path of diffResult.stdout.split("\n").filter(Boolean)) {
    modified.set(path, contentFingerprint(cwd, path));
  }
  const untracked = /* @__PURE__ */ new Map();
  for (const path of untrackedResult.stdout.split("\n").filter(Boolean)) {
    untracked.set(path, contentFingerprint(cwd, path));
  }
  return { modified, untracked };
}
function diffSince(before, cwd) {
  return diffSnapshots(before, snapshotDiff(cwd));
}
function classifyOracleExecution(definition, result) {
  if (result.spawnError || result.timedOut || result.signal)
    return { classification: "ORACLE_INFRASTRUCTURE", outcome: "indeterminate" };
  if (result.exitCode !== 0) return { classification: "PRODUCT_REGRESSION", outcome: "fail" };
  if (definition.success.requireWork && result.counts.executed === 0)
    return { classification: "EMPTY_SUCCESS", outcome: "indeterminate" };
  return { classification: "CLEAN", outcome: "pass" };
}
async function validateOracleRoot(repoRoot) {
  if (typeof repoRoot !== "string" || !isAbsolute3(repoRoot))
    throw Object.assign(new Error("INVALID_ORACLE_ROOT"), { code: "INVALID_ORACLE_ROOT" });
  return realpath(repoRoot);
}
function oracleRootIdentity(repoRoot) {
  return `sha256:${createHash9("sha256").update(repoRoot).digest("hex")}`;
}
function processEnvironment(source = process.env) {
  const env = {};
  for (const key of ["PATH", "HOME", "TMPDIR", "TMP", "TEMP", "SystemRoot", "WINDIR", "PATHEXT"])
    if (typeof source[key] === "string") env[key] = source[key];
  return env;
}
function bounded(chunks) {
  const value = Buffer.concat(chunks).toString("utf8");
  return { excerpt: value.slice(0, LIMIT), truncated: value.length > LIMIT };
}
function executeOracle(definition, {
  repoRoot,
  countTaskOutcomes: countTaskOutcomes2 = countTaskOutcomes,
  spawnImpl = spawn,
  environment = process.env
}) {
  return new Promise((resolve10) => {
    const started = Date.now();
    const stdout = [];
    const stderr = [];
    let timedOut = false;
    let spawnError = null;
    let settled = false;
    const child = spawnImpl(definition.argv[0] ?? "", definition.argv.slice(1), {
      cwd: repoRoot,
      env: processEnvironment(environment),
      stdio: ["ignore", "pipe", "pipe"],
      shell: false,
      detached: process.platform !== "win32"
    });
    child.stdout?.on("data", (x) => stdout.push(Buffer.from(x)));
    child.stderr?.on("data", (x) => stderr.push(Buffer.from(x)));
    child.on("error", (e) => {
      spawnError = e.code ?? "SPAWN_FAILURE";
    });
    const timer = setTimeout(() => {
      timedOut = true;
      try {
        if (process.platform === "win32" || child.pid === void 0) child.kill("SIGKILL");
        else process.kill(-child.pid, "SIGKILL");
      } catch {
        try {
          child.kill("SIGKILL");
        } catch {
        }
      }
    }, definition.timeoutMs);
    child.on("close", (code, signal) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const out = bounded(stdout);
      const err = bounded(stderr);
      const counts = countTaskOutcomes2(`${out.excerpt}
${err.excerpt}`);
      const base = {
        exitCode: code,
        signal,
        timedOut,
        durationMs: Date.now() - started,
        spawnError,
        counts,
        stdout: out,
        stderr: err
      };
      resolve10({ ...base, ...classifyOracleExecution(definition, base) });
    });
  });
}
function oracleArtifact(result) {
  const raw = JSON.stringify({ stdout: result.stdout.excerpt, stderr: result.stderr.excerpt });
  return { raw, hash: `sha256:${createHash9("sha256").update(raw).digest("hex")}` };
}

// ../src/application/oracle/oracle-baseline.ts
var TS_INFRASTRUCTURE_CODES = /* @__PURE__ */ new Set(["TS5090", "TS6059", "TS18003", "TS6305", "TS6307"]);
var BASELINE_TAIL_LINES = 40;
function stripAnsi(s) {
  return s.replace(/\u001b\[[0-9;]*m/g, "");
}
function isNoiseLine(line) {
  const t = line.trim();
  if (!t) return true;
  if (t.startsWith("NX") || t.startsWith("> NX") || t.includes("Running target")) return true;
  if (t.includes("Successfully ran target")) return true;
  if (t.includes("Nx read the output from the cache")) return true;
  if (t.includes("existing outputs match the cache")) return true;
  if (t.startsWith("> nx run ")) return true;
  if (t.startsWith("> Task ")) return true;
  if (t.includes("Failed tasks:") || t.includes("Hint: ")) return true;
  if (t.startsWith("PASS ") && !t.includes(".spec.") && !t.includes(".test.")) return true;
  return false;
}
function normalizeDiagnosticLine(rawLine) {
  const line = stripAnsi(rawLine).trim();
  if (!line || isNoiseLine(line)) return null;
  const tsMatch = line.match(/([^\s(]+(?:\.tsx?|\.json))(\(\d+,\d+\))?:\s*error\s+(TS\d+):/) ?? line.match(/([^\s(]+)(\(\d+,\d+\))?:\s*error\s+(TS\d+):/);
  if (tsMatch) {
    const filePath = tsMatch[1] ?? "";
    const location = tsMatch[2] ?? "";
    const code = tsMatch[3] ?? "";
    const locMatch = location.match(/(\d+),(\d+)/);
    const locStr = locMatch ? `:${locMatch[1]}:${locMatch[2]}` : "";
    return `TS:${code}:${filePath}${locStr}`;
  }
  const jestMatch = line.match(/^\u25cf\s+(.+?)\s+[›>]\s+(.+)$/);
  if (jestMatch) {
    const suite = (jestMatch[1] ?? "").trim();
    const testName = (jestMatch[2] ?? "").trim();
    return `TEST:${suite}:${testName}`;
  }
  return `RAW:${line.slice(0, 120)}`;
}
function tailLines(text2, n) {
  return text2.split("\n").filter((line) => line.trim().length > 0).slice(-n);
}
function extractTypeDiagnostics(stdout, stderr, maxLines) {
  const stdoutLines = stdout.split("\n");
  const diagnosticIndices = /* @__PURE__ */ new Set();
  for (let i = 0; i < stdoutLines.length; i++) {
    if ((stdoutLines[i] ?? "").includes("error TS")) {
      if (i > 0 && (stdoutLines[i - 1] ?? "").trim().length > 0) diagnosticIndices.add(i - 1);
      diagnosticIndices.add(i);
      if (i + 1 < stdoutLines.length && (stdoutLines[i + 1] ?? "").trim().length > 0) diagnosticIndices.add(i + 1);
      if (i + 2 < stdoutLines.length && (stdoutLines[i + 2] ?? "").trim().includes("~")) diagnosticIndices.add(i + 2);
    }
  }
  if (diagnosticIndices.size === 0) {
    const source = stderr.trim().length > 0 ? stderr : stdout;
    return tailLines(source, maxLines);
  }
  const sorted = [...diagnosticIndices].sort((a, b) => a - b);
  return sorted.map((i) => stdoutLines[i] ?? "").filter((line) => line.trim().length > 0).slice(-maxLines);
}
function extractTestDiagnostics(stdout, stderr, maxLines) {
  const plain = stripAnsi(stdout);
  const lines = plain.split("\n");
  const diagnosticIndices = /* @__PURE__ */ new Set();
  for (let i = 0; i < lines.length; i++) {
    const trimmed = (lines[i] ?? "").trim();
    if (trimmed.startsWith("\u25CF ") || trimmed.startsWith("\u25CF\u25CF")) {
      diagnosticIndices.add(i);
      let j = i + 1;
      while (j < lines.length) {
        const next = (lines[j] ?? "").trim();
        if (next.startsWith("\u25CF ") || next.includes("Running target") || next.includes("Failed tasks")) break;
        diagnosticIndices.add(j);
        j++;
      }
      continue;
    }
    if (trimmed.startsWith("FAIL ") && (trimmed.includes(".spec.") || trimmed.includes(".test."))) {
      diagnosticIndices.add(i);
      continue;
    }
    if (trimmed.startsWith("at ") && trimmed.includes(".spec.") || trimmed.startsWith("at ") && trimmed.includes(".test.")) {
      diagnosticIndices.add(i);
      continue;
    }
    if (trimmed.startsWith("Expected:") || trimmed.startsWith("Received:") || trimmed.startsWith("Expected value") || trimmed.startsWith("Received value") || trimmed.startsWith("expect(") || trimmed.startsWith("- Expected") || trimmed.startsWith("+ Received")) {
      if (i > 0) diagnosticIndices.add(i - 1);
      diagnosticIndices.add(i);
      if (i + 1 < lines.length) diagnosticIndices.add(i + 1);
      continue;
    }
  }
  if (diagnosticIndices.size === 0) {
    const source = stderr.trim().length > 0 ? stderr : stdout;
    return tailLines(source, maxLines);
  }
  const sorted = [...diagnosticIndices].sort((a, b) => a - b);
  return sorted.map((i) => lines[i] ?? "").filter((line) => line.trim().length > 0).slice(-maxLines);
}
function extractOracleDiagnostics(oracleName, stdout, stderr, maxLines = 200) {
  let rawLines;
  if (oracleName === "types") {
    rawLines = extractTypeDiagnostics(stdout, stderr, maxLines);
  } else if (oracleName === "tests") {
    rawLines = extractTestDiagnostics(stdout, stderr, maxLines);
  } else {
    const source = stderr.trim().length > 0 ? stderr : stdout;
    rawLines = source.split("\n").map((l) => stripAnsi(l)).filter((l) => l.trim().length > 0 && !isNoiseLine(l)).slice(-maxLines);
  }
  const identities = [];
  for (const line of rawLines) {
    const id2 = normalizeDiagnosticLine(line);
    if (id2 !== null) identities.push(id2);
  }
  const seen = /* @__PURE__ */ new Set();
  const uniqueIdentities = [];
  for (const id2 of identities) {
    if (!seen.has(id2)) {
      seen.add(id2);
      uniqueIdentities.push(id2);
    }
  }
  return { identities: uniqueIdentities, rawLines };
}
function isInfrastructureIdentity(identity) {
  if (!identity.startsWith("TS:")) return false;
  const parts = identity.split(":");
  return TS_INFRASTRUCTURE_CODES.has(parts[1] ?? "");
}
function runBaselineOracle(params) {
  const { oracle, planFiles, repoRoot, timeoutMs } = params;
  const builtCommand = buildOracleCommand(oracle, planFiles, repoRoot);
  const command = typeof builtCommand === "string" ? builtCommand : builtCommand;
  const cwd = oracle.cwd;
  const projects = oracle.filesArg ? resolveOwnerProjects(planFiles, repoRoot) : [];
  const result = runCommand(command, { cwd, timeoutMs });
  const tasks = countTaskOutcomes(result.stdout + "\n" + result.stderr);
  const timedOut = result.timedOut;
  const emptySuccess = result.exitCode === 0 && tasks.executed === 0;
  const { identities, rawLines } = result.exitCode !== 0 && !timedOut && !emptySuccess ? extractOracleDiagnostics(oracle.name, result.stdout, result.stderr) : { identities: [], rawLines: [] };
  const evidenceParts = [
    `exitCode=${result.exitCode}`,
    `durationMs=${result.durationMs}`,
    timedOut ? "TIMED_OUT" : null,
    emptySuccess ? "EMPTY_SUCCESS" : null,
    `tasks.executed=${tasks.executed}`,
    `tasks.fromCache=${tasks.fromCache}`,
    projects.length > 0 ? `projects=${projects.join(",")}` : null
  ].filter((part) => part !== null);
  return {
    command,
    cwd,
    projects,
    exitCode: result.exitCode,
    timedOut,
    emptySuccess,
    durationMs: result.durationMs,
    tasks,
    diagnosticIdentities: identities,
    rawDiagnosticLines: rawLines.slice(0, BASELINE_TAIL_LINES),
    executionEvidence: evidenceParts.join(", "),
    ranAt: (/* @__PURE__ */ new Date()).toISOString()
  };
}
function classifyOracleResult(params) {
  const { oracle, baseline, postEdit, changedFiles, plannedFiles } = params;
  const baselinePassed = baseline !== null && baseline.exitCode === 0 && !baseline.timedOut && !baseline.emptySuccess;
  const postEditPassed = postEdit.exitCode === 0 && !postEdit.timedOut && !postEdit.emptySuccess;
  if (postEditPassed) {
    return {
      classification: "CLEAN",
      reason: "Post-edit oracle passed.",
      baselineIdentities: baseline?.diagnosticIdentities ?? [],
      postEditIdentities: [],
      newDiagnostics: [],
      preExistingDiagnostics: [],
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: true
    };
  }
  const { identities: postEditIdentities, rawLines: postEditRawLines } = !postEdit.timedOut && !postEdit.emptySuccess ? extractOracleDiagnostics(oracle.name, postEdit.stdout, postEdit.stderr) : { identities: [], rawLines: [] };
  const baselineIdentities = baseline?.diagnosticIdentities ?? [];
  const baselineSet = new Set(baselineIdentities);
  if (postEdit.timedOut || postEdit.emptySuccess) {
    const reason = postEdit.timedOut ? `Oracle timed out (no verdict on code).` : `Oracle empty success (tasks.executed === 0, no verdict on code).`;
    return {
      classification: "ORACLE_INFRASTRUCTURE",
      reason,
      baselineIdentities,
      postEditIdentities: [],
      newDiagnostics: [],
      preExistingDiagnostics: [],
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: false
    };
  }
  if (baseline !== null && (baseline.timedOut || baseline.emptySuccess)) {
    return {
      classification: "ORACLE_INFRASTRUCTURE",
      reason: baseline.timedOut ? "Baseline oracle timed out \u2014 cannot compare post-edit diagnostics." : "Baseline oracle had empty success \u2014 cannot compare post-edit diagnostics.",
      baselineIdentities: [],
      postEditIdentities,
      newDiagnostics: postEditIdentities,
      preExistingDiagnostics: [],
      newDiagnosticLines: postEditRawLines,
      baselinePassed: false,
      postEditPassed: false
    };
  }
  const infraIdentities = postEditIdentities.filter(isInfrastructureIdentity);
  const productIdentities = postEditIdentities.filter((id2) => !isInfrastructureIdentity(id2));
  if (infraIdentities.length > 0 && productIdentities.length === 0) {
    return {
      classification: "ORACLE_INFRASTRUCTURE",
      reason: `Only TypeScript infrastructure/config error codes found: ${infraIdentities.map((id2) => id2.split(":")[1]).join(", ")}. Not a product regression.`,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics: infraIdentities,
      preExistingDiagnostics: [],
      newDiagnosticLines: postEditRawLines,
      baselinePassed,
      postEditPassed: false
    };
  }
  const newProductDiagnostics = productIdentities.filter((id2) => !baselineSet.has(id2));
  const preExistingProductDiagnostics = productIdentities.filter((id2) => baselineSet.has(id2));
  const newInfraDiagnostics = infraIdentities.filter((id2) => !baselineSet.has(id2));
  const newDiagnostics = [...newProductDiagnostics, ...newInfraDiagnostics];
  const preExistingDiagnostics = [
    ...preExistingProductDiagnostics,
    ...infraIdentities.filter((id2) => baselineSet.has(id2))
  ];
  const newDiagnosticLines = postEditRawLines.filter((line) => {
    const id2 = normalizeDiagnosticLine(line);
    return id2 !== null && newDiagnostics.includes(id2);
  });
  if (newProductDiagnostics.length === 0 && preExistingProductDiagnostics.length > 0) {
    return {
      classification: "BASELINE_FAILURE",
      reason: `All ${preExistingProductDiagnostics.length} post-edit diagnostic(s) were already present at baseline. The edit did not introduce new failures.`,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics: [],
      preExistingDiagnostics,
      newDiagnosticLines: [],
      baselinePassed,
      postEditPassed: false
    };
  }
  if (newProductDiagnostics.length > 0) {
    const scopeFiles = /* @__PURE__ */ new Set([...changedFiles, ...plannedFiles]);
    const allNewInScope = newProductDiagnostics.every((id2) => {
      if (id2.startsWith("TS:")) {
        const parts = id2.split(":");
        const filePath = parts[2] ?? "";
        if (!filePath) return false;
        return [...scopeFiles].some((sf) => filePath.includes(sf) || sf.includes(filePath) || filePath === sf);
      }
      return true;
    });
    if (!allNewInScope) {
      return {
        classification: "INDETERMINATE_OUT_OF_SCOPE",
        reason: `New diagnostics reference files outside the union of planned and changed files. Cannot confidently attribute to this edit.`,
        baselineIdentities,
        postEditIdentities,
        newDiagnostics,
        preExistingDiagnostics,
        newDiagnosticLines,
        baselinePassed,
        postEditPassed: false
      };
    }
  }
  if (newProductDiagnostics.length > 0) {
    const reason = baselinePassed ? `Baseline passed; post-edit introduced ${newProductDiagnostics.length} new diagnostic(s).` : `Baseline failed; post-edit introduced ${newProductDiagnostics.length} new diagnostic(s) beyond baseline.`;
    return {
      classification: "PRODUCT_REGRESSION",
      reason,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics,
      preExistingDiagnostics,
      newDiagnosticLines,
      baselinePassed,
      postEditPassed: false
    };
  }
  return {
    classification: "ORACLE_INFRASTRUCTURE",
    reason: "No classifiable diagnostics found in post-edit output.",
    baselineIdentities,
    postEditIdentities,
    newDiagnostics: [],
    preExistingDiagnostics: [],
    newDiagnosticLines: [],
    baselinePassed,
    postEditPassed: false
  };
}
function buildQuarantineRecord(params) {
  const { oracleName, classification, reason, baseline, postEdit, classificationResult, humanDecision, humanMessage } = params;
  return {
    quarantinedAt: (/* @__PURE__ */ new Date()).toISOString(),
    oracleName,
    classification,
    reason,
    humanDecision,
    humanMessage: humanMessage || null,
    // The oracle remains visibly failed — never rewritten as passed.
    oracleFailed: true,
    baseline: baseline ? {
      exitCode: baseline.exitCode,
      timedOut: baseline.timedOut,
      emptySuccess: baseline.emptySuccess,
      executionEvidence: baseline.executionEvidence,
      diagnosticCount: baseline.diagnosticIdentities.length
    } : null,
    postEdit: {
      exitCode: postEdit.exitCode,
      timedOut: postEdit.timedOut,
      emptySuccess: postEdit.emptySuccess,
      durationMs: postEdit.durationMs
    },
    diagnostics: {
      baseline: classificationResult.baselineIdentities,
      postEdit: classificationResult.postEditIdentities,
      new: classificationResult.newDiagnostics,
      preExisting: classificationResult.preExistingDiagnostics
    }
  };
}

// ../src/adapters/persistence/work-unit-environment-store.ts
import { createHash as createHash10, randomBytes as randomBytes4 } from "node:crypto";
import { appendFile as appendFile2, lstat, mkdir as mkdir3, open as open3, readFile as readFile4, readdir as readdir3, realpath as realpath2, rename as rename3, rm as rm3 } from "node:fs/promises";
import { dirname as dirname4, isAbsolute as isAbsolute4, join as join9, relative as relative2, sep } from "node:path";
var ENVIRONMENT_STORE_ERROR_CODES = Object.freeze({
  INVALID_DATA_ROOT: "INVALID_DATA_ROOT",
  INVALID_ENVIRONMENT: "INVALID_ENVIRONMENT",
  INVALID_NAMESPACE: "INVALID_NAMESPACE",
  NOT_FOUND: "NOT_FOUND",
  REVISION_CONFLICT: "REVISION_CONFLICT",
  INVALID_TRANSITION: "INVALID_TRANSITION",
  CORRUPT_STORAGE: "CORRUPT_STORAGE"
});
var WorkUnitEnvironmentStoreError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause ? { cause } : void 0);
    this.code = code;
    this.details = details;
  }
};
var digest = (value) => createHash10("sha256").update(value).digest("hex");
var snapshotHash = (e) => digest(JSON.stringify(e, Object.keys(e).sort()));
var contained = (root, path) => {
  const r = relative2(root, path);
  return r !== "" && !r.startsWith(`..${sep}`) && r !== ".." && !isAbsolute4(r);
};
async function syncDir(p) {
  const h = await open3(p, "r");
  try {
    await h.sync();
  } finally {
    await h.close();
  }
}
async function atomic(p, v) {
  await mkdir3(dirname4(p), { recursive: true });
  const t = `${p}.tmp-${process.pid}-${randomBytes4(6).toString("hex")}`;
  const h = await open3(t, "wx", 384);
  try {
    await h.writeFile(`${JSON.stringify(v)}
`);
    await h.sync();
  } finally {
    await h.close();
  }
  await rename3(t, p);
  await syncDir(dirname4(p));
}
async function append(p, v) {
  await appendFile2(p, `${JSON.stringify(v)}
`, { encoding: "utf8", mode: 384 });
  const h = await open3(p, "r");
  try {
    await h.sync();
  } finally {
    await h.close();
  }
}
var WorkUnitEnvironmentStore = class {
  dataRoot;
  fault;
  locks;
  root;
  constructor(dataRoot, { fault = async () => {
  } } = {}) {
    if (typeof dataRoot !== "string" || !isAbsolute4(dataRoot))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_DATA_ROOT);
    this.dataRoot = dataRoot;
    this.fault = fault;
    this.locks = /* @__PURE__ */ new Map();
    this.root = null;
  }
  async initialize() {
    await mkdir3(join9(this.dataRoot, "environments"), { recursive: true });
    this.root = await realpath2(join9(this.dataRoot, "environments"));
  }
  async _safeDirectory(path, { missing = true } = {}) {
    let stat2;
    try {
      stat2 = await lstat(path);
    } catch (error2) {
      if (error2?.code === "ENOENT" && missing) return false;
      throw new WorkUnitEnvironmentStoreError(
        ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE,
        { artifact: "path" },
        error2
      );
    }
    if (stat2.isSymbolicLink() || !stat2.isDirectory())
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: "unsafe_path"
      });
    const canonical5 = await realpath2(path);
    if (path !== this.root && !contained(this.root, canonical5))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: "path_escape"
      });
    return true;
  }
  async _guard(p, { environmentMayBeMissing = true } = {}) {
    await this._safeDirectory(this.root, { missing: false });
    const namespaceDirectory = dirname4(p.directory);
    const namespaceExists = await this._safeDirectory(namespaceDirectory, { missing: true });
    if (!namespaceExists) return;
    await this._safeDirectory(p.directory, { missing: environmentMayBeMissing });
  }
  _namespace(ns) {
    if (!validateNamespaceId(ns).ok)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_NAMESPACE);
  }
  paths(ns, id2) {
    this._namespace(ns);
    if (!this.root || typeof id2 !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id2))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT);
    const directory = join9(this.root, ns, digest(`${ns}:${id2}`));
    if (!contained(this.root, directory))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT);
    return {
      directory,
      snapshot: join9(directory, "environment.json"),
      events: join9(directory, "events.jsonl"),
      pending: join9(directory, "pending.json")
    };
  }
  _locked(ns, id2, fn) {
    this._namespace(ns);
    const k = `${ns}\0${id2}`;
    const p = this.locks.get(k) ?? Promise.resolve();
    const o = p.then(fn);
    const t = o.catch(() => {
    });
    this.locks.set(k, t);
    return o.finally(() => {
      if (this.locks.get(k) === t) this.locks.delete(k);
    });
  }
  async _json(p, missing = null) {
    try {
      return JSON.parse(await readFile4(p, "utf8"));
    } catch (e) {
      if (e?.code === "ENOENT") return missing;
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, e);
    }
  }
  async _recover(p, ns, id2) {
    const q = await this._json(p.pending);
    if (!q) return;
    const valid = q && Number.isSafeInteger(q.revision) && q.revision > 0 && q.environment?.namespaceId === ns && q.environment?.environmentId === id2 && validateWorkUnitEnvironment(q.environment).ok && q.environmentHash === snapshotHash(q.environment);
    if (!valid)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "pending" });
    let facts;
    try {
      facts = (await readFile4(p.events, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    } catch (e) {
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "journal" }, e);
    }
    if (!facts.some((f) => f.revision === q.revision && f.environmentId === id2 && f.environmentHash === q.environmentHash))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: "recovery_binding"
      });
    await atomic(p.snapshot, q);
    await rm3(p.pending);
    await syncDir(p.directory);
  }
  async read(ns, id2) {
    this._namespace(ns);
    const p = this.paths(ns, id2);
    await this._guard(p);
    await this._recover(p, ns, id2);
    const s = await this._json(p.snapshot);
    if (!s) return null;
    if (!Number.isSafeInteger(s.revision) || s.environmentHash !== snapshotHash(s.environment) || !validateWorkUnitEnvironment(s.environment).ok)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE);
    return s;
  }
  async list(ns, { states } = {}) {
    this._namespace(ns);
    let es;
    const root = join9(this.root, ns);
    const probe = this.paths(ns, "list-probe");
    await this._guard(probe);
    try {
      es = await readdir3(root, { withFileTypes: true });
    } catch (e) {
      if (e?.code === "ENOENT") return [];
      throw e;
    }
    const out = [];
    for (const x of es) {
      const d = join9(root, x.name);
      const p = {
        directory: d,
        snapshot: join9(d, "environment.json"),
        events: join9(d, "events.jsonl"),
        pending: join9(d, "pending.json")
      };
      await this._guard(p, { environmentMayBeMissing: false });
      const pending = await this._json(p.pending);
      if (pending) await this._recover(p, ns, pending.environment?.environmentId);
      const s = await this._json(p.snapshot);
      if (s && s.environment.namespaceId === ns && (!states || states.includes(s.environment.lifecycleState)))
        out.push(s);
    }
    return out;
  }
  async reserve(e) {
    const v = validateWorkUnitEnvironment(e);
    if (!v.ok) return v;
    this._namespace(v.environment.namespaceId);
    return this._locked(v.environment.namespaceId, v.environment.environmentId, async () => {
      const c = await this.read(v.environment.namespaceId, v.environment.environmentId);
      if (c)
        return JSON.stringify(c.environment) === JSON.stringify(v.environment) ? { ok: true, changed: false, snapshot: c } : { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } };
      return this._write(null, v.environment, "provisioning_reserved");
    });
  }
  async transition(ns, id2, next, { expectedRevision, errorCode } = {}) {
    this._namespace(ns);
    return this._locked(ns, id2, async () => {
      const c = await this.read(ns, id2);
      if (!c) return { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.NOT_FOUND } };
      if (expectedRevision !== void 0 && expectedRevision !== c.revision)
        return { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.REVISION_CONFLICT } };
      if (JSON.stringify(c.environment) === JSON.stringify(next)) return { ok: true, changed: false, snapshot: c };
      for (const field of [
        "schemaVersion",
        "environmentId",
        "workUnitId",
        "workflowId",
        "namespaceId",
        "repoRoot",
        "integrationBranch",
        "branch",
        "worktreePath",
        "baseCommit",
        "createdAt",
        "createdBy"
      ])
        if (c.environment[field] !== next[field])
          return { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } };
      if (c.environment.parentCaseId && next.parentCaseId !== c.environment.parentCaseId)
        return { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } };
      const f = c.environment.lifecycleState;
      const t = next.lifecycleState;
      const allowed = f === "provisioning" && ["provisioning", "active", "error"].includes(t) || f === "active" && ["completed", "abandoned", "error"].includes(t) || ["completed", "abandoned", "error"].includes(f) && t === "removed";
      if (!allowed) return { ok: false, error: { code: ENVIRONMENT_STORE_ERROR_CODES.INVALID_TRANSITION } };
      const v = validateWorkUnitEnvironment(next);
      if (!v.ok) return v;
      return this._write(
        c,
        v.environment,
        t === "active" ? "parent_case_bound" : t === "removed" ? "environment_removed" : f === t ? "environment_provisioned" : "environment_state_changed",
        errorCode
      );
    });
  }
  async _write(c, e, kind, errorCode) {
    const p = this.paths(e.namespaceId, e.environmentId);
    await this._guard(p);
    const revision = (c?.revision ?? 0) + 1;
    const environmentHash = snapshotHash(e);
    const s = { revision, environmentHash, environment: e };
    await mkdir3(p.directory, { recursive: true });
    await this._guard(p, { environmentMayBeMissing: false });
    await atomic(p.pending, s);
    await this.fault("after-pending");
    await append(p.events, {
      kind,
      revision,
      environmentId: e.environmentId,
      environmentHash,
      lifecycleState: e.lifecycleState,
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      ...errorCode ? { errorCode } : {}
    });
    await this.fault("after-journal");
    await atomic(p.snapshot, s);
    await this.fault("after-snapshot");
    await rm3(p.pending, { force: true });
    return { ok: true, changed: true, snapshot: s };
  }
};

// ../src/application/environment/work-unit-environment-service.ts
import { randomUUID as randomUUID5 } from "node:crypto";
var machine = (e) => {
  const code = e?.code;
  return typeof code === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : "GIT_FAILED";
};
var sameIdentity = (environment, input, id2) => environment.environmentId === id2 && environment.workflowId === (input.workflowId ?? environment.workflowId) && environment.workUnitId === input.workUnitId && environment.namespaceId === input.namespaceId && environment.repoRoot === input.repoRoot && environment.integrationBranch === input.integrationBranch && environment.branch === input.branch && environment.worktreePath === input.worktreePath && environment.createdBy === input.createdBy;
var WorkUnitEnvironmentService = class {
  store;
  git;
  clock;
  idGenerator;
  fault;
  locks;
  constructor({
    store,
    git,
    clock = () => /* @__PURE__ */ new Date(),
    idGenerator = () => randomUUID5(),
    fault = async () => {
    }
  }) {
    this.store = store;
    this.git = git;
    this.clock = clock;
    this.idGenerator = idGenerator;
    this.fault = fault;
    this.locks = /* @__PURE__ */ new Map();
  }
  _locked(ns, id2, fn) {
    const k = `${ns}\0${id2}`;
    const p = this.locks.get(k) ?? Promise.resolve();
    const o = p.then(fn);
    const t = o.catch(() => {
    });
    this.locks.set(k, t);
    return o.finally(() => {
      if (this.locks.get(k) === t) this.locks.delete(k);
    });
  }
  async provision(input) {
    const id2 = input.environmentId ?? this.idGenerator();
    return this._locked(input.namespaceId, id2, () => this._provision(input, id2));
  }
  async _provision(input, id2) {
    let current = await this.store.read(input.namespaceId, id2);
    if (current) {
      const e = current.environment;
      if (!sameIdentity(e, input, id2)) return { ok: false, error: { code: "ENVIRONMENT_IDENTITY_CONFLICT" } };
      if (e.lifecycleState !== "provisioning")
        return e.lifecycleState === "error" ? { ok: false, error: { code: "ENVIRONMENT_ERROR" } } : { ok: false, error: { code: "INVALID_PROVISION" } };
      const reconciliation = await this.git.reconcile(e);
      if (reconciliation.status === "owned") {
        if (!e.baseCommit) return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
        return { ok: true, changed: false, snapshot: current, headCommit: reconciliation.headCommit ?? null };
      }
      if (reconciliation.status === "uncertain") return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
    }
    let reserved = current;
    try {
      const facts = await this.git.provisionWorktree(input, async (inspected) => {
        if (!reserved) {
          const descriptor = {
            schemaVersion: "1",
            environmentId: id2,
            workUnitId: input.workUnitId,
            ...input.workflowId ? { workflowId: input.workflowId } : {},
            namespaceId: input.namespaceId,
            ...input.businessRef ? { businessRef: input.businessRef } : {},
            ...input.businessType ? { businessType: input.businessType } : {},
            repoRoot: inspected.repoRoot,
            integrationBranch: inspected.integrationBranch,
            branch: input.branch,
            worktreePath: inspected.worktreePath,
            baseCommit: inspected.baseCommit,
            createdAt: this.clock().toISOString(),
            createdBy: input.createdBy,
            lifecycleState: "provisioning"
          };
          const r = await this.store.reserve(descriptor);
          if (!r.ok) throw Object.assign(new Error("STORE_REJECTED"), { code: "STORE_REJECTED" });
          reserved = r.snapshot;
        }
      });
      await this.fault("after-git-add", { namespaceId: input.namespaceId, environmentId: id2 });
      current = await this.store.read(input.namespaceId, id2);
      if (current.environment.baseCommit !== facts.baseCommit)
        return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
      return { ok: true, changed: false, snapshot: current, headCommit: facts.headCommit };
    } catch (error2) {
      current = await this.store.read(input.namespaceId, id2);
      if (current?.environment.lifecycleState === "provisioning") {
        let reconciliation;
        try {
          reconciliation = await this.git.reconcile(current.environment);
        } catch {
          return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
        }
        if (reconciliation.status === "owned") return { ok: false, error: { code: "POST_ADD_RECOVERY_REQUIRED" } };
        if (reconciliation.status === "uncertain") return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
        await this.store.transition(
          input.namespaceId,
          id2,
          { ...current.environment, lifecycleState: "error" },
          { expectedRevision: current.revision, errorCode: machine(error2) }
        );
      }
      throw error2;
    }
  }
  async bindParentCase(ns, id2, caseId) {
    return this._locked(ns, id2, async () => {
      const c = await this.store.read(ns, id2);
      if (c?.environment.lifecycleState === "active" && c.environment.parentCaseId === caseId)
        return { ok: true, changed: false, snapshot: c };
      if (!c || c.environment.lifecycleState !== "provisioning" || !c.environment.baseCommit)
        return { ok: false, error: { code: "INVALID_BIND" } };
      const existing = await this.store.list(ns, { states: ["active"] });
      if (existing.some(
        (snapshot) => snapshot.environment.environmentId !== id2 && snapshot.environment.parentCaseId === caseId
      ))
        return { ok: false, error: { code: "WRITER_ALREADY_ACTIVE" } };
      if (existing.some(
        (snapshot) => snapshot.environment.environmentId !== id2 && snapshot.environment.worktreePath === c.environment.worktreePath
      ))
        return { ok: false, error: { code: "WORKTREE_ALREADY_ACTIVE" } };
      return this.store.transition(
        ns,
        id2,
        { ...c.environment, parentCaseId: caseId, lifecycleState: "active" },
        { expectedRevision: c.revision }
      );
    });
  }
  async inspect(ns, id2) {
    return this._locked(ns, id2, async () => {
      const snapshot = await this.store.read(ns, id2);
      if (!snapshot) return { ok: false, error: { code: "NOT_FOUND" } };
      if (snapshot.environment.lifecycleState === "removed")
        return { ok: true, snapshot, reconciliation: { status: "absent" } };
      const reconciliation = await this.git.reconcile(snapshot.environment);
      if (reconciliation.status !== "owned")
        return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" }, snapshot, reconciliation };
      return { ok: true, snapshot, reconciliation };
    });
  }
  async setState(ns, id2, state) {
    return this._locked(ns, id2, async () => {
      const c = await this.store.read(ns, id2);
      return c ? this.store.transition(ns, id2, { ...c.environment, lifecycleState: state }, { expectedRevision: c.revision }) : { ok: false, error: { code: "NOT_FOUND" } };
    });
  }
  async remove(ns, id2) {
    return this._locked(ns, id2, async () => {
      const c = await this.store.read(ns, id2);
      if (!c) return { ok: false, error: { code: "NOT_FOUND" } };
      if (c.environment.lifecycleState === "removed") return { ok: true, changed: false, snapshot: c };
      if (!["completed", "abandoned", "error"].includes(c.environment.lifecycleState))
        return { ok: false, error: { code: "INVALID_REMOVE" } };
      await this.git.removeWorktree(c.environment);
      return this.store.transition(
        ns,
        id2,
        { ...c.environment, lifecycleState: "removed" },
        { expectedRevision: c.revision }
      );
    });
  }
  async listRecoveryCandidates(ns) {
    return this.store.list(ns, { states: ["provisioning", "error"] });
  }
};

// ../src/application/environment/work-unit-environment-controller.ts
var UUID2 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE3 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var ALLOWED = /* @__PURE__ */ new Set(["workflowId", "workUnitId", "integrationBranch", "branch"]);
var error = (send, status, code, message) => send(status, { error: { code, message } });
var publicEnvironment = (result) => {
  const bound = result.snapshot.environment.lifecycleState === "active" && result.reconciliation?.status === "owned";
  return {
    revision: result.snapshot.revision,
    environment: result.snapshot.environment,
    reconciliation: result.reconciliation ?? null,
    headCommit: (result.reconciliation?.status === "owned" ? result.reconciliation.headCommit : void 0) ?? result.headCommit ?? null,
    fileAccess: {
      status: bound ? "bound" : "blocked",
      code: bound ? null : "ENVIRONMENT_NOT_BOUND",
      rootPath: result.snapshot.environment.worktreePath
    }
  };
};
var WorkUnitEnvironmentController = class {
  store;
  git;
  policy;
  workflowStore;
  service;
  constructor({ store, git, policy, workflowStore, clock, idGenerator, fault }) {
    this.store = store;
    this.git = git;
    this.policy = policy;
    this.workflowStore = workflowStore;
    this.service = new WorkUnitEnvironmentService({
      store,
      git,
      ...clock ? { clock } : {},
      ...idGenerator ? { idGenerator } : {},
      ...fault ? { fault } : {}
    });
  }
  async initialize() {
    await this.store.initialize();
  }
  async provision({
    namespaceId,
    caseId,
    createdBy,
    body
  }) {
    if (!UUID2.test(namespaceId ?? "") || !UUID2.test(caseId ?? "") || !SAFE3.test(createdBy ?? ""))
      return { ok: false, status: 400, error: { code: "INVALID_TRUST_CONTEXT" } };
    const requestBody = body ?? {};
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(requestBody).some((key) => !ALLOWED.has(key)) || !SAFE3.test(requestBody.workflowId ?? "") || !SAFE3.test(requestBody.workUnitId ?? ""))
      return { ok: false, status: 400, error: { code: "INVALID_ENVIRONMENT_REQUEST" } };
    const roots = await this.policy.resolve(namespaceId, requestBody);
    if (!roots?.repoRoot || !roots?.worktreePath)
      return { ok: false, status: 422, error: { code: "ENVIRONMENT_POLICY_UNAVAILABLE" } };
    const workflow = await this.workflowStore?.read(namespaceId, requestBody.workflowId);
    if (!workflow?.instance || workflow.instance.controllerExecution?.kind !== "agentos" || workflow.instance.controllerExecution.caseId !== caseId)
      return { ok: false, status: 409, error: { code: "WORKFLOW_ENVIRONMENT_CONTEXT_MISMATCH" } };
    const environmentId = `${requestBody.workflowId}-${requestBody.workUnitId}`;
    const result = await this.service.provision({
      ...requestBody,
      environmentId,
      namespaceId,
      repoRoot: roots.repoRoot,
      worktreePath: roots.worktreePath,
      createdBy
    });
    if (!result.ok)
      return {
        ok: false,
        status: result.error.code === "ENVIRONMENT_IDENTITY_CONFLICT" ? 409 : 422,
        error: result.error
      };
    const bound = await this.service.bindParentCase(namespaceId, environmentId, caseId);
    if (!bound.ok) return { ok: false, status: 409, error: bound.error };
    const inspected = await this.service.inspect(namespaceId, environmentId);
    if (!inspected.ok) return { ok: false, status: 409, error: { code: "ENVIRONMENT_NOT_BOUND" } };
    const linked = await this.workflowStore?.bindEnvironment(namespaceId, requestBody.workflowId, {
      environmentId,
      environmentHash: inspected.snapshot.environmentHash
    });
    if (!linked?.ok) return { ok: false, status: 409, error: linked?.error ?? { code: "ENVIRONMENT_NOT_BOUND" } };
    return { ok: true, status: result.changed ? 201 : 200, data: publicEnvironment(inspected) };
  }
  async get(namespaceId, workflowId) {
    if (!UUID2.test(namespaceId ?? "") || !SAFE3.test(workflowId ?? ""))
      return { ok: false, status: 400, error: { code: "INVALID_LOOKUP" } };
    const workflow = await this.workflowStore?.read(namespaceId, workflowId);
    const ref2 = workflow?.instance?.environmentRef;
    if (!ref2) return { ok: false, status: 404, error: { code: "ENVIRONMENT_NOT_FOUND" } };
    const snapshot = await this.store.read(namespaceId, ref2.environmentId);
    if (!snapshot || snapshot.environmentHash !== ref2.environmentHash)
      return { ok: false, status: 409, error: { code: "ENVIRONMENT_BINDING_UNCERTAIN" } };
    const result = await this.service.inspect(namespaceId, ref2.environmentId);
    if (!result.ok) return { ok: false, status: 409, error: { code: result.error.code } };
    return { ok: true, status: 200, data: publicEnvironment(result) };
  }
  async reconcile(namespaceId, workflowId) {
    return this.get(namespaceId, workflowId);
  }
  async release(namespaceId, workflowId, state) {
    if (!["completed", "abandoned"].includes(state))
      return { ok: false, status: 400, error: { code: "INVALID_RELEASE_STATE" } };
    const found = await this.get(namespaceId, workflowId);
    if (!found.ok) return found;
    const environmentId = found.data.environment.environmentId;
    const transitioned = await this.service.setState(
      namespaceId,
      environmentId,
      state
    );
    if (!transitioned.ok) return { ok: false, status: 409, error: transitioned.error };
    return {
      ok: true,
      status: 200,
      data: publicEnvironment({ snapshot: transitioned.snapshot, reconciliation: found.data.reconciliation })
    };
  }
};
async function handleWorkUnitEnvironmentRequest({
  method,
  path,
  url,
  readBody,
  send,
  controller,
  identity,
  log = console
}) {
  const provision = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/provision$/);
  const reconcile = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/reconcile$/);
  const release2 = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/release$/);
  const detail = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment$/);
  if (!provision && !reconcile && !release2 && !detail) return false;
  try {
    if (provision && method === "POST") {
      const trust2 = await identity();
      if (!trust2) {
        error(send, 401, "TRUST_CONTEXT_UNAVAILABLE", "Trusted AgentOS execution context is required.");
        return true;
      }
      const result = await controller.provision({
        namespaceId: trust2.namespaceId,
        caseId: trust2.caseId,
        createdBy: trust2.actorId,
        body: await readBody()
      });
      if (result.ok) send(result.status, { data: result.data });
      else error(send, result.status, result.error.code, "Environment provisioning was rejected.");
      return true;
    }
    const trust = await identity();
    if (!trust) {
      error(send, 401, "TRUST_CONTEXT_UNAVAILABLE", "Trusted AgentOS execution context is required.");
      return true;
    }
    const namespaceId = trust.namespaceId;
    const workflowId = decodeURIComponent((reconcile ?? release2 ?? detail)[1]);
    if (reconcile && method === "POST") {
      const result = await controller.reconcile(namespaceId, workflowId);
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, "ENVIRONMENT_NOT_BOUND", "Environment is not bound to the controlling case.");
        return true;
      }
      if (result.ok) send(200, { data: result.data });
      else error(send, result.status, result.error.code, "Environment reconciliation failed closed.");
      return true;
    }
    if (release2 && method === "POST") {
      const current = await controller.get(namespaceId, workflowId);
      if (current.ok && current.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, "ENVIRONMENT_NOT_BOUND", "Environment is not bound to the controlling case.");
        return true;
      }
      const body = current.ok ? await readBody() : null;
      const result = current.ok ? await controller.release(namespaceId, workflowId, body.state) : current;
      if (result.ok) send(200, { data: result.data });
      else error(send, result.status, result.error.code, "Environment release was rejected.");
      return true;
    }
    if (detail && method === "GET") {
      const result = await controller.get(namespaceId, workflowId);
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, "ENVIRONMENT_NOT_BOUND", "Environment is not bound to the controlling case.");
        return true;
      }
      if (result.ok) send(200, { data: result.data });
      else error(send, result.status, result.error.code, "Environment is unavailable.");
      return true;
    }
    error(send, 405, "METHOD_NOT_ALLOWED", "Method not allowed.");
    return true;
  } catch (cause) {
    log.error("Work unit environment failure", { code: cause?.code ?? "UNEXPECTED" });
    error(send, 500, "ENVIRONMENT_STORAGE_FAILURE", "Environment control-plane is unavailable.");
    return true;
  }
}

// ../src/domain/delivery/delivery-definition.ts
import { createHash as createHash11 } from "node:crypto";
var DELIVERY_DEFINITION_SCHEMA_VERSION = "1";
var DELIVERY_STAGES = Object.freeze([
  "implementation-ready",
  "artifact-ready",
  "release-approved",
  "deployed",
  "production-verified"
]);
var DELIVERY_EVIDENCE_KINDS = Object.freeze([
  "implementation-result",
  "artifact",
  "oracle-result",
  "human-decision",
  "deployment-result",
  "smoke-result",
  "rollback-result"
]);
var SAFE4 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SEMVER2 = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
var TOP = /* @__PURE__ */ new Set([
  "schemaVersion",
  "deliveryType",
  "version",
  "title",
  "checkpoints",
  "artifactPolicy",
  "promotionPolicy",
  "deploymentPolicy",
  "retentionPolicy"
]);
var CHECKPOINT = /* @__PURE__ */ new Set(["stage", "responsibility", "requiredEvidence"]);
var RESPONSIBILITY = /* @__PURE__ */ new Set(["kind", "name"]);
var EVIDENCE = /* @__PURE__ */ new Set(["kind", "outcome", "oracleId"]);
var fail2 = (path, reason = "invalid_value") => ({
  ok: false,
  error: { code: "INVALID_DELIVERY_DEFINITION", path, reason }
});
var canonical = (value) => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((key) => [key, canonical(value[key])])
) : value;
function validateDeliveryDefinition(input) {
  if (!input || typeof input !== "object" || Array.isArray(input) || Object.keys(input).some((key) => !TOP.has(key)))
    return fail2("$");
  const candidate = input;
  if (candidate.schemaVersion !== DELIVERY_DEFINITION_SCHEMA_VERSION) return fail2("schemaVersion");
  if (!SAFE4.test(candidate.deliveryType ?? "") || !SEMVER2.test(candidate.version ?? "") || typeof candidate.title !== "string" || !candidate.title.trim() || candidate.title.length > 256)
    return fail2("$");
  if (!Array.isArray(candidate.checkpoints) || candidate.checkpoints.length !== DELIVERY_STAGES.length)
    return fail2("checkpoints");
  const checkpoints = [];
  for (let index = 0; index < candidate.checkpoints.length; index++) {
    const raw = candidate.checkpoints[index];
    if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((key) => !CHECKPOINT.has(key)) || raw.stage !== DELIVERY_STAGES[index])
      return fail2(`checkpoints[${index}]`);
    const responsibility = raw.responsibility;
    if (!responsibility || Object.keys(responsibility).some((key) => !RESPONSIBILITY.has(key)) || !["code", "human"].includes(responsibility.kind) || !SAFE4.test(responsibility.name ?? ""))
      return fail2(`checkpoints[${index}].responsibility`);
    if (raw.stage === "release-approved" && responsibility.kind !== "human")
      return fail2(`checkpoints[${index}].responsibility`, "release_requires_human");
    if (raw.stage !== "release-approved" && responsibility.kind !== "code")
      return fail2(`checkpoints[${index}].responsibility`, "factory_code_required");
    if (!Array.isArray(raw.requiredEvidence) || raw.requiredEvidence.length === 0 || raw.requiredEvidence.length > 16)
      return fail2(`checkpoints[${index}].requiredEvidence`);
    const requiredEvidence = [];
    for (let evidenceIndex = 0; evidenceIndex < raw.requiredEvidence.length; evidenceIndex++) {
      const item = raw.requiredEvidence[evidenceIndex];
      if (!item || typeof item !== "object" || Array.isArray(item) || Object.keys(item).some((key) => !EVIDENCE.has(key)) || !DELIVERY_EVIDENCE_KINDS.includes(item.kind) || !["pass", "fail", "indeterminate", "approved", "rejected"].includes(item.outcome))
        return fail2(`checkpoints[${index}].requiredEvidence[${evidenceIndex}]`);
      if (item.oracleId !== void 0 && !SAFE4.test(item.oracleId))
        return fail2(`checkpoints[${index}].requiredEvidence[${evidenceIndex}].oracleId`);
      requiredEvidence.push({
        kind: item.kind,
        outcome: item.outcome,
        ...item.oracleId ? { oracleId: item.oracleId } : {}
      });
    }
    checkpoints.push({
      stage: raw.stage,
      responsibility: { ...responsibility },
      requiredEvidence
    });
  }
  for (const [field, maximum] of [
    ["artifactPolicy", 32],
    ["promotionPolicy", 32],
    ["deploymentPolicy", 32],
    ["retentionPolicy", 16]
  ]) {
    const value = candidate[field];
    if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).length === 0 || Object.keys(value).length > maximum)
      return fail2(field);
  }
  return { ok: true, definition: canonical({ ...candidate, checkpoints }) };
}
function hashDeliveryDefinition(definition) {
  return createHash11("sha256").update(JSON.stringify(canonical(definition))).digest("hex");
}
function defaultDeliveryDefinition() {
  return {
    schemaVersion: "1",
    deliveryType: "factory-delivery",
    version: "1.0.0",
    title: "Governed Factory delivery",
    checkpoints: [
      {
        stage: "implementation-ready",
        responsibility: { kind: "code", name: "implementation-policy" },
        requiredEvidence: [{ kind: "implementation-result", outcome: "pass" }]
      },
      {
        stage: "artifact-ready",
        responsibility: { kind: "code", name: "artifact-oracle" },
        requiredEvidence: [
          { kind: "artifact", outcome: "pass" },
          { kind: "oracle-result", outcome: "pass" }
        ]
      },
      {
        stage: "release-approved",
        responsibility: { kind: "human", name: "release-approver" },
        requiredEvidence: [{ kind: "human-decision", outcome: "approved" }]
      },
      {
        stage: "deployed",
        responsibility: { kind: "code", name: "deployment-control-plane" },
        requiredEvidence: [{ kind: "deployment-result", outcome: "pass" }]
      },
      {
        stage: "production-verified",
        responsibility: { kind: "code", name: "production-smoke" },
        requiredEvidence: [{ kind: "smoke-result", outcome: "pass" }]
      }
    ],
    artifactPolicy: { requireBuildAndTests: true, extensibleChecks: "sast sca secrets sbom signature provenance" },
    promotionPolicy: { ordered: true, automaticMerge: false, requireFactoryEvidence: true },
    deploymentPolicy: { environmentsFromTrustedConfiguration: true, requireRollbackCapability: true },
    retentionPolicy: { deleteWorktreeBeforeProductionVerified: false }
  };
}

// ../src/domain/delivery/delivery-policy.ts
import { createHash as createHash12, randomUUID as randomUUID6 } from "node:crypto";
var DELIVERY_INITIAL_STAGE = "implementation-ready";
var SAFE5 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var FIELDS3 = /* @__PURE__ */ new Set(["deliveryId", "expectedRevision", "requestedStage", "evidenceIds", "idempotencyKey"]);
var deny2 = (code, reason) => ({ allowed: false, code, reason });
var hash = (value) => createHash12("sha256").update(JSON.stringify(value)).digest("hex");
function validateDeliveryPromotionRequest(input, expectedDeliveryId) {
  if (!input || typeof input !== "object" || Array.isArray(input) || Object.keys(input).some((key) => !FIELDS3.has(key)))
    return { ok: false, error: { code: "INVALID_DELIVERY_REQUEST" } };
  const candidate = input;
  if (candidate.deliveryId !== expectedDeliveryId || !SAFE5.test(candidate.deliveryId ?? "") || !Number.isSafeInteger(candidate.expectedRevision) || candidate.expectedRevision < 1 || !DELIVERY_STAGES.includes(candidate.requestedStage))
    return { ok: false, error: { code: "INVALID_DELIVERY_REQUEST" } };
  if (!Array.isArray(candidate.evidenceIds) || candidate.evidenceIds.length > 100 || new Set(candidate.evidenceIds).size !== candidate.evidenceIds.length || candidate.evidenceIds.some((id2) => !SAFE5.test(id2 ?? "")))
    return { ok: false, error: { code: "INVALID_DELIVERY_REQUEST" } };
  if (typeof candidate.idempotencyKey !== "string" || !candidate.idempotencyKey || candidate.idempotencyKey.length > 128 || /[\r\n]/.test(candidate.idempotencyKey))
    return { ok: false, error: { code: "INVALID_DELIVERY_REQUEST" } };
  return {
    ok: true,
    value: {
      requestId: randomUUID6(),
      deliveryId: candidate.deliveryId,
      expectedRevision: candidate.expectedRevision,
      requestedStage: candidate.requestedStage,
      evidenceIds: [...candidate.evidenceIds],
      idempotencyKey: candidate.idempotencyKey
    }
  };
}
var deliveryScopeHash = (namespaceId, request, execution2) => hash({
  namespaceId,
  deliveryId: request.deliveryId,
  caseId: execution2.caseId,
  runtimeId: execution2.runtimeId,
  idempotencyKey: request.idempotencyKey
});
var deliverySemanticHash = (request) => hash({
  deliveryId: request.deliveryId,
  expectedRevision: request.expectedRevision,
  requestedStage: request.requestedStage,
  evidenceIds: [...request.evidenceIds].sort()
});
function evaluateDeliveryPromotion({
  request,
  snapshot,
  definition,
  evidence,
  execution: execution2
}) {
  if (!snapshot) return deny2("DELIVERY_NOT_FOUND", "delivery_not_found");
  if (snapshot.revision !== request.expectedRevision) return deny2("REVISION_CONFLICT", "stale_delivery_revision");
  if (snapshot.namespaceId !== execution2.namespaceId || snapshot.workflowId !== execution2.workflowId || snapshot.parentCaseId !== execution2.caseId)
    return deny2("DELIVERY_SCOPE_MISMATCH", "controlling_execution_mismatch");
  if (snapshot.definitionHash !== definition.definitionHash)
    return deny2("DELIVERY_DEFINITION_MISMATCH", "definition_identity_mismatch");
  const currentIndex = DELIVERY_STAGES.indexOf(snapshot.stage), requestedIndex = DELIVERY_STAGES.indexOf(request.requestedStage);
  if (requestedIndex !== currentIndex + 1) return deny2("ILLEGAL_PROMOTION", "ordered_promotion_required");
  const checkpoint = definition.checkpoints.find((item) => item.stage === request.requestedStage);
  if (!checkpoint) return deny2("DELIVERY_DEFINITION_MISMATCH", "checkpoint_missing");
  const isHuman = checkpoint.responsibility.kind === "human";
  if (isHuman ? !(execution2.kind === "factory-human" && execution2.actorId && execution2.runtimeId === "factory-dashboard") : !(execution2.kind === "factory-control-plane" && execution2.runtimeId === "factory-dashboard"))
    return deny2("ACTOR_NOT_AUTHORIZED", "factory_responsibility_required");
  const selected = [];
  for (const id2 of request.evidenceIds) {
    const item = evidence.find((candidate) => candidate.evidenceId === id2);
    if (!item) return deny2("EVIDENCE_NOT_FOUND", "evidence_not_found");
    if (item.namespaceId !== snapshot.namespaceId || item.workflowId !== snapshot.workflowId || item.deliveryId !== snapshot.deliveryId || item.environmentHash !== snapshot.environmentHash || item.caseId !== snapshot.parentCaseId || item.headCommit !== snapshot.headCommit)
      return deny2("EVIDENCE_SCOPE_MISMATCH", "bounded_fact_mismatch");
    selected.push(item);
  }
  for (const requirement of checkpoint.requiredEvidence) {
    const match2 = selected.find(
      (item) => item.kind === requirement.kind && item.outcome === requirement.outcome && (!requirement.oracleId || item.oracleId === requirement.oracleId) && item.source?.kind !== "agent"
    );
    if (!match2) return deny2("PASS_EVIDENCE_REQUIRED", `${requirement.kind}:${requirement.outcome}`);
  }
  if (request.requestedStage === "release-approved" && !selected.some(
    (item) => item.kind === "human-decision" && item.outcome === "approved" && item.source?.kind === "factory-human"
  ))
    return deny2("HUMAN_APPROVAL_REQUIRED", "release_approval_missing");
  if (request.requestedStage === "deployed" && currentIndex < DELIVERY_STAGES.indexOf("release-approved"))
    return deny2("RELEASE_NOT_APPROVED", "release_approval_missing");
  if (request.requestedStage === "production-verified" && !selected.some((item) => item.kind === "smoke-result" && item.outcome === "pass"))
    return deny2("SMOKE_PASS_REQUIRED", "production_smoke_missing");
  return { allowed: true };
}
function applyDeliveryPromotion(snapshot, request, observedAt = (/* @__PURE__ */ new Date()).toISOString()) {
  return {
    ...snapshot,
    stage: request.requestedStage,
    revision: snapshot.revision + 1,
    updatedAt: observedAt,
    evidenceIds: [.../* @__PURE__ */ new Set([...snapshot.evidenceIds ?? [], ...request.evidenceIds])]
  };
}

// ../src/domain/delivery/delivery-operation-definition.ts
import { createHash as createHash13 } from "node:crypto";
var DELIVERY_OPERATION_KINDS = Object.freeze([
  "deployment",
  "production-verification",
  "rollback",
  "rollback-verification"
]);
var DELIVERY_OPERATION_STATES = Object.freeze(["pending", "running", "succeeded", "failed", "indeterminate"]);
var DELIVERY_OPERATION_ERROR_CODES = Object.freeze({
  INVALID_REQUEST: "INVALID_DELIVERY_OPERATION_REQUEST",
  INVALID_RECORD: "INVALID_DELIVERY_OPERATION_RECORD",
  INVALID_TRANSITION: "INVALID_DELIVERY_OPERATION_TRANSITION",
  RECONCILIATION_REQUIRED: "DELIVERY_OPERATION_RECONCILIATION_REQUIRED"
});
var SAFE6 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SHA2 = /^[0-9a-f]{40}$/i;
var DIGEST = /^sha256:[0-9a-f]{64}$/i;
var MEDIA = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$/i;
var canonical2 = (value) => Array.isArray(value) ? value.map(canonical2) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((k) => [k, canonical2(value[k])])
) : value;
var canonicalDeliveryHash = (value) => `sha256:${createHash13("sha256").update(JSON.stringify(canonical2(value))).digest("hex")}`;
var fail3 = (path, reason = "invalid_value") => ({
  ok: false,
  error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_REQUEST, path, reason }
});
var exact = (v, fields) => v !== null && typeof v === "object" && !Array.isArray(v) && Object.keys(v).every((k) => fields.includes(k));
var id = (v) => typeof v === "string" && SAFE6.test(v);
var digest2 = (v) => typeof v === "string" && DIGEST.test(v);
var sha = (v) => typeof v === "string" && SHA2.test(v);
function artifact(v, path = "artifactRef") {
  if (!exact(v, ["digest", "mediaType", "producerRef", "buildRef", "sourceCommit"]) || !digest2(v.digest) || !MEDIA.test(v.mediaType ?? "") || !id(v.producerRef) || !id(v.buildRef) || !sha(v.sourceCommit))
    return fail3(path);
  return {
    ok: true,
    value: Object.freeze({
      ...v,
      digest: v.digest.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase()
    })
  };
}
function release(v, path = "releaseRef") {
  if (!exact(v, ["releaseId", "artifactDigest", "sourceCommit", "approvedEvidenceId"]) || !id(v.releaseId) || !digest2(v.artifactDigest) || !sha(v.sourceCommit) || !id(v.approvedEvidenceId))
    return fail3(path);
  return {
    ok: true,
    value: Object.freeze({
      ...v,
      artifactDigest: v.artifactDigest.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase()
    })
  };
}
function operationRef(v, path, kind) {
  if (!exact(v, ["operationId", "kind", "state", "targetHash", "sourceCommit", "artifactDigest"]) || !id(v.operationId) || v.kind !== kind || v.state !== "succeeded" || !digest2(v.targetHash) || !sha(v.sourceCommit) || !digest2(v.artifactDigest))
    return fail3(path);
  return {
    ok: true,
    value: Object.freeze({
      ...v,
      targetHash: v.targetHash.toLowerCase(),
      sourceCommit: v.sourceCommit.toLowerCase(),
      artifactDigest: v.artifactDigest.toLowerCase()
    })
  };
}
var BASE = ["kind", "expectedRevision", "idempotencyKey", "targetId"];
var SPEC = {
  deployment: ["artifactRef", "releaseRef"],
  "production-verification": ["deploymentRef"],
  rollback: ["deploymentRef", "priorArtifactRef", "priorReleaseRef", "rollbackRequestId", "approvedEvidenceId"],
  "rollback-verification": ["rollbackRef"]
};
function normalizeDeliveryOperationRequest(input) {
  const candidate = input;
  if (!candidate || !DELIVERY_OPERATION_KINDS.includes(candidate.kind) || !exact(candidate, [...BASE, ...SPEC[candidate.kind]]))
    return fail3("$", "unknown_or_missing_field");
  if (!Number.isSafeInteger(candidate.expectedRevision) || candidate.expectedRevision < 1 || !id(candidate.idempotencyKey) || !id(candidate.targetId))
    return fail3("$");
  const out = {
    kind: candidate.kind,
    expectedRevision: candidate.expectedRevision,
    idempotencyKey: candidate.idempotencyKey,
    targetId: candidate.targetId
  };
  if (candidate.kind === "deployment") {
    const a = artifact(candidate.artifactRef), r = release(candidate.releaseRef);
    if (!a.ok) return a;
    if (!r.ok) return r;
    if (a.value.digest !== r.value.artifactDigest || a.value.sourceCommit !== r.value.sourceCommit)
      return fail3("releaseRef", "artifact_identity_mismatch");
    Object.assign(out, { artifactRef: a.value, releaseRef: r.value });
  }
  if (candidate.kind === "production-verification") {
    const d = operationRef(candidate.deploymentRef, "deploymentRef", "deployment");
    if (!d.ok) return d;
    out.deploymentRef = d.value;
  }
  if (candidate.kind === "rollback") {
    const d = operationRef(candidate.deploymentRef, "deploymentRef", "deployment"), a = artifact(candidate.priorArtifactRef, "priorArtifactRef"), r = release(candidate.priorReleaseRef, "priorReleaseRef");
    if (!d.ok) return d;
    if (!a.ok) return a;
    if (!r.ok) return r;
    if (!id(candidate.rollbackRequestId) || !id(candidate.approvedEvidenceId) || a.value.digest !== r.value.artifactDigest || a.value.sourceCommit !== r.value.sourceCommit)
      return fail3("$", "rollback_identity_mismatch");
    Object.assign(out, {
      deploymentRef: d.value,
      priorArtifactRef: a.value,
      priorReleaseRef: r.value,
      rollbackRequestId: candidate.rollbackRequestId,
      approvedEvidenceId: candidate.approvedEvidenceId
    });
  }
  if (candidate.kind === "rollback-verification") {
    const r = operationRef(candidate.rollbackRef, "rollbackRef", "rollback");
    if (!r.ok) return r;
    out.rollbackRef = r.value;
  }
  return { ok: true, value: Object.freeze(out) };
}
function deriveDeliveryOperationIdentity({ namespaceId, workflowId, deliveryId, caseId, runtimeId }, request, targetHash) {
  for (const v of [namespaceId, workflowId, deliveryId, caseId, runtimeId]) if (!id(v)) return fail3("scope");
  if (!digest2(targetHash)) return fail3("targetHash");
  const scopeHash = canonicalDeliveryHash({
    namespaceId,
    workflowId,
    deliveryId,
    caseId,
    runtimeId,
    idempotencyKey: request.idempotencyKey
  });
  const semanticHash = canonicalDeliveryHash({
    kind: request.kind,
    expectedRevision: request.expectedRevision,
    targetHash,
    ...Object.fromEntries(
      Object.entries(request).filter(([k]) => /Ref$/.test(k) || ["rollbackRequestId", "approvedEvidenceId"].includes(k))
    )
  });
  return { ok: true, value: { operationId: `dop_${scopeHash.slice(7, 39)}`, scopeHash, semanticHash } };
}
var ALLOWED2 = {
  pending: ["running", "failed"],
  running: ["succeeded", "failed", "indeterminate"],
  indeterminate: ["succeeded", "failed"],
  succeeded: [],
  failed: []
};
function validateDeliveryOperationTransition(previous, next, { inspectedObservation } = {}) {
  if (!previous || !next || previous.operationId !== next.operationId || !DELIVERY_OPERATION_STATES.includes(previous.state) || !ALLOWED2[previous.state]?.includes(next.state))
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_TRANSITION } };
  if (previous.state === "indeterminate" && (!inspectedObservation || inspectedObservation.operationId !== previous.operationId || inspectedObservation.state !== next.state || !["succeeded", "failed"].includes(next.state) || next.resolvedOperationId !== previous.operationId))
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.RECONCILIATION_REQUIRED } };
  return { ok: true };
}
function validateDeliveryOperationRecord(v) {
  const fields = [
    "recordType",
    "operationId",
    "kind",
    "expectedRevision",
    "targetRef",
    "artifactRef",
    "releaseRef",
    "deploymentRef",
    "rollbackRef",
    "state",
    "attempt",
    "requestedAt",
    "startedAt",
    "completedAt",
    "execution",
    "adapterCorrelation",
    "scopeHash",
    "semanticHash",
    "result",
    "error",
    "resolvedOperationId",
    "sourceCommit",
    "artifactDigest",
    "rollbackRequestId",
    "approvedEvidenceId"
  ];
  if (!exact(v, fields) || v.recordType !== "delivery-operation" || !id(v.operationId) || !DELIVERY_OPERATION_KINDS.includes(v.kind) || !DELIVERY_OPERATION_STATES.includes(v.state) || !Number.isSafeInteger(v.expectedRevision) || !Number.isSafeInteger(v.attempt) || v.attempt < 0 || !digest2(v.scopeHash) || !digest2(v.semanticHash))
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD } };
  for (const key of ["requestedAt", "startedAt", "completedAt"])
    if (v[key] !== void 0 && (!Number.isFinite(Date.parse(v[key])) || new Date(Date.parse(v[key])).toISOString() !== v[key]))
      return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD, path: key } };
  return { ok: true, value: Object.freeze({ ...v }) };
}

// ../src/domain/delivery/delivery-operation-policy.ts
var deny3 = (code, reason) => ({ allowed: false, code, reason });
var pass = () => ({ allowed: true });
function evaluateDeliveryOperationPolicy({
  request,
  snapshot,
  target,
  identity,
  existingOperations = []
}) {
  if (!snapshot) return deny3("DELIVERY_NOT_FOUND", "delivery_not_found");
  if (snapshot.revision !== request.expectedRevision) return deny3("REVISION_CONFLICT", "stale_delivery_revision");
  if (!target) return deny3("DELIVERY_TARGET_NOT_FOUND", "trusted_target_missing");
  if (identity?.targetHash !== target.targetHash)
    return deny3("DELIVERY_TARGET_HASH_MISMATCH", "target_binding_mismatch");
  if (existingOperations.some((o) => o.state === "indeterminate" && !o.resolvedOperationId))
    return deny3("DELIVERY_OPERATION_INDETERMINATE", "reconciliation_required");
  const head = snapshot.headCommit;
  if (request.artifactRef && request.artifactRef.sourceCommit !== head)
    return deny3("SOURCE_COMMIT_MISMATCH", "artifact_not_at_head");
  if (request.releaseRef && request.releaseRef.sourceCommit !== head)
    return deny3("SOURCE_COMMIT_MISMATCH", "release_not_at_head");
  if (request.kind === "deployment" && snapshot.stage !== "release-approved")
    return deny3("RELEASE_NOT_APPROVED", "release_approved_stage_required");
  if (request.kind === "production-verification") {
    if (snapshot.stage !== "deployed" || request.deploymentRef?.state !== "succeeded")
      return deny3("SUCCESSFUL_DEPLOYMENT_REQUIRED", "linked_deployment_required");
    if (request.deploymentRef?.targetHash !== target.targetHash || request.deploymentRef?.sourceCommit !== head)
      return deny3("DEPLOYMENT_SCOPE_MISMATCH", "deployment_binding_mismatch");
    if (!target.verificationSuiteId || !target.verificationSuiteHash)
      return deny3("VERIFICATION_SUITE_NOT_CONFIGURED", "trusted_suite_required");
  }
  if (request.kind === "rollback") {
    if (request.deploymentRef?.state !== "succeeded")
      return deny3("SUCCESSFUL_DEPLOYMENT_REQUIRED", "linked_deployment_required");
    if (!target.supportsRollback) return deny3("ROLLBACK_NOT_SUPPORTED", "target_disallows_rollback");
    if (!request.approvedEvidenceId) return deny3("ROLLBACK_APPROVAL_REQUIRED", "approval_evidence_required");
    if (request.priorArtifactRef?.digest === request.deploymentRef?.artifactDigest)
      return deny3("ROLLBACK_RELEASE_UNCHANGED", "prior_release_must_differ");
    if (request.deploymentRef?.targetHash !== target.targetHash || request.deploymentRef?.sourceCommit !== head)
      return deny3("DEPLOYMENT_SCOPE_MISMATCH", "deployment_binding_mismatch");
  }
  if (request.kind === "rollback-verification") {
    if (request.rollbackRef?.state !== "succeeded")
      return deny3("SUCCESSFUL_ROLLBACK_REQUIRED", "linked_rollback_required");
    if (request.rollbackRef?.targetHash !== target.targetHash)
      return deny3("ROLLBACK_SCOPE_MISMATCH", "rollback_binding_mismatch");
    if (!target.verificationSuiteId || !target.verificationSuiteHash)
      return deny3("VERIFICATION_SUITE_NOT_CONFIGURED", "trusted_suite_required");
  }
  return pass();
}
function resolveDeliveryVerificationRequest(request, target) {
  if (!target?.verificationSuiteId || !target?.verificationSuiteHash)
    return { ok: false, error: { code: "VERIFICATION_SUITE_NOT_CONFIGURED" } };
  return {
    ok: true,
    value: Object.freeze({
      ...request,
      verificationSuiteRef: Object.freeze({
        suiteId: target.verificationSuiteId,
        suiteHash: target.verificationSuiteHash
      })
    })
  };
}

// ../src/adapters/persistence/delivery-store.ts
import { createHash as createHash14, randomBytes as randomBytes5 } from "node:crypto";
import { appendFile as appendFile3, mkdir as mkdir4, open as open4, readFile as readFile5, rename as rename4, rm as rm4 } from "node:fs/promises";
import { dirname as dirname5, isAbsolute as isAbsolute5, join as join10 } from "node:path";
var UUID3 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE7 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SHA3 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var HASH2 = /^sha256:[0-9a-f]{64}$/;
var canonical3 = (value) => Array.isArray(value) ? value.map(canonical3) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).filter((key) => value[key] !== void 0).sort().map((key) => [key, canonical3(value[key])])
) : value;
var hash2 = (value) => createHash14("sha256").update(JSON.stringify(canonical3(value))).digest("hex");
async function sync(path) {
  const handle = await open4(path, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}
async function atomic2(path, value) {
  await mkdir4(dirname5(path), { recursive: true });
  const temp = `${path}.tmp-${process.pid}-${randomBytes5(6).toString("hex")}`;
  const handle = await open4(temp, "wx", 384);
  try {
    await handle.writeFile(`${JSON.stringify(value)}
`);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename4(temp, path);
  await sync(dirname5(path));
}
async function append2(path, value) {
  await mkdir4(dirname5(path), { recursive: true });
  await appendFile3(path, `${JSON.stringify(value)}
`, { mode: 384 });
  await sync(path);
}
var validSnapshot = (value) => value !== null && typeof value === "object" && value.schemaVersion === "1" && UUID3.test(value.namespaceId ?? "") && SAFE7.test(value.deliveryId ?? "") && SAFE7.test(value.workflowId ?? "") && UUID3.test(value.environmentId ?? "") && HASH2.test(value.environmentHash ?? "") && UUID3.test(value.parentCaseId ?? "") && SAFE7.test(value.runtimeId ?? "") && SHA3.test(value.baseCommit ?? "") && SHA3.test(value.headCommit ?? "") && Number.isSafeInteger(value.revision) && value.revision > 0;
var DeliveryStore = class {
  dataRoot;
  fault;
  locks;
  constructor(dataRoot, { fault = async () => {
  } } = {}) {
    if (!isAbsolute5(dataRoot)) throw new Error("INVALID_DATA_ROOT");
    this.dataRoot = dataRoot;
    this.fault = fault;
    this.locks = /* @__PURE__ */ new Map();
  }
  async initialize() {
    await mkdir4(join10(this.dataRoot, "deliveries"), { recursive: true });
  }
  paths(namespaceId, deliveryId) {
    if (!UUID3.test(namespaceId ?? "") || !SAFE7.test(deliveryId ?? "")) throw new Error("INVALID_DELIVERY_SCOPE");
    const directory = join10(
      this.dataRoot,
      "deliveries",
      namespaceId,
      createHash14("sha256").update(`${namespaceId}:${deliveryId}`).digest("hex")
    );
    return {
      directory,
      snapshot: join10(directory, "delivery.json"),
      journal: join10(directory, "operations.jsonl"),
      pending: join10(directory, "pending.json")
    };
  }
  _locked(namespaceId, deliveryId, action) {
    const key = `${namespaceId}\0${deliveryId}`, prior = this.locks.get(key) ?? Promise.resolve(), operation = prior.then(action), tail = operation.catch(() => {
    });
    this.locks.set(key, tail);
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key);
    });
  }
  async _json(path) {
    try {
      return JSON.parse(await readFile5(path, "utf8"));
    } catch (error2) {
      if (error2?.code === "ENOENT") return null;
      throw Object.assign(new Error("CORRUPT_DELIVERY_STORAGE"), { code: "CORRUPT_DELIVERY_STORAGE" });
    }
  }
  async journal(namespaceId, deliveryId) {
    try {
      return (await readFile5(this.paths(namespaceId, deliveryId).journal, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    } catch (error2) {
      if (error2?.code === "ENOENT") return [];
      throw Object.assign(new Error("CORRUPT_DELIVERY_JOURNAL"), { code: "CORRUPT_DELIVERY_JOURNAL" });
    }
  }
  async _recover(paths) {
    const pending = await this._json(paths.pending);
    if (!pending) return;
    const pendingSnapshot = pending.snapshot, pendingOperation = pending.operation;
    const records = await this.journal(pendingSnapshot.namespaceId, pendingSnapshot.deliveryId);
    const matching = records.filter((item) => item.operationId === pendingOperation.operationId);
    if (!matching.length)
      throw Object.assign(new Error("DELIVERY_OPERATION_INDETERMINATE"), { code: "DELIVERY_OPERATION_INDETERMINATE" });
    const last = matching[matching.length - 1];
    if (last.state === "succeeded" && pending.snapshotHash === hash2(pendingSnapshot)) {
      await atomic2(paths.snapshot, pendingSnapshot);
      await rm4(paths.pending, { force: true });
      return;
    }
    throw Object.assign(new Error("DELIVERY_OPERATION_INDETERMINATE"), { code: "DELIVERY_OPERATION_INDETERMINATE" });
  }
  async read(namespaceId, deliveryId) {
    const paths = this.paths(namespaceId, deliveryId);
    await this._recover(paths);
    const snapshot = await this._json(paths.snapshot);
    if (!snapshot) return null;
    if (!validSnapshot(snapshot) || snapshot.snapshotHash !== hash2({ ...snapshot, snapshotHash: void 0 }))
      throw Object.assign(new Error("CORRUPT_DELIVERY_STORAGE"), { code: "CORRUPT_DELIVERY_STORAGE" });
    return snapshot;
  }
  async create(input) {
    return this._locked(input.namespaceId, input.deliveryId, async () => {
      const current = await this.read(input.namespaceId, input.deliveryId);
      if (current)
        return JSON.stringify({ ...current, snapshotHash: void 0 }) === JSON.stringify(input) ? { ok: true, changed: false, snapshot: current } : { ok: false, error: { code: "DELIVERY_IDENTITY_CONFLICT" } };
      if (!validSnapshot(input)) return { ok: false, error: { code: "INVALID_DELIVERY_SNAPSHOT" } };
      return this._write(null, input, { kind: "delivery_created", idempotencyKey: `create:${input.deliveryId}` });
    });
  }
  async promote({
    namespaceId,
    request,
    definition,
    evidence,
    execution: execution2
  }) {
    return this._locked(namespaceId, request.deliveryId, async () => {
      const current = await this.read(namespaceId, request.deliveryId);
      const records = await this.journal(namespaceId, request.deliveryId);
      const scopeHash = deliveryScopeHash(namespaceId, request, execution2), semanticHash = deliverySemanticHash(request), prior = records.find((item) => item.scopeHash === scopeHash && item.state === "succeeded");
      if (prior) {
        if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        return { ok: true, changed: false, idempotent: true, snapshot: current };
      }
      const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution: execution2 });
      if (!decision.allowed) return { ok: false, error: decision };
      const next = applyDeliveryPromotion(current, request);
      return this._write(current, next, {
        kind: "delivery_promoted",
        idempotencyKey: request.idempotencyKey,
        scopeHash,
        semanticHash,
        evidenceIds: request.evidenceIds
      });
    });
  }
  async _write(current, value, operationInput) {
    const paths = this.paths(value.namespaceId, value.deliveryId), operationId = createHash14("sha256").update(`${value.namespaceId}:${value.deliveryId}:${operationInput.idempotencyKey}`).digest("hex"), operation = {
      schemaVersion: "1",
      operationId,
      deliveryId: value.deliveryId,
      revision: (current?.revision ?? 0) + (current ? 1 : 0),
      kind: operationInput.kind,
      state: "pending",
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      ...operationInput.scopeHash ? { scopeHash: operationInput.scopeHash, semanticHash: operationInput.semanticHash } : {},
      ...operationInput.evidenceIds ? { evidenceIds: [...operationInput.evidenceIds].sort() } : {}
    };
    const clean = { ...value };
    delete clean.snapshotHash;
    const snapshot = { ...clean, snapshotHash: hash2(clean) };
    await atomic2(paths.pending, { operation, snapshot, snapshotHash: hash2(snapshot) });
    await append2(paths.journal, operation);
    await this.fault("after-pending-journal");
    const running = { ...operation, state: "running", timestamp: (/* @__PURE__ */ new Date()).toISOString() };
    await append2(paths.journal, running);
    await this.fault("after-running");
    const succeeded = {
      ...operation,
      state: "succeeded",
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      resultHash: snapshot.snapshotHash
    };
    await append2(paths.journal, succeeded);
    await this.fault("after-success");
    await atomic2(paths.snapshot, snapshot);
    await this.fault("after-snapshot");
    await rm4(paths.pending, { force: true });
    return { ok: true, changed: true, idempotent: false, snapshot };
  }
  async recordOperation(namespaceId, deliveryId, input) {
    return this._locked(namespaceId, deliveryId, async () => {
      const records = await this.journal(namespaceId, deliveryId), operationId = createHash14("sha256").update(`${namespaceId}:${deliveryId}:${input.idempotencyKey}`).digest("hex"), prior = records.filter((item) => item.operationId === operationId).at(-1);
      const semanticHash = hash2(input.facts);
      if (prior) {
        if (prior.semanticHash !== semanticHash) return { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        return { ok: true, changed: false, operation: prior };
      }
      const operation = {
        schemaVersion: "1",
        operationId,
        deliveryId,
        kind: input.kind,
        state: input.state,
        semanticHash,
        facts: input.facts,
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      };
      await append2(this.paths(namespaceId, deliveryId).journal, operation);
      return { ok: true, changed: true, operation };
    });
  }
  _deliveryOperationProjection(records) {
    const history = records.filter((record2) => record2.recordType === "delivery-operation");
    const current = /* @__PURE__ */ new Map();
    const resolved = new Set(
      history.filter((record2) => record2.resolvedOperationId && ["succeeded", "failed"].includes(record2.state)).map((record2) => record2.resolvedOperationId)
    );
    for (const record2 of history) current.set(record2.operationId, record2);
    const rollbackHistory = records.filter((record2) => record2.recordType === "rollback-request");
    const rollbackCurrent = /* @__PURE__ */ new Map();
    for (const record2 of rollbackHistory) rollbackCurrent.set(record2.rollbackRequestId, record2);
    return {
      history,
      operations: [...current.values()],
      rollbackRequests: [...rollbackCurrent.values()],
      rollbackRequestHistory: rollbackHistory,
      unresolvedIndeterminate: [...current.values()].filter(
        (record2) => record2.state === "indeterminate" && !resolved.has(record2.operationId)
      )
    };
  }
  async readWithOperations(namespaceId, deliveryId) {
    const snapshot = await this.read(namespaceId, deliveryId);
    if (!snapshot) return null;
    const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId));
    return { ...snapshot, deliveryOperations: projection.operations, rollbackRequests: projection.rollbackRequests };
  }
  async inspectDeliveryOperations(namespaceId, deliveryId) {
    return this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId));
  }
  async createRollbackRequest({
    namespaceId,
    deliveryId,
    workflowId,
    caseId,
    runtimeId,
    request,
    execution: execution2
  }) {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId);
      if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
      if (snapshot.workflowId !== workflowId || snapshot.parentCaseId !== caseId || snapshot.runtimeId !== runtimeId)
        return { ok: false, error: { code: "DELIVERY_SCOPE_MISMATCH" } };
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), prior = projection.rollbackRequestHistory.find((record3) => record3.scopeHash === request.scopeHash);
      if (prior)
        return prior.semanticHash === request.semanticHash ? {
          ok: true,
          changed: false,
          idempotent: true,
          request: projection.rollbackRequests.find((item) => item.rollbackRequestId === prior.rollbackRequestId) ?? prior
        } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      if (snapshot.revision !== request.expectedRevision) return { ok: false, error: { code: "REVISION_CONFLICT" } };
      const record2 = {
        recordType: "rollback-request",
        schemaVersion: "1",
        rollbackRequestId: request.rollbackRequestId,
        deliveryId,
        workflowId,
        namespaceId,
        caseId,
        runtimeId,
        status: "requested",
        expectedRevision: request.expectedRevision,
        idempotencyKey: request.idempotencyKey,
        scopeHash: request.scopeHash,
        semanticHash: request.semanticHash,
        targetId: request.targetId,
        targetHash: request.targetHash,
        deploymentRef: canonical3(request.deploymentRef),
        priorArtifactRef: canonical3(request.priorArtifactRef),
        priorReleaseRef: canonical3(request.priorReleaseRef),
        reasonCode: request.reasonCode,
        ...request.reason ? { reason: request.reason } : {},
        requestedAt: (/* @__PURE__ */ new Date()).toISOString(),
        requestedBy: canonical3(execution2)
      };
      await append2(this.paths(namespaceId, deliveryId).journal, record2);
      return { ok: true, changed: true, idempotent: false, request: record2 };
    });
  }
  async approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval) {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId);
      if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId);
      if (!current) return { ok: false, error: { code: "ROLLBACK_REQUEST_NOT_FOUND" } };
      const scopeHash = `sha256:${hash2({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`, semanticHash = `sha256:${hash2({ rollbackRequestId, expectedRevision: approval.expectedRevision, actorId: approval.execution.actorId })}`, prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash);
      if (prior)
        return prior.approvalSemanticHash === semanticHash ? { ok: true, changed: false, idempotent: true, request: prior } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision)
        return { ok: false, error: { code: "REVISION_CONFLICT" } };
      if (current.status !== "requested") return { ok: false, error: { code: "ROLLBACK_REQUEST_ALREADY_DECIDED" } };
      const record2 = {
        ...current,
        status: "approved",
        approvedAt: (/* @__PURE__ */ new Date()).toISOString(),
        approvedBy: canonical3(approval.execution),
        approvalScopeHash: scopeHash,
        approvalSemanticHash: semanticHash,
        approvalIdempotencyKey: approval.idempotencyKey
      };
      await append2(this.paths(namespaceId, deliveryId).journal, record2);
      return { ok: true, changed: true, idempotent: false, request: record2 };
    });
  }
  async createDeliveryOperation({
    namespaceId,
    workflowId,
    deliveryId,
    caseId,
    runtimeId,
    request,
    targetRef,
    execution: execution2
  }) {
    return this._locked(namespaceId, deliveryId, async () => {
      const normalized = normalizeDeliveryOperationRequest(request);
      if (!normalized.ok) return normalized;
      const snapshot = await this.read(namespaceId, deliveryId);
      if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
      const targetHash = targetRef?.targetHash;
      const identity = deriveDeliveryOperationIdentity(
        { namespaceId, workflowId, deliveryId, caseId, runtimeId },
        normalized.value,
        targetHash
      );
      if (!identity.ok) return identity;
      const records = await this.journal(namespaceId, deliveryId), projection = this._deliveryOperationProjection(records), existing = projection.history.find((record2) => record2.scopeHash === identity.value.scopeHash);
      if (existing)
        return existing.semanticHash === identity.value.semanticHash ? {
          ok: true,
          changed: false,
          idempotent: true,
          operation: projection.operations.find((record2) => record2.operationId === existing.operationId) ?? existing
        } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      if (snapshot.revision !== normalized.value.expectedRevision)
        return { ok: false, error: { code: "REVISION_CONFLICT" } };
      if (projection.unresolvedIndeterminate.length)
        return { ok: false, error: { code: "DELIVERY_OPERATION_INDETERMINATE" } };
      const now = (/* @__PURE__ */ new Date()).toISOString(), source = normalized.value.artifactRef ?? normalized.value.priorArtifactRef, operation = {
        recordType: "delivery-operation",
        operationId: identity.value.operationId,
        kind: normalized.value.kind,
        expectedRevision: normalized.value.expectedRevision,
        targetRef: canonical3(targetRef),
        artifactRef: normalized.value.artifactRef ?? normalized.value.priorArtifactRef,
        releaseRef: normalized.value.releaseRef ?? normalized.value.priorReleaseRef,
        deploymentRef: normalized.value.deploymentRef,
        rollbackRef: normalized.value.rollbackRef,
        state: "pending",
        attempt: 0,
        requestedAt: now,
        startedAt: void 0,
        completedAt: void 0,
        execution: canonical3(execution2),
        adapterCorrelation: void 0,
        scopeHash: identity.value.scopeHash,
        semanticHash: identity.value.semanticHash,
        result: void 0,
        error: void 0,
        resolvedOperationId: void 0,
        sourceCommit: source?.sourceCommit,
        artifactDigest: source?.digest,
        rollbackRequestId: normalized.value.rollbackRequestId,
        approvedEvidenceId: normalized.value.approvedEvidenceId
      };
      const persisted = Object.fromEntries(Object.entries(operation).filter(([, value]) => value !== void 0));
      const contract = validateDeliveryOperationRecord(persisted);
      if (!contract.ok) return { ok: false, error: contract.error };
      await append2(this.paths(namespaceId, deliveryId).journal, persisted);
      await this.fault("after-delivery-operation-write");
      return { ok: true, changed: true, idempotent: false, operation: persisted };
    });
  }
  async recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options = {}) {
    return this._locked(namespaceId, deliveryId, async () => {
      await this.read(namespaceId, deliveryId);
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), previous = projection.operations.find((record2) => record2.operationId === operationId);
      if (!previous) return { ok: false, error: { code: "DELIVERY_OPERATION_NOT_FOUND" } };
      const now = (/* @__PURE__ */ new Date()).toISOString(), state = transition.state, next = {
        ...previous,
        state,
        attempt: state === "running" ? previous.attempt + 1 : previous.attempt,
        startedAt: state === "running" ? now : previous.startedAt,
        completedAt: ["succeeded", "failed"].includes(state) ? now : void 0,
        adapterCorrelation: transition.adapterCorrelation ?? previous.adapterCorrelation,
        result: transition.result,
        error: transition.error,
        resolvedOperationId: transition.resolvedOperationId
      };
      const clean = Object.fromEntries(Object.entries(next).filter(([, value]) => value !== void 0)), valid = validateDeliveryOperationTransition(previous, clean, options);
      if (!valid.ok) return valid;
      const contract = validateDeliveryOperationRecord(clean);
      if (!contract.ok) return contract;
      await append2(this.paths(namespaceId, deliveryId).journal, clean);
      await this.fault(`after-delivery-operation-${state}`);
      return { ok: true, changed: true, operation: clean };
    });
  }
  async startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation) {
    return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, { state: "running", adapterCorrelation });
  }
  async reconcileDeliveryOperation(namespaceId, deliveryId, operationId, observation) {
    return this.recordDeliveryOperation(
      namespaceId,
      deliveryId,
      operationId,
      {
        state: observation.state,
        result: observation.result,
        error: observation.error,
        adapterCorrelation: observation.adapterCorrelation,
        resolvedOperationId: operationId
      },
      { inspectedObservation: { ...observation, operationId } }
    );
  }
  /** Only an indeterminate logical operation without a later terminal reconciliation blocks. */
  async hasIndeterminateOperation(namespaceId, deliveryId) {
    try {
      return (await this.inspectDeliveryOperations(namespaceId, deliveryId)).unresolvedIndeterminate.length > 0;
    } catch {
      return true;
    }
  }
  /**
   * Atomically patch specific fields in the delivery snapshot and append a journal record.
   * Supports dot-notation keys like 'git.checkpoint' to set nested properties.
   * Used after checkpoint/push/PR to persist the new state without a full promote cycle.
   */
  async updateSnapshot(namespaceId, deliveryId, patch, operationInput) {
    return this._locked(namespaceId, deliveryId, async () => {
      const current = await this.read(namespaceId, deliveryId);
      if (!current) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
      const updated = { ...current };
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split(".");
        if (parts.length === 1) {
          updated[key] = value;
        } else if (parts.length === 2) {
          const head = parts[0], tail = parts[1];
          updated[head] = { ...updated[head] ?? {}, [tail]: value };
        } else {
          updated[key] = value;
        }
      }
      updated.updatedAt = patch.updatedAt ?? (/* @__PURE__ */ new Date()).toISOString();
      return this._write(current, updated, operationInput);
    });
  }
};

// ../src/adapters/persistence/delivery-evidence-store.ts
import { createHash as createHash15, randomUUID as randomUUID7 } from "node:crypto";
import { appendFile as appendFile4, mkdir as mkdir5, open as open5, readFile as readFile6 } from "node:fs/promises";
import { dirname as dirname6, join as join11 } from "node:path";
var SAFE8 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var UUID4 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var HASH3 = /^sha256:[0-9a-f]{64}$/;
var SHA4 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var ALLOWED3 = /* @__PURE__ */ new Set([
  "deliveryId",
  "workflowId",
  "environmentHash",
  "caseId",
  "runtimeId",
  "headCommit",
  "kind",
  "outcome",
  "oracleId",
  "facts",
  "idempotencyKey"
]);
var canonical4 = (value) => Array.isArray(value) ? value.map(canonical4) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((key) => [key, canonical4(value[key])])
) : value;
var digest3 = (value) => createHash15("sha256").update(JSON.stringify(canonical4(value))).digest("hex");
async function append3(path, value) {
  await mkdir5(dirname6(path), { recursive: true });
  await appendFile4(path, `${JSON.stringify(value)}
`, { mode: 384 });
  const handle = await open5(path, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}
function validateDeliveryEvidence(input) {
  if (!input || typeof input !== "object" || Array.isArray(input) || Object.keys(input).some((key) => !ALLOWED3.has(key)))
    return { ok: false, error: { code: "INVALID_DELIVERY_EVIDENCE" } };
  const candidate = input;
  if (![
    candidate.deliveryId,
    candidate.workflowId,
    candidate.runtimeId,
    candidate.kind,
    candidate.outcome,
    candidate.idempotencyKey
  ].every((value) => SAFE8.test(value ?? "")) || !HASH3.test(candidate.environmentHash ?? "") || !UUID4.test(candidate.caseId ?? "") || !SHA4.test(candidate.headCommit ?? ""))
    return { ok: false, error: { code: "INVALID_DELIVERY_EVIDENCE" } };
  if (!candidate.facts || typeof candidate.facts !== "object" || Array.isArray(candidate.facts) || Object.keys(candidate.facts).length > 32 || JSON.stringify(candidate.facts).length > 4096)
    return { ok: false, error: { code: "INVALID_DELIVERY_EVIDENCE" } };
  return { ok: true, value: canonical4(candidate) };
}
var DeliveryEvidenceStore = class {
  dataRoot;
  locks;
  constructor(dataRoot) {
    if (!dataRoot || typeof dataRoot !== "string") throw new Error("INVALID_DATA_ROOT");
    this.dataRoot = dataRoot;
    this.locks = /* @__PURE__ */ new Map();
  }
  path(namespaceId, deliveryId) {
    if (!UUID4.test(namespaceId ?? ""))
      throw Object.assign(new Error("INVALID_NAMESPACE_ID"), { code: "INVALID_NAMESPACE_ID" });
    if (!SAFE8.test(deliveryId ?? ""))
      throw Object.assign(new Error("INVALID_DELIVERY_ID"), { code: "INVALID_DELIVERY_ID" });
    return join11(
      this.dataRoot,
      "deliveries",
      namespaceId,
      createHash15("sha256").update(`${namespaceId}:${deliveryId}`).digest("hex"),
      "evidence.jsonl"
    );
  }
  async list(namespaceId, deliveryId) {
    try {
      return (await readFile6(this.path(namespaceId, deliveryId), "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    } catch (error2) {
      if (error2?.code === "ENOENT") return [];
      throw error2;
    }
  }
  _locked(key, action) {
    const prior = this.locks.get(key) ?? Promise.resolve(), operation = prior.then(action), tail = operation.catch(() => {
    });
    this.locks.set(key, tail);
    return operation.finally(() => {
      if (this.locks.get(key) === tail) this.locks.delete(key);
    });
  }
  async record(namespaceId, input, source) {
    const validation = validateDeliveryEvidence(input);
    if (!validation.ok) return validation;
    const value = validation.value;
    return this._locked(`${namespaceId}\0${value.deliveryId}`, async () => {
      const existing = await this.list(namespaceId, value.deliveryId), scopeHash = digest3({
        namespaceId,
        deliveryId: value.deliveryId,
        workflowId: value.workflowId,
        caseId: value.caseId,
        runtimeId: value.runtimeId,
        idempotencyKey: value.idempotencyKey
      }), semanticHash = digest3({ ...value, idempotencyKey: void 0 });
      const prior = existing.find((item) => item.idempotency.scopeHash === scopeHash);
      if (prior)
        return prior.idempotency.semanticHash === semanticHash ? { ok: true, created: false, evidence: prior } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      const evidence = {
        evidenceId: randomUUID7(),
        namespaceId,
        ...value,
        source: { ...source },
        observedAt: (/* @__PURE__ */ new Date()).toISOString(),
        idempotency: { scopeHash, semanticHash }
      };
      await append3(this.path(namespaceId, value.deliveryId), evidence);
      return { ok: true, created: true, evidence };
    });
  }
};

// ../src/adapters/delivery/delivery-target-registry.ts
var SAFE9 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var DIGEST2 = /^sha256:[0-9a-f]{64}$/i;
var FIELDS4 = [
  "targetId",
  "environmentKind",
  "adapterId",
  "adapterTargetRef",
  "supportsRollback",
  "verificationSuiteId",
  "verificationSuiteHash"
];
var unavailable = () => ({ ok: false, error: { code: "DELIVERY_TARGET_REGISTRY_UNAVAILABLE" } });
var DeliveryTargetRegistry = class {
  #targets;
  constructor(definitions) {
    this.#targets = null;
    if (definitions === void 0) return;
    if (!Array.isArray(definitions))
      throw Object.assign(new Error("Invalid target registry"), { code: "INVALID_DELIVERY_TARGET_REGISTRY" });
    const map = /* @__PURE__ */ new Map();
    for (const raw of definitions) {
      if (!raw || Object.keys(raw).some((k) => !FIELDS4.includes(k)) || !SAFE9.test(raw.targetId ?? "") || !["development", "staging", "production"].includes(raw.environmentKind) || !SAFE9.test(raw.adapterId ?? "") || !SAFE9.test(raw.adapterTargetRef ?? "") || typeof raw.supportsRollback !== "boolean" || raw.verificationSuiteId !== void 0 && !SAFE9.test(raw.verificationSuiteId) || raw.verificationSuiteHash !== void 0 && !DIGEST2.test(raw.verificationSuiteHash))
        throw Object.assign(new Error("Invalid target"), { code: "INVALID_DELIVERY_TARGET" });
      if (map.has(raw.targetId))
        throw Object.assign(new Error("Duplicate target"), { code: "DUPLICATE_DELIVERY_TARGET_ID" });
      const targetHash = canonicalDeliveryHash(raw);
      if ([...map.values()].some((v) => v.targetHash === targetHash))
        throw Object.assign(new Error("Ambiguous target hash"), { code: "AMBIGUOUS_DELIVERY_TARGET_HASH" });
      map.set(raw.targetId, Object.freeze({ ...raw, targetHash }));
    }
    this.#targets = map;
  }
  lookup(targetId) {
    if (!this.#targets) return unavailable();
    if (!SAFE9.test(targetId ?? "")) return { ok: false, error: { code: "DELIVERY_TARGET_NOT_FOUND" } };
    const target = this.#targets.get(targetId);
    return target ? { ok: true, target } : { ok: false, error: { code: "DELIVERY_TARGET_NOT_FOUND" } };
  }
};
var unavailableDeliveryTargetRegistry = Object.freeze({ lookup: unavailable });

// ../src/adapters/delivery/delivery-git-control-plane.ts
import { execFile } from "node:child_process";
import { createHash as createHash16 } from "node:crypto";
import { realpath as realpath3 } from "node:fs/promises";
import { isAbsolute as isAbsolute6 } from "node:path";
import { promisify } from "node:util";
var execute = promisify(execFile);
var SHA5 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var SAFE_REMOTE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SAFE_BRANCH = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,255}$/;
var fail4 = (code, details = {}) => {
  throw Object.assign(new Error(code), { code, details });
};
function createExecFileRunner() {
  return async (file, args, o = {}) => {
    try {
      const r = await execute(file, args, { cwd: o.cwd, encoding: "utf8" });
      return { exitCode: 0, stdout: r.stdout ?? "", stderr: r.stderr ?? "" };
    } catch (e) {
      const error2 = e;
      return {
        exitCode: Number.isInteger(error2.code) ? error2.code : 1,
        stdout: error2.stdout ?? "",
        stderr: error2.stderr ?? ""
      };
    }
  };
}
function parseStatusZ(raw) {
  const results = [];
  let i = 0;
  while (i < raw.length) {
    if (raw.length - i < 4) break;
    const xy = raw.slice(i, i + 2);
    if (raw[i + 2] !== " ") {
      i++;
      continue;
    }
    const nulPos = raw.indexOf("\0", i + 3);
    if (nulPos === -1) break;
    const path = raw.slice(i + 3, nulPos);
    i = nulPos + 1;
    let originalPath = null;
    if (xy[0] === "R" || xy[0] === "C" || xy[1] === "R" || xy[1] === "C") {
      const nulPos2 = raw.indexOf("\0", i);
      if (nulPos2 !== -1) {
        originalPath = raw.slice(i, nulPos2);
        i = nulPos2 + 1;
      }
    }
    results.push({ code: xy, path, originalPath });
  }
  return results;
}
function safePath(path) {
  return typeof path === "string" && path.length > 0 && !isAbsolute6(path) && !path.split(/[\\/]/).includes("..") && !path.includes("\0");
}
var matchesPrefix = (path, prefix) => path === prefix || path.startsWith(`${prefix}/`);
var DeliveryGitControlPlane = class {
  runner;
  identity;
  remote;
  allowedPaths;
  protectedPaths;
  constructor({
    runner = createExecFileRunner(),
    serviceIdentity,
    configuredRemote,
    allowedPaths = [],
    protectedPaths = []
  }) {
    if (!serviceIdentity?.name || !serviceIdentity?.email) fail4("SERVICE_IDENTITY_NOT_CONFIGURED");
    if (configuredRemote !== null && configuredRemote !== void 0 && !SAFE_REMOTE.test(configuredRemote))
      fail4("INVALID_REMOTE_CONFIGURATION");
    if (![...allowedPaths, ...protectedPaths].every(safePath)) fail4("INVALID_PATH_POLICY");
    this.runner = runner;
    this.identity = serviceIdentity;
    this.remote = configuredRemote;
    this.allowedPaths = allowedPaths;
    this.protectedPaths = protectedPaths;
  }
  async _run(args, cwd) {
    return this.runner("git", args, { cwd });
  }
  async inspect(binding) {
    const canonical5 = await realpath3(binding.worktreePath).catch(() => fail4("CANONICAL_WORKTREE_REQUIRED"));
    if (canonical5 !== binding.worktreePath) fail4("CANONICAL_WORKTREE_REQUIRED");
    const top = await this._run(["rev-parse", "--show-toplevel"], canonical5);
    if (top.exitCode || await realpath3(top.stdout.trim()).catch(() => null) !== canonical5)
      fail4("CANONICAL_WORKTREE_REQUIRED");
    if (!SAFE_BRANCH.test(binding.branch ?? "")) fail4("INVALID_BRANCH_NAME");
    const branch = await this._run(["branch", "--show-current"], canonical5), head = await this._run(["rev-parse", "HEAD"], canonical5);
    if (branch.exitCode || branch.stdout.trim() !== binding.branch || head.exitCode || !SHA5.test(head.stdout.trim()))
      fail4("WORKTREE_BINDING_UNCERTAIN");
    if (head.stdout.trim() !== binding.expectedHead)
      fail4("STALE_HEAD", { expected: binding.expectedHead, actual: head.stdout.trim() });
    const status = await this._run(["status", "--porcelain=v1", "-z", "--untracked-files=all"], canonical5);
    if (status.exitCode) fail4("GIT_INSPECTION_FAILED");
    const files = parseStatusZ(status.stdout);
    const protectedHit = files.find(
      (item) => this.protectedPaths.some(
        (prefix) => matchesPrefix(item.path, prefix) || item.originalPath && matchesPrefix(item.originalPath, prefix)
      )
    );
    if (protectedHit) fail4("PROTECTED_FILE_CHANGED", { path: protectedHit.path });
    const outOfScope = files.find(
      (item) => !this.allowedPaths.some((prefix) => matchesPrefix(item.path, prefix)) || item.originalPath && !this.allowedPaths.some((prefix) => matchesPrefix(item.originalPath, prefix))
    );
    if (outOfScope) fail4("SCOPE_VIOLATION", { path: outOfScope.path });
    const allPaths = [
      ...new Set(files.flatMap((item) => item.originalPath ? [item.path, item.originalPath] : [item.path]))
    ].sort();
    const trackedDiff = await this._run(
      ["diff", "--binary", "--no-ext-diff", binding.baseCommit, "--", ...allPaths],
      canonical5
    );
    if (trackedDiff.exitCode) fail4("GIT_INSPECTION_FAILED");
    const untracked = files.filter((item) => item.code === "??").map((item) => item.path).sort();
    const diffContent = `${trackedDiff.stdout}
${untracked.map((path) => `untracked ${path}`).join("\n")}`;
    return {
      worktreePath: canonical5,
      branch: binding.branch,
      headCommit: head.stdout.trim(),
      files,
      diffHash: `sha256:${createHash16("sha256").update(diffContent).digest("hex")}`
    };
  }
  compareClaims(inspection, claims) {
    const candidate = claims;
    if (!candidate) fail4("INVALID_CLAIMS");
    if (Object.keys(candidate).some((key) => !["paths", "diffHash"].includes(key)) || !Array.isArray(candidate.paths))
      fail4("INVALID_CLAIMS");
    const trusted = candidate;
    const actual = [...new Set(inspection.files.map((item) => item.path))].sort(), claimed = [...new Set(trusted.paths)].sort();
    if (JSON.stringify(actual) !== JSON.stringify(claimed) || trusted.diffHash !== inspection.diffHash)
      fail4("CLAIMS_MISMATCH");
    return { ok: true };
  }
  async checkpoint(binding, { message, claims }) {
    const inspection = await this.inspect(binding);
    this.compareClaims(inspection, claims);
    if (inspection.files.length === 0) return { changed: false, commit: inspection.headCommit, inspection };
    const paths = inspection.files.map((item) => item.path);
    const add = await this._run(["add", "--", ...paths], inspection.worktreePath);
    if (add.exitCode) fail4("GIT_STAGE_FAILED");
    const staged = await this._run(["diff", "--cached", "--quiet", "--exit-code"], inspection.worktreePath);
    if (staged.exitCode === 0) return { changed: false, commit: inspection.headCommit, inspection };
    if (staged.exitCode !== 1) fail4("GIT_STAGE_INDETERMINATE");
    const commit = await this._run(
      [
        "-c",
        `user.name=${this.identity.name}`,
        "-c",
        `user.email=${this.identity.email}`,
        "commit",
        "--no-gpg-sign",
        "-m",
        message,
        "--",
        ...paths
      ],
      inspection.worktreePath
    );
    if (commit.exitCode) fail4("GIT_COMMIT_FAILED");
    const head = await this._run(["rev-parse", "HEAD"], inspection.worktreePath), identity = await this._run(["show", "-s", "--format=%cn%n%ce", "HEAD"], inspection.worktreePath);
    if (head.exitCode || !SHA5.test(head.stdout.trim()) || identity.stdout.trim() !== `${this.identity.name}
${this.identity.email}`)
      fail4("GIT_COMMIT_INDETERMINATE");
    return { changed: true, commit: head.stdout.trim(), previousHead: inspection.headCommit, inspection };
  }
  async push(binding) {
    if (!this.remote) return { ok: false, blocked: true, error: { code: "REMOTE_NOT_CONFIGURED" } };
    const inspection = await this.inspect(binding), remoteHeadBefore = await this._run(
      ["ls-remote", "--heads", this.remote, `refs/heads/${binding.branch}`],
      inspection.worktreePath
    );
    if (remoteHeadBefore.exitCode) fail4("REMOTE_INSPECTION_FAILED");
    const previous = remoteHeadBefore.stdout.trim().split(/\s+/)[0] || null;
    if (previous === inspection.headCommit) return { ok: true, changed: false, headCommit: inspection.headCommit };
    const push = await this._run(
      ["push", "--porcelain", this.remote, `refs/heads/${binding.branch}:refs/heads/${binding.branch}`],
      inspection.worktreePath
    );
    if (push.exitCode) fail4("GIT_PUSH_FAILED");
    const remoteHeadAfter = await this._run(
      ["ls-remote", "--heads", this.remote, `refs/heads/${binding.branch}`],
      inspection.worktreePath
    ), actual = remoteHeadAfter.stdout.trim().split(/\s+/)[0];
    if (remoteHeadAfter.exitCode || actual !== inspection.headCommit) fail4("GIT_PUSH_INDETERMINATE");
    return { ok: true, changed: true, headCommit: inspection.headCommit, previousRemoteHead: previous };
  }
};

// ../src/adapters/delivery/delivery-pr-adapter.ts
var TRUSTED_PR_HOSTS = /* @__PURE__ */ new Set(["github.com", "www.github.com"]);
function trustedPullRequestUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === "https:" && TRUSTED_PR_HOSTS.has(url.hostname);
  } catch {
    return false;
  }
}
var DeliveryPullRequestAdapter = class {
  provider;
  constructor({ provider = null } = {}) {
    this.provider = provider;
  }
  /**
   * Find an existing PR by stable identity (head branch + base branch from trusted configuration).
   * Returns { ok: true, pullRequest } if found, { ok: true, pullRequest: null } if not found,
   * or { ok: false, error } if the provider is not configured or inspection fails.
   */
  async findExisting(context) {
    const provider = this.provider;
    if (!provider) return { ok: false, blocked: true, error: { code: "PULL_REQUEST_NOT_CONFIGURED" } };
    if (typeof provider.findExisting !== "function") return { ok: true, pullRequest: null };
    try {
      const result = await provider.findExisting(context);
      if (!result) return { ok: true, pullRequest: null };
      if (!result.id || !trustedPullRequestUrl(result.url) || !result.state)
        return { ok: false, blocked: true, error: { code: "PULL_REQUEST_RESULT_INDETERMINATE" } };
      return {
        ok: true,
        pullRequest: {
          id: String(result.id),
          url: result.url,
          draft: result.draft === true,
          state: result.state
        }
      };
    } catch {
      return { ok: false, blocked: true, error: { code: "PULL_REQUEST_INSPECTION_FAILED" } };
    }
  }
  async createDraft(context) {
    const provider = this.provider;
    if (!provider) return { ok: false, blocked: true, error: { code: "PULL_REQUEST_NOT_CONFIGURED" } };
    const existing = await this.findExisting(context);
    if (!existing.ok) return existing;
    if (existing.pullRequest) return { ok: true, pullRequest: existing.pullRequest, reused: true };
    try {
      const result = await provider.createDraft(context);
      if (!result?.id || !trustedPullRequestUrl(result.url) || result.draft !== true)
        return { ok: false, blocked: true, error: { code: "PULL_REQUEST_RESULT_INDETERMINATE" } };
      return {
        ok: true,
        pullRequest: { id: String(result.id), url: result.url, draft: true, state: "open" },
        reused: false
      };
    } catch {
      return { ok: false, blocked: true, error: { code: "PULL_REQUEST_CREATION_FAILED" } };
    }
  }
  async inspect(context) {
    const provider = this.provider;
    if (!provider) return { ok: false, blocked: true, error: { code: "PULL_REQUEST_NOT_CONFIGURED" } };
    const result = await provider.inspect(context);
    return result?.id && trustedPullRequestUrl(result.url) && result?.state ? { ok: true, pullRequest: result } : { ok: false, blocked: true, error: { code: "PULL_REQUEST_RESULT_INDETERMINATE" } };
  }
};

// ../src/adapters/delivery/delivery-deployment-adapter.ts
var SAFE10 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var DELIVERY_ADAPTER_OUTCOMES = Object.freeze(["running", "succeeded", "failed", "indeterminate"]);
function normalizeDeliveryAdapterOutcome(value) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some((k) => !["state", "correlationRef", "resultRef", "errorCode", "observedAt"].includes(k)) || !DELIVERY_ADAPTER_OUTCOMES.includes(value.state))
    return { ok: false, error: { code: "INVALID_DELIVERY_ADAPTER_OUTCOME" } };
  const candidate = value;
  for (const key of ["correlationRef", "resultRef", "errorCode"])
    if (candidate[key] !== void 0 && !SAFE10.test(candidate[key]))
      return { ok: false, error: { code: "INVALID_DELIVERY_ADAPTER_OUTCOME", path: key } };
  if (candidate.observedAt !== void 0 && (!Number.isFinite(Date.parse(candidate.observedAt)) || new Date(Date.parse(candidate.observedAt)).toISOString() !== candidate.observedAt))
    return { ok: false, error: { code: "INVALID_DELIVERY_ADAPTER_OUTCOME", path: "observedAt" } };
  return { ok: true, value: Object.freeze({ ...candidate }) };
}
var DeliveryDeploymentAdapter = class {
  async deploy(..._args) {
    throw new Error("Not implemented");
  }
  async rollback(..._args) {
    throw new Error("Not implemented");
  }
  async inspect(..._args) {
    throw new Error("Not implemented");
  }
  async reconcile(operation) {
    return this.inspect(operation);
  }
};
var DeliveryVerificationAdapter = class {
  async verify(..._args) {
    throw new Error("Not implemented");
  }
  async inspect(..._args) {
    throw new Error("Not implemented");
  }
};
var blocked = async () => ({
  ok: false,
  error: { code: "DELIVERY_ADAPTER_NOT_CONFIGURED" }
});
var UnconfiguredDeliveryDeploymentAdapter = class {
  deploy = blocked;
  rollback = blocked;
  inspect = blocked;
  reconcile = blocked;
};
var UnconfiguredDeliveryVerificationAdapter = class {
  verify = blocked;
  inspect = blocked;
};

// ../src/application/delivery/delivery-controller.ts
var UUID5 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE11 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var FORBIDDEN = /* @__PURE__ */ new Set([
  "root",
  "repoRoot",
  "worktreePath",
  "remote",
  "url",
  "owner",
  "repo",
  "command",
  "credentials",
  "token"
]);
var rejectUntrusted = (body) => !body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).some((key) => FORBIDDEN.has(key));
var DeliveryController = class {
  store;
  evidenceStore;
  environmentController;
  workflowStore;
  git;
  pullRequests;
  definition;
  configuration;
  constructor({
    store,
    evidenceStore,
    environmentController,
    workflowStore,
    git,
    pullRequests,
    definition,
    trustedConfiguration
  }) {
    const validated = validateDeliveryDefinition(definition);
    if (!validated.ok) throw new Error("INVALID_DELIVERY_DEFINITION");
    this.store = store;
    this.evidenceStore = evidenceStore;
    this.environmentController = environmentController;
    this.workflowStore = workflowStore;
    this.git = git;
    this.pullRequests = pullRequests;
    this.definition = Object.freeze({
      ...validated.definition,
      definitionHash: hashDeliveryDefinition(validated.definition)
    });
    this.configuration = trustedConfiguration;
  }
  async initialize() {
    await this.store.initialize();
  }
  async resolve(identity, workflowId) {
    if (!identity || !UUID5.test(identity.namespaceId ?? "") || !UUID5.test(identity.caseId ?? "") || !SAFE11.test(workflowId ?? ""))
      return { ok: false, status: 400, error: { code: "INVALID_TRUST_CONTEXT" } };
    let workflow = await this.workflowStore.read(identity.namespaceId, workflowId), environmentResult = await this.environmentController.get(identity.namespaceId, workflowId);
    if (!workflow?.instance || !environmentResult.ok)
      return { ok: false, status: 409, error: { code: "DELIVERY_BINDING_UNAVAILABLE" } };
    const environment = environmentResult.data?.environment, reconciliation = environmentResult.data?.reconciliation;
    if (workflow.instance.controllerExecution?.caseId !== identity.caseId || environment.parentCaseId !== identity.caseId || environment.workflowId !== workflowId || reconciliation?.status !== "owned" || reconciliation.worktreePath !== environment.worktreePath)
      return { ok: false, status: 409, error: { code: "DELIVERY_SCOPE_MISMATCH" } };
    const deliveryId = `${workflowId}-delivery`, runtimeId = workflow.instance.controllerExecution.runtimeId ?? "agentos", environmentHash = workflow.instance.environmentRef?.environmentHash;
    if (!environmentHash) return { ok: false, status: 409, error: { code: "DELIVERY_BINDING_UNAVAILABLE" } };
    const existing = await this.store.read(identity.namespaceId, deliveryId);
    if (!existing) {
      const created = await this.store.create({
        schemaVersion: "1",
        deliveryId,
        namespaceId: identity.namespaceId,
        workflowId,
        environmentId: environment.environmentId,
        environmentHash,
        parentCaseId: identity.caseId,
        runtimeId,
        worktreePath: environment.worktreePath,
        branch: environment.branch,
        baseCommit: environment.baseCommit,
        headCommit: reconciliation.headCommit,
        definitionType: this.definition.deliveryType,
        definitionVersion: this.definition.version,
        definitionHash: this.definition.definitionHash,
        stage: "implementation-ready",
        revision: 1,
        evidenceIds: [],
        createdAt: environment.createdAt,
        updatedAt: (/* @__PURE__ */ new Date()).toISOString(),
        git: { checkpoint: null, push: null, pullRequest: null },
        artifact: { state: "pending" },
        release: { state: "pending" },
        deployment: { state: "pending" },
        verification: { state: "pending" },
        blockers: []
      });
      if (!created.ok) return { ok: false, status: 409, error: created.error };
      const linked = await this.workflowStore.bindDelivery(identity.namespaceId, workflowId, {
        deliveryId,
        definitionHash: this.definition.definitionHash
      });
      if (!linked.ok) return { ok: false, status: 409, error: linked.error };
      return { ok: true, snapshot: created.snapshot, environment, reconciliation };
    }
    let deliveryRef = workflow.instance.deliveryRef;
    if (!deliveryRef) {
      const linked = await this.workflowStore.bindDelivery(identity.namespaceId, workflowId, {
        deliveryId,
        definitionHash: existing.definitionHash
      });
      if (!linked.ok) return { ok: false, status: 409, error: linked.error };
      workflow = linked.snapshot;
      deliveryRef = workflow.instance.deliveryRef;
    } else if (deliveryRef.deliveryId !== existing.deliveryId || deliveryRef.definitionHash !== existing.definitionHash)
      return { ok: false, status: 409, error: { code: "DELIVERY_SCOPE_MISMATCH" } };
    if (existing.environmentId !== environment.environmentId || existing.environmentHash !== workflow.instance.environmentRef?.environmentHash || existing.worktreePath !== environment.worktreePath || existing.parentCaseId !== identity.caseId || existing.runtimeId !== runtimeId)
      return { ok: false, status: 409, error: { code: "DELIVERY_SCOPE_MISMATCH" } };
    const hasIndeterminate = await this.store.hasIndeterminateOperation(identity.namespaceId, deliveryId);
    if (hasIndeterminate) return { ok: false, status: 409, error: { code: "DELIVERY_INDETERMINATE_OPERATION_PENDING" } };
    if (existing.headCommit !== reconciliation.headCommit)
      return {
        ok: false,
        status: 409,
        error: {
          code: "DELIVERY_HEAD_RECONCILIATION_REQUIRED",
          expectedHead: existing.headCommit,
          observedHead: reconciliation.headCommit
        }
      };
    return { ok: true, snapshot: existing, environment, reconciliation };
  }
  async status(identity, workflowId) {
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const projection = await this.store.inspectDeliveryOperations(
      identity.namespaceId,
      resolved.snapshot.deliveryId
    );
    return {
      ok: true,
      status: 200,
      data: {
        ...resolved.snapshot,
        deliveryOperations: projection.operations,
        unresolvedIndeterminate: projection.unresolvedIndeterminate,
        rollbackRequests: projection.rollbackRequests
      }
    };
  }
  async checkpoint(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some(
      (key) => !["expectedHead", "message", "claims", "idempotencyKey"].includes(key)
    ))
      return { ok: false, status: 400, error: { code: "UNTRUSTED_DELIVERY_INPUT" } };
    const input = body;
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    if (input.expectedHead !== resolved.reconciliation.headCommit || resolved.snapshot.headCommit !== input.expectedHead)
      return { ok: false, status: 409, error: { code: "STALE_HEAD" } };
    const binding = {
      worktreePath: resolved.environment.worktreePath,
      branch: resolved.environment.branch,
      baseCommit: resolved.environment.baseCommit,
      expectedHead: input.expectedHead
    };
    let gitResult;
    try {
      gitResult = await this.git.checkpoint(binding, { message: input.message, claims: input.claims });
    } catch (error2) {
      const code = error2?.code;
      const state = ["GIT_COMMIT_INDETERMINATE", "GIT_STAGE_INDETERMINATE"].includes(code) ? "indeterminate" : "failed";
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: "git-checkpoint",
        state,
        idempotencyKey: input.idempotencyKey ?? `checkpoint:${input.expectedHead}`,
        facts: { code: code ?? "GIT_FAILED" }
      });
      return { ok: false, status: 409, error: { code: code ?? "GIT_FAILED" } };
    }
    const newHead = gitResult.commit;
    const snapshotPatch = {
      headCommit: newHead,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString(),
      "git.checkpoint": {
        commit: newHead,
        previousHead: gitResult.previousHead ?? input.expectedHead,
        changed: gitResult.changed,
        diffHash: gitResult.inspection.diffHash,
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      }
    };
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: "git-checkpoint",
      idempotencyKey: input.idempotencyKey ?? `checkpoint:${input.expectedHead}`,
      facts: { commit: newHead, changed: gitResult.changed, diffHash: gitResult.inspection.diffHash }
    });
    return { ok: true, status: gitResult.changed ? 201 : 200, data: gitResult };
  }
  async push(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some((key) => !["expectedHead", "idempotencyKey"].includes(key)))
      return { ok: false, status: 400, error: { code: "UNTRUSTED_DELIVERY_INPUT" } };
    const input = body;
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const expectedHead = input.expectedHead ?? resolved.snapshot.headCommit;
    let result;
    try {
      result = await this.git.push({
        worktreePath: resolved.environment.worktreePath,
        branch: resolved.environment.branch,
        baseCommit: resolved.environment.baseCommit,
        expectedHead
      });
    } catch (error2) {
      const code = error2?.code;
      const state = code === "GIT_PUSH_INDETERMINATE" ? "indeterminate" : "failed";
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: "git-push",
        state,
        idempotencyKey: input.idempotencyKey ?? `push:${expectedHead}`,
        facts: { code: code ?? "GIT_PUSH_FAILED" }
      });
      return { ok: false, status: 409, error: { code: code ?? "GIT_PUSH_FAILED" } };
    }
    if (!result.ok) {
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: "git-push",
        state: "failed",
        idempotencyKey: input.idempotencyKey ?? `push:${expectedHead}`,
        facts: { code: result.error.code }
      });
      return { ok: false, status: 422, error: result.error };
    }
    const snapshotPatch = {
      updatedAt: (/* @__PURE__ */ new Date()).toISOString(),
      "git.push": {
        headCommit: result.headCommit,
        changed: result.changed,
        remote: this.git.remote ?? null,
        branch: resolved.environment.branch,
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      }
    };
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: "git-push",
      idempotencyKey: input.idempotencyKey ?? `push:${expectedHead}`,
      facts: { headCommit: result.headCommit, changed: result.changed }
    });
    return { ok: true, status: 200, data: result };
  }
  async pullRequest(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some((key) => !["title", "body", "idempotencyKey"].includes(key)))
      return { ok: false, status: 400, error: { code: "UNTRUSTED_DELIVERY_INPUT" } };
    const input = body;
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const configured = this.configuration.pullRequest;
    if (!configured) return { ok: false, status: 422, error: { code: "PULL_REQUEST_NOT_CONFIGURED" } };
    const context = {
      owner: configured.owner,
      repo: configured.repo,
      baseBranch: configured.baseBranch,
      headBranch: resolved.environment.branch,
      title: input.title,
      body: input.body,
      idempotencyKey: input.idempotencyKey
    };
    const result = await this.pullRequests.createDraft(context);
    const state = result.ok ? "succeeded" : "failed";
    await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
      kind: "pull-request",
      state,
      idempotencyKey: input.idempotencyKey ?? `pr:${resolved.environment.branch}`,
      facts: result.ok ? {
        id: result.pullRequest.id,
        url: result.pullRequest.url,
        reused: result.reused ?? false
      } : { code: result.error.code }
    });
    if (!result.ok) return { ok: false, status: 422, error: result.error };
    const pullRequest = result.pullRequest;
    const snapshotPatch = {
      updatedAt: (/* @__PURE__ */ new Date()).toISOString(),
      "git.pullRequest": {
        id: pullRequest.id,
        url: pullRequest.url,
        draft: pullRequest.draft,
        state: pullRequest.state,
        reused: result.reused ?? false,
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      }
    };
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: "pull-request-persisted",
      idempotencyKey: `pr-persisted:${pullRequest.id}`,
      facts: { id: pullRequest.id }
    });
    return { ok: true, status: result.reused ? 200 : 201, data: pullRequest };
  }
  async promote(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some(
      (key) => !["deliveryId", "expectedRevision", "requestedStage", "evidenceIds", "idempotencyKey"].includes(key)
    ))
      return { ok: false, status: 400, error: { code: "UNTRUSTED_DELIVERY_INPUT" } };
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const validation = validateDeliveryPromotionRequest(body, resolved.snapshot.deliveryId);
    if (!validation.ok) return { ok: false, status: 400, error: validation.error };
    const evidence = await this.evidenceStore.list(
      identity.namespaceId,
      resolved.snapshot.deliveryId
    );
    const actorId = identity.resolvedActorId ?? "factory-operator";
    const execution2 = {
      kind: validation.value.requestedStage === "release-approved" ? "factory-human" : "factory-control-plane",
      namespaceId: identity.namespaceId,
      workflowId,
      caseId: identity.caseId,
      runtimeId: "factory-dashboard",
      actorId
    };
    const result = await this.store.promote({
      namespaceId: identity.namespaceId,
      request: validation.value,
      definition: this.definition,
      evidence,
      execution: execution2
    });
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.snapshot } : { ok: false, status: 409, error: result.error };
  }
  /**
   * Record a Factory-only delivery evidence entry.
   * sourceKind must be one of the trusted internal producers.
   * Agents are not permitted to write delivery evidence.
   */
  async recordEvidence(identity, workflowId, input, sourceKind) {
    const ALLOWED_SOURCE_KINDS = /* @__PURE__ */ new Set(["factory-build", "factory-human"]);
    const forbiddenAuthority = /* @__PURE__ */ new Set(["deployment-result", "smoke-result", "rollback-result"]);
    if (!ALLOWED_SOURCE_KINDS.has(sourceKind) || forbiddenAuthority.has(input?.kind))
      return { ok: false, status: 403, error: { code: "DELIVERY_EVIDENCE_AUTHORITY_FORBIDDEN" } };
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const result = await this.evidenceStore.record(
      identity.namespaceId,
      {
        ...input,
        deliveryId: resolved.snapshot.deliveryId,
        workflowId,
        environmentHash: resolved.snapshot.environmentHash,
        caseId: identity.caseId,
        runtimeId: "factory-dashboard",
        headCommit: resolved.snapshot.headCommit
      },
      { kind: sourceKind, actorId: identity.resolvedActorId ?? "factory-operator" }
    );
    return result.ok ? { ok: true, status: result.created ? 201 : 200, data: result.evidence } : { ok: false, status: 409, error: result.error };
  }
};
async function handleDeliveryRequest({
  method,
  path,
  readBody,
  send,
  identity,
  controller,
  log = console
}) {
  const match2 = path.match(
    /^\/api\/factory\/workflows\/([^/]+)\/delivery(?:\/(checkpoint|push|pull-request|promote|evidence))?$/
  );
  if (!match2) return false;
  try {
    const trust = await identity();
    if (!trust) {
      send(401, { error: { code: "TRUST_CONTEXT_UNAVAILABLE" } });
      return true;
    }
    const workflowId = decodeURIComponent(match2[1]), action = match2[2];
    let result;
    if (!action && method === "GET") result = await controller.status(trust, workflowId);
    else if (action === "checkpoint" && method === "POST")
      result = await controller.checkpoint(trust, workflowId, await readBody());
    else if (action === "push" && method === "POST") result = await controller.push(trust, workflowId, await readBody());
    else if (action === "pull-request" && method === "POST")
      result = await controller.pullRequest(trust, workflowId, await readBody());
    else if (action === "promote" && method === "POST")
      result = await controller.promote(trust, workflowId, await readBody());
    else if (action === "evidence" && method === "POST")
      result = await controller.recordEvidence(trust, workflowId, await readBody(), "factory-build");
    else result = { ok: false, status: 405, error: { code: "METHOD_NOT_ALLOWED" } };
    send(result.status ?? (result.ok ? 200 : 409), result.ok ? { data: result.data } : { error: result.error });
    return true;
  } catch (error2) {
    log.error("Delivery control-plane failure", { code: error2?.code ?? "UNEXPECTED" });
    send(500, { error: { code: "DELIVERY_CONTROL_PLANE_FAILURE" } });
    return true;
  }
}

// ../src/application/delivery/delivery-operation-controller.ts
var SAFE12 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var REASON = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
var FORBIDDEN2 = /* @__PURE__ */ new Set([
  "targetConfig",
  "adapterId",
  "adapterTargetRef",
  "callbackUrl",
  "command",
  "env",
  "credentials",
  "result",
  "outcome",
  "success",
  "sourceKind",
  "facts"
]);
var exact2 = (value, fields) => value !== null && typeof value === "object" && !Array.isArray(value) && Object.keys(value).every((key) => fields.includes(key)) && !Object.keys(value).some((key) => FORBIDDEN2.has(key));
var response = (error2, fallback = 409) => ({
  ok: false,
  status: error2?.code?.includes("NOT_CONFIGURED") || error2?.code === "DELIVERY_TARGET_REGISTRY_UNAVAILABLE" ? 503 : fallback,
  error: error2
});
var execution = (identity, workflowId) => ({
  kind: "factory-control-plane",
  namespaceId: identity.namespaceId,
  workflowId,
  caseId: identity.caseId,
  runtimeId: "factory-dashboard",
  actorId: identity.resolvedActorId ?? identity.actorId ?? "factory-operator"
});
var requestId = (scopeHash) => `rrq_${scopeHash.slice(7, 39)}`;
var DeliveryOperationController = class {
  deliveryController;
  store;
  targetRegistry;
  deploymentAdapters;
  verificationAdapters;
  constructor({
    deliveryController,
    store,
    targetRegistry,
    deploymentAdapters = /* @__PURE__ */ new Map(),
    verificationAdapters = /* @__PURE__ */ new Map()
  }) {
    this.deliveryController = deliveryController;
    this.store = store;
    this.targetRegistry = targetRegistry;
    this.deploymentAdapters = deploymentAdapters;
    this.verificationAdapters = verificationAdapters;
  }
  async resolve(identity, workflowId) {
    return this.deliveryController.resolve(identity, workflowId);
  }
  async target(targetId) {
    const found = await this.targetRegistry.lookup(targetId);
    return found.ok ? found : response(found.error, found.error?.code === "DELIVERY_TARGET_REGISTRY_UNAVAILABLE" ? 503 : 404);
  }
  adapter(registry2, target) {
    const adapter = registry2.get?.(target.adapterId);
    return adapter ? { ok: true, adapter } : response({ code: "DELIVERY_ADAPTER_NOT_CONFIGURED" }, 503);
  }
  async status(identity, workflowId) {
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId);
    return {
      ok: true,
      status: 200,
      data: {
        ...resolved.snapshot,
        deliveryOperations: projection.operations,
        unresolvedIndeterminate: projection.unresolvedIndeterminate,
        rollbackRequests: projection.rollbackRequests
      }
    };
  }
  async prepare(identity, workflowId, body, kind, fields, adapterRegistry) {
    if (!exact2(body, fields)) return response({ code: "UNTRUSTED_DELIVERY_INPUT" }, 400);
    const normalized = normalizeDeliveryOperationRequest({ ...body, kind });
    if (!normalized.ok) return response(normalized.error, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const targetResult = await this.target(normalized.value.targetId);
    if (!targetResult.ok) return targetResult;
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId);
    const decision = evaluateDeliveryOperationPolicy({
      request: normalized.value,
      snapshot: resolved.snapshot,
      target: targetResult.target,
      identity: { targetHash: targetResult.target.targetHash },
      existingOperations: projection.operations
    });
    if (!decision.allowed) return response({ code: decision.code, reason: decision.reason });
    const available = this.adapter(adapterRegistry, targetResult.target);
    if (!available.ok) return available;
    return {
      ok: true,
      resolved,
      target: targetResult.target,
      request: normalized.value,
      adapter: available.adapter,
      projection
    };
  }
  async deploy(identity, workflowId, body) {
    const prepared = await this.prepare(
      identity,
      workflowId,
      body,
      "deployment",
      ["expectedRevision", "idempotencyKey", "targetId", "artifactRef", "releaseRef"],
      this.deploymentAdapters
    );
    if (!prepared.ok) return prepared;
    return response({ code: "DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED" }, 503);
  }
  async verify(identity, workflowId, body) {
    const prepared = await this.prepare(
      identity,
      workflowId,
      body,
      "production-verification",
      ["expectedRevision", "idempotencyKey", "targetId", "deploymentRef"],
      this.verificationAdapters
    );
    if (!prepared.ok) return prepared;
    const suite = resolveDeliveryVerificationRequest(prepared.request, prepared.target);
    if (!suite.ok) return response(suite.error);
    return response({ code: "DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED" }, 503);
  }
  async reconcile(identity, workflowId, body) {
    if (!exact2(body, ["operationId"])) return response({ code: "UNTRUSTED_DELIVERY_INPUT" }, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId);
    const operation = projection.operations.find((item) => item.operationId === body.operationId);
    if (!operation || !["running", "indeterminate"].includes(operation.state))
      return response({ code: "DELIVERY_OPERATION_NOT_RECONCILABLE" }, 409);
    const adapterResult = this.adapter(
      operation.kind.includes("verification") ? this.verificationAdapters : this.deploymentAdapters,
      operation.targetRef
    );
    if (!adapterResult.ok) return adapterResult;
    return response({ code: "DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED" }, 503);
  }
  async requestRollback(identity, workflowId, body) {
    const fields = [
      "expectedRevision",
      "idempotencyKey",
      "targetId",
      "deploymentRef",
      "priorArtifactRef",
      "priorReleaseRef",
      "reasonCode",
      "reason"
    ];
    if (!exact2(body, fields) || !SAFE12.test(body.idempotencyKey ?? "") || !SAFE12.test(body.targetId ?? "") || !REASON.test(body.reasonCode ?? "") || body.reason !== void 0 && (typeof body.reason !== "string" || body.reason.length < 1 || body.reason.length > 512))
      return response({ code: "INVALID_ROLLBACK_REQUEST" }, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const targetResult = await this.target(body.targetId);
    if (!targetResult.ok) return targetResult;
    if (resolved.snapshot.revision !== body.expectedRevision) return response({ code: "REVISION_CONFLICT" });
    const scopeHash = canonicalDeliveryHash({
      namespaceId: identity.namespaceId,
      workflowId,
      deliveryId: resolved.snapshot.deliveryId,
      caseId: identity.caseId,
      runtimeId: "factory-dashboard",
      idempotencyKey: body.idempotencyKey
    });
    const semanticHash = canonicalDeliveryHash({
      targetHash: targetResult.target.targetHash,
      deploymentRef: body.deploymentRef,
      priorArtifactRef: body.priorArtifactRef,
      priorReleaseRef: body.priorReleaseRef,
      reasonCode: body.reasonCode,
      reason: body.reason ?? null,
      expectedRevision: body.expectedRevision
    });
    const result = await this.store.createRollbackRequest({
      namespaceId: identity.namespaceId,
      deliveryId: resolved.snapshot.deliveryId,
      workflowId,
      caseId: identity.caseId,
      runtimeId: "factory-dashboard",
      request: {
        rollbackRequestId: requestId(scopeHash),
        expectedRevision: body.expectedRevision,
        idempotencyKey: body.idempotencyKey,
        targetId: body.targetId,
        targetHash: targetResult.target.targetHash,
        deploymentRef: body.deploymentRef,
        priorArtifactRef: body.priorArtifactRef,
        priorReleaseRef: body.priorReleaseRef,
        reasonCode: body.reasonCode,
        reason: body.reason,
        scopeHash,
        semanticHash
      },
      execution: execution(identity, workflowId)
    });
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.request } : response(result.error);
  }
  async approveRollback(identity, workflowId, requestIdValue, body) {
    if (!exact2(body, ["expectedRevision", "idempotencyKey"]) || !SAFE12.test(requestIdValue ?? "") || !SAFE12.test(body.idempotencyKey ?? ""))
      return response({ code: "INVALID_ROLLBACK_APPROVAL" }, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const result = await this.store.approveRollbackRequest(
      identity.namespaceId,
      resolved.snapshot.deliveryId,
      requestIdValue,
      {
        expectedRevision: body.expectedRevision,
        idempotencyKey: body.idempotencyKey,
        execution: execution(identity, workflowId)
      }
    );
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.request } : response(result.error);
  }
  async executeRollback(identity, workflowId, requestIdValue, body) {
    if (!exact2(body, ["expectedRevision", "idempotencyKey"]) || !SAFE12.test(requestIdValue ?? ""))
      return response({ code: "UNTRUSTED_DELIVERY_INPUT" }, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId);
    const rollback = projection.rollbackRequests.find((item) => item.rollbackRequestId === requestIdValue);
    if (!rollback) return response({ code: "ROLLBACK_REQUEST_NOT_FOUND" }, 404);
    if (rollback.status !== "approved") return response({ code: "ROLLBACK_APPROVAL_REQUIRED" });
    const targetResult = await this.target(rollback.targetId);
    if (!targetResult.ok) return targetResult;
    const available = this.adapter(this.deploymentAdapters, targetResult.target);
    if (!available.ok) return available;
    return response({ code: "DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED" }, 503);
  }
  async verifyRollback(identity, workflowId, requestIdValue, body) {
    if (!exact2(body, ["expectedRevision", "idempotencyKey", "rollbackRef", "targetId"]) || !SAFE12.test(requestIdValue ?? ""))
      return response({ code: "UNTRUSTED_DELIVERY_INPUT" }, 400);
    const resolved = await this.resolve(identity, workflowId);
    if (!resolved.ok) return resolved;
    const targetResult = await this.target(body.targetId);
    if (!targetResult.ok) return targetResult;
    const available = this.adapter(this.verificationAdapters, targetResult.target);
    if (!available.ok) return available;
    return response({ code: "DELIVERY_ADAPTER_EXECUTION_NOT_IMPLEMENTED" }, 503);
  }
};

// ../src/domain/forge-bmad/forge-roots.ts
import { isAbsolute as isAbsolute7, join as join12, relative as relative3 } from "node:path";
var FORGE_ROOTS_SCHEMA_VERSION = 2;
var DEFAULT_RUN_STORE_POLICY = "under_orchestrator";
var EXTERNAL_RUN_STORE_POLICY = "external_allowed";
var REPO_RUN_STORE_POLICY = "under_repo";
var FORGE_RUN_STORE_POLICIES = Object.freeze([
  DEFAULT_RUN_STORE_POLICY,
  EXTERNAL_RUN_STORE_POLICY,
  REPO_RUN_STORE_POLICY
]);
function isWithin(child, parent) {
  const rel = relative3(parent, child);
  return rel === "" || !rel.startsWith("..") && !isAbsolute7(rel);
}
function defaultRunStoreRoot(repoRoot) {
  return join12(repoRoot, "forge", "factory-runs");
}

// ../src/domain/forge-bmad/forge-human-decision.ts
import { createHash as createHash17 } from "node:crypto";
var G1_POLICY_VERSION = "forge-g1-human-v1";
var G1_OUTCOMES = /* @__PURE__ */ new Set(["approved", "rejected"]);
var G1_REASON_CODES = /* @__PURE__ */ new Set([
  "intent_confirmed",
  "intent_rejected",
  "scope_unclear",
  "risk_not_accepted"
]);
function canonicalG1(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalG1).join(",")}]`;
  if (value && typeof value === "object")
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalG1(value[key])}`).join(",")}}`;
  return JSON.stringify(value);
}
function computeG1EvidenceSetHash(events, runId, attempt = 1, policyVersion = G1_POLICY_VERSION) {
  const evidence = events.filter(
    (event) => event.event === "run_started" && event.runId === runId || event.event === "story_run_created" && event.parentRunId === runId || event.event === "gate_started" && event.runId === runId && event.gate === "G1" && event.attempt === attempt
  );
  return `sha256:${createHash17("sha256").update(canonicalG1({ policyVersion, evidence })).digest("hex")}`;
}

// ../src/domain/forge-bmad/forge-spec.ts
import { createHash as createHash18 } from "node:crypto";
var FORGE_SPEC_SCHEMA_VERSION = 1;
var G2_POLICY_VERSION = "forge-g2-deterministic-v1";
var ORACLE_CATALOG = /* @__PURE__ */ new Set(["front.build", "front.tests", "back.build"]);
function fail5(code) {
  const error2 = new Error(code);
  error2.code = code;
  throw error2;
}
function scalar(value) {
  const trimmed = value.trim();
  if (/^(true|false)$/.test(trimmed)) return trimmed === "true";
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith('"') && trimmed.endsWith('"') || trimmed.startsWith("'") && trimmed.endsWith("'"))
    return trimmed.slice(1, -1);
  return trimmed;
}
function parseForgeSpecFrontmatter(text2) {
  const lines = text2.split("\n");
  const out = {};
  let section = null;
  let list = null;
  for (const raw of lines) {
    if (!raw.trim() || raw.trimStart().startsWith("#")) continue;
    const indent = raw.length - raw.trimStart().length;
    const line = raw.trim();
    if (indent === 0) {
      const match2 = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/);
      if (!match2) fail5("G2_FRONTMATTER_INVALID");
      const [, key, value] = match2;
      if (Object.hasOwn(out, key)) fail5("G2_FRONTMATTER_INVALID");
      if (value) {
        out[key] = scalar(value);
        section = null;
      } else {
        out[key] = {};
        section = key;
      }
      list = null;
      continue;
    }
    if (indent === 2 && section && line.match(/^([A-Za-z][A-Za-z0-9]*):\s*$/)) {
      out[section][line.slice(0, -1)] = [];
      list = out[section][line.slice(0, -1)];
      continue;
    }
    if (indent === 2 && section) {
      const match2 = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.+)$/);
      if (match2) {
        out[section][match2[1]] = scalar(match2[2]);
        list = null;
        continue;
      }
    }
    if (indent === 2 && section && line.startsWith("- ") && section === "oracles") {
      if (!Array.isArray(out.oracles)) out.oracles = [];
      out.oracles.push(scalar(line.slice(2)));
      continue;
    }
    if (indent === 4 && list && line.startsWith("- ")) {
      list.push(scalar(line.slice(2)));
      continue;
    }
    fail5("G2_FRONTMATTER_INVALID");
  }
  return out;
}
function validatePattern(pattern) {
  if (typeof pattern !== "string" || !pattern || pattern.includes("\\") || pattern.startsWith("/") || pattern.includes("..") || pattern.includes("//"))
    fail5("G2_SCOPE_PATTERN_INVALID");
  const parts = pattern.split("/");
  if (parts.some((part) => !part || part !== "*" && part !== "**" && !/^[A-Za-z0-9._@-]+$/.test(part)))
    fail5("G2_SCOPE_PATTERN_INVALID");
  if (parts.includes("**") && parts.at(-1) !== "**") fail5("G2_SCOPE_PATTERN_INVALID");
}
function validateForgeSpecSchema(data, workItem) {
  if (data.schemaVersion !== FORGE_SPEC_SCHEMA_VERSION) fail5("G2_SPEC_SCHEMA_UNSUPPORTED");
  if (!data.workItem || data.workItem.id !== workItem.id || data.workItem.kind !== workItem.kind)
    fail5("G2_WORK_ITEM_MISMATCH");
  if (!data.scope || typeof data.scope !== "object") fail5("G2_SCOPE_INVALID");
  for (const key of ["allow", "create", "deny"]) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail5("G2_SCOPE_INVALID");
    data.scope[key].forEach(validatePattern);
  }
  if (!Array.isArray(data.oracles) || data.oracles.some((oracle) => typeof oracle !== "string" || !ORACLE_CATALOG.has(oracle)))
    fail5("G2_ORACLE_UNKNOWN");
  if (Object.keys(data).some((key) => !["schemaVersion", "workItem", "scope", "oracles"].includes(key)))
    fail5("G2_FRONTMATTER_INVALID");
}
function computeForgeSpecHash(content) {
  return `sha256:${createHash18("sha256").update(content).digest("hex")}`;
}
var FORGE_SPEC_FRONTMATTER_PATTERN = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/;

// ../src/domain/forge-bmad/forge-story-spec.ts
import { createHash as createHash19 } from "node:crypto";
var FORGE_STORY_SPEC_SCHEMA_VERSION = 1;
var G2_US_POLICY_VERSION = "forge-g2-us-deterministic-v1";
var STORY_SPEC_ALLOWED_KEYS = /* @__PURE__ */ new Set([
  "schemaVersion",
  "workItem",
  "scope",
  "oracles",
  "acceptanceCriteria",
  "impacts"
]);
function fail6(code, detail) {
  const error2 = new Error(detail ?? code);
  error2.code = code;
  throw error2;
}
function scalar2(value) {
  const trimmed = value.trim();
  if (/^(true|false)$/.test(trimmed)) return trimmed === "true";
  if (/^\d+$/.test(trimmed)) return Number(trimmed);
  if (trimmed.startsWith('"') && trimmed.endsWith('"') || trimmed.startsWith("'") && trimmed.endsWith("'"))
    return trimmed.slice(1, -1);
  return trimmed;
}
function parseStorySpecFrontmatter(text2) {
  const lines = text2.split("\n");
  const out = {};
  let section = null;
  let list = null;
  for (const raw of lines) {
    if (!raw.trim() || raw.trimStart().startsWith("#")) continue;
    const indent = raw.length - raw.trimStart().length;
    const line = raw.trim();
    if (indent === 0) {
      const match2 = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/);
      if (!match2) fail6("G2_FRONTMATTER_INVALID");
      const [, key, value] = match2;
      if (Object.hasOwn(out, key)) fail6("G2_FRONTMATTER_INVALID");
      if (value) {
        out[key] = scalar2(value);
        section = null;
      } else {
        out[key] = {};
        section = key;
      }
      list = null;
      continue;
    }
    if (indent === 2 && section && line.match(/^([A-Za-z][A-Za-z0-9]*):\s*$/)) {
      out[section][line.slice(0, -1)] = [];
      list = out[section][line.slice(0, -1)];
      continue;
    }
    if (indent === 2 && section) {
      const match2 = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.+)$/);
      if (match2) {
        out[section][match2[1]] = scalar2(match2[2]);
        list = null;
        continue;
      }
    }
    if (indent === 2 && section && line.startsWith("- ")) {
      if (!Array.isArray(out[section])) out[section] = [];
      out[section].push(scalar2(line.slice(2)));
      continue;
    }
    if (indent === 4 && list && line.startsWith("- ")) {
      list.push(scalar2(line.slice(2)));
      continue;
    }
    fail6("G2_FRONTMATTER_INVALID");
  }
  return out;
}
function validateStorySpec(data) {
  if (data.schemaVersion !== FORGE_STORY_SPEC_SCHEMA_VERSION) fail6("G2_US_SPEC_SCHEMA_UNSUPPORTED");
  for (const key of Object.keys(data)) {
    if (!STORY_SPEC_ALLOWED_KEYS.has(key)) fail6("G2_FRONTMATTER_INVALID", `unexpected key: ${key}`);
  }
  if (!data.workItem || typeof data.workItem !== "object") fail6("G2_US_WORK_ITEM_KIND_INVALID");
  if (data.workItem.kind !== "Story") fail6("G2_US_WORK_ITEM_KIND_INVALID");
  if (typeof data.workItem.id !== "string" || !data.workItem.id) fail6("G2_FRONTMATTER_INVALID");
  if (typeof data.workItem.parentId !== "string" || !data.workItem.parentId) fail6("G2_US_PARENT_ID_MISSING");
  if (!data.scope || typeof data.scope !== "object") fail6("G2_SCOPE_INVALID");
  for (const key of ["allow", "create", "deny"]) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail6("G2_SCOPE_INVALID");
  }
  if (data.oracles !== void 0 && !Array.isArray(data.oracles)) fail6("G2_FRONTMATTER_INVALID");
  for (const key of ["acceptanceCriteria", "impacts"]) {
    if (data[key] !== void 0) {
      if (!Array.isArray(data[key])) fail6("G2_FRONTMATTER_INVALID");
    }
  }
}
function validateInheritance(storySpec, epicSpec) {
  const violations = [];
  const epicAllow = new Set(epicSpec.scope?.allow ?? []);
  const epicCreate = new Set(epicSpec.scope?.create ?? []);
  const epicDeny = new Set(epicSpec.scope?.deny ?? []);
  const epicOracles = new Set(epicSpec.oracles ?? []);
  for (const pattern of storySpec.scope?.allow ?? []) {
    if (!epicAllow.has(pattern)) {
      violations.push({ code: "G2_US_ALLOW_EXCEEDS_EPIC", detail: `allow pattern "${pattern}" not in Epic allow set` });
    }
  }
  for (const pattern of storySpec.scope?.create ?? []) {
    if (!epicCreate.has(pattern)) {
      violations.push({
        code: "G2_US_CREATE_EXCEEDS_EPIC",
        detail: `create pattern "${pattern}" not in Epic create set`
      });
    }
  }
  const storyDeny = new Set(storySpec.scope?.deny ?? []);
  for (const pattern of epicDeny) {
    if (!storyDeny.has(pattern)) {
      violations.push({
        code: "G2_US_DENY_WEAKER_THAN_EPIC",
        detail: `Epic deny pattern "${pattern}" missing from Story deny set`
      });
    }
  }
  for (const oracle of storySpec.oracles ?? []) {
    if (!epicOracles.has(oracle)) {
      violations.push({
        code: "G2_US_ORACLE_UNKNOWN_IN_EPIC",
        detail: `oracle "${oracle}" not declared in Epic oracles`
      });
    }
  }
  return { valid: violations.length === 0, violations };
}
function computeStorySpecHash(content) {
  return `sha256:${createHash19("sha256").update(content).digest("hex")}`;
}

// ../src/domain/forge-bmad/forge-bmad-parser.ts
function toLines(raw) {
  return raw.replace(/\r\n/g, "\n").split("\n");
}
function parseScalar(raw) {
  const v = raw.trim();
  if (v === "" || v === "null" || v === "~") return null;
  if (v.startsWith('"') && v.endsWith('"')) return v.slice(1, -1);
  if (v.startsWith("'") && v.endsWith("'")) return v.slice(1, -1);
  return v;
}
function indentOf(line) {
  return line.length - line.trimStart().length;
}
function parseBlock(lines, start, indent) {
  const obj = {};
  let i = start;
  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trimStart();
    if (trimmed === "" || trimmed.startsWith("#")) {
      i++;
      continue;
    }
    const currentIndent = indentOf(line);
    if (currentIndent < indent) break;
    if (currentIndent > indent) {
      i++;
      continue;
    }
    const colonIdx = trimmed.indexOf(":");
    if (colonIdx < 0) {
      i++;
      continue;
    }
    const key = trimmed.slice(0, colonIdx).trim();
    const rest = trimmed.slice(colonIdx + 1);
    let nextMeaningful = i + 1;
    while (nextMeaningful < lines.length && lines[nextMeaningful].trim() === "") nextMeaningful++;
    const hasSubBlock = nextMeaningful < lines.length && lines[nextMeaningful].trim() !== "" && !lines[nextMeaningful].trimStart().startsWith("#") && indentOf(lines[nextMeaningful]) > indent;
    if (hasSubBlock && rest.trim() === "") {
      const { obj: subObj, nextIndex } = parseBlock(lines, i + 1, indentOf(lines[nextMeaningful]));
      obj[key] = subObj;
      i = nextIndex;
    } else {
      obj[key] = parseScalar(rest);
      i++;
    }
  }
  return { obj, nextIndex: i };
}
function parseYamlMinimal(content) {
  const lines = toLines(content);
  const { obj } = parseBlock(lines, 0, 0);
  return obj;
}
function extractFrontmatter(content) {
  const lines = toLines(content);
  if (lines[0]?.trim() !== "---") return null;
  const end = lines.findIndex((l, i) => i > 0 && l.trim() === "---");
  if (end < 0) return null;
  return lines.slice(1, end).join("\n");
}
function str(obj, key) {
  if (!obj || typeof obj !== "object") return null;
  const v = obj[key];
  if (v === null || v === void 0) return null;
  if (typeof v === "string") return v;
  return String(v);
}
function sub(obj, key) {
  if (!obj || typeof obj !== "object") return null;
  const v = obj[key];
  if (!v || typeof v !== "object") return null;
  return v;
}
function normalizeForgeRunYaml(raw, ticketId) {
  const g12 = sub(raw, "gate_1");
  const g22 = sub(raw, "gate_2");
  const g3 = sub(raw, "gate_3");
  const g4 = sub(raw, "gate_4");
  const outcome = sub(raw, "run_outcome");
  return {
    forgeRunId: str(raw, "forge_run_id"),
    ticketId: str(raw, "ticket_id") ?? ticketId,
    ticketSummary: str(raw, "ticket_summary"),
    workstream: str(raw, "workstream"),
    gates: {
      gate_1: {
        startedAt: str(g12, "started_at"),
        decidedAt: str(g12, "decided_at"),
        humanDecision: str(g12, "human_decision"),
        reviewVerdict: str(sub(g12, "review"), "verdict")
      },
      gate_2: {
        startedAt: str(g22, "started_at"),
        decidedAt: str(g22, "decided_at"),
        humanDecision: str(g22, "human_decision"),
        reviewVerdict: str(sub(g22, "review"), "verdict"),
        specFile: str(g22, "spec_file"),
        branch: str(g22, "branch")
      },
      gate_3: {
        startedAt: str(g3, "started_at"),
        decidedAt: str(g3, "decided_at"),
        humanDecision: str(g3, "human_decision"),
        reviewVerdict: str(sub(g3, "review"), "verdict"),
        prLink: str(g3, "pr_link")
      },
      gate_4: {
        startedAt: str(g4, "started_at"),
        decidedAt: str(g4, "decided_at"),
        humanDecision: str(g4, "human_decision")
      }
    },
    runOutcome: {
      status: str(outcome, "status") ?? "in-progress",
      branch: str(outcome, "branch"),
      prLink: str(outcome, "pr_link")
    }
  };
}
function normalizeStoryFrontmatterFields(parsed) {
  return {
    status: str(parsed, "status"),
    jira: str(parsed, "jira"),
    jiraEpic: str(parsed, "jira-epic"),
    forgeGate: str(parsed, "forge_gate"),
    title: str(parsed, "title"),
    type: str(parsed, "type"),
    created: str(parsed, "created")
  };
}
function normalizeSprintStatus(raw) {
  const devStatusRaw = sub(raw, "development_status");
  const developmentStatus = /* @__PURE__ */ new Map();
  if (devStatusRaw) {
    for (const [k, v] of Object.entries(devStatusRaw)) {
      if (typeof v === "string") developmentStatus.set(k, v);
      else if (v === null) developmentStatus.set(k, "unknown");
    }
  }
  return {
    developmentStatus,
    epicJira: str(raw, "epic_jira"),
    sprintGate: str(raw, "sprint_gate")
  };
}
var STRICT_GATE_FIELDS = ["started_at", "decided_at", "human_decision"];
function validateStrictForgeYamlSyntax(content) {
  const meaningful = toLines(content).map((line, index) => ({ line, index })).filter(({ line }) => line.trim() && !line.trimStart().startsWith("#") && line.trim() !== "---");
  for (let position = 0; position < meaningful.length; position++) {
    const { line } = meaningful[position];
    if (line.includes("	")) return false;
    const indent = indentOf(line);
    if (indent % 2 !== 0) return false;
    const text2 = line.trim();
    if (text2.startsWith("- ") || text2.startsWith("[") || text2.startsWith("{")) return false;
    let quote = null;
    let colon = -1;
    for (let i = 0; i < text2.length; i++) {
      const char = text2[i];
      if (quote) {
        if (char === quote && (quote === "'" || text2[i - 1] !== "\\")) quote = null;
      } else if (char === "'" || char === '"') quote = char;
      else if (char === ":") {
        colon = i;
        break;
      }
    }
    if (quote || colon <= 0 || !/^[A-Za-z0-9_-]+$/.test(text2.slice(0, colon).trim())) return false;
    const rawValue = text2.slice(colon + 1).trim();
    if (rawValue.startsWith("|") || rawValue.startsWith(">") || rawValue.startsWith("[") || rawValue.startsWith("{"))
      return false;
    if (rawValue) {
      const first = rawValue[0];
      if (first === "'" || first === '"') {
        let closedAt = -1;
        for (let i = 1; i < rawValue.length; i++) {
          if (rawValue[i] === first && (first === "'" || rawValue[i - 1] !== "\\")) {
            closedAt = i;
            break;
          }
        }
        if (closedAt < 0 || !/^\s*(?:#.*)?$/.test(rawValue.slice(closedAt + 1))) return false;
      }
    } else {
      const next = meaningful[position + 1]?.line;
      if (!next || indentOf(next) <= indent) return false;
    }
  }
  return meaningful.length > 0;
}
function validateForgeRunStructure(raw, ticketId) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).length === 0)
    return { ok: false, error: { code: "FORGE_RUN_TRUNCATED" } };
  if (!Object.hasOwn(raw, "ticket_id") || typeof raw.ticket_id !== "string" || raw.ticket_id !== ticketId)
    return { ok: false, error: { code: "FORGE_TICKET_MISMATCH" } };
  const outcome = raw.run_outcome;
  if (!outcome || typeof outcome !== "object" || Array.isArray(outcome) || !Object.hasOwn(outcome, "status") || typeof outcome.status !== "string" || !["in-progress", "completed", "abandoned"].includes(outcome.status)) {
    return { ok: false, error: { code: "INVALID_FORGE_RUN_STRUCTURE", path: "run_outcome.status" } };
  }
  for (let number = 1; number <= 4; number++) {
    const key = `gate_${number}`;
    if (!Object.hasOwn(raw, key)) continue;
    const gate2 = raw[key];
    if (!gate2 || typeof gate2 !== "object" || Array.isArray(gate2))
      return { ok: false, error: { code: "INVALID_FORGE_RUN_STRUCTURE", path: key } };
    for (const field of STRICT_GATE_FIELDS) {
      if (!Object.hasOwn(gate2, field) || gate2[field] !== null && typeof gate2[field] !== "string")
        return { ok: false, error: { code: "INVALID_FORGE_RUN_STRUCTURE", path: `${key}.${field}` } };
    }
  }
  return { ok: true };
}

// ../src/domain/forge-bmad/forge-ledger.ts
var FORGE_LEDGER_SCHEMA_VERSION = 1;
var FORGE_WORKFLOW_VERSION = "forge-epic-v1";
function parseForgeLedgerLines(raw) {
  return raw.split("\n").filter(Boolean).map((line, index) => {
    let event;
    try {
      event = JSON.parse(line);
    } catch {
      throw new Error(`invalid JSONL at line ${index + 1}`);
    }
    if (event.schemaVersion !== FORGE_LEDGER_SCHEMA_VERSION)
      throw new Error(`unsupported forge ledger schema at line ${index + 1}`);
    return event;
  });
}
function projectForgeRun(events) {
  const start = events.find((event) => event.event === "run_started" && event.runType === "EpicRun");
  if (!start) return null;
  const storyEvents = events.filter((event) => event.event === "story_run_created" && event.parentRunId === start.runId).sort((a, b) => a.ordinal - b.ordinal);
  const g12 = events.filter((event) => event.event === "gate_started" && event.runId === start.runId && event.gate === "G1").at(-1);
  const decision = g12 && events.find(
    (event) => event.event === "human_decision_recorded" && event.runId === start.runId && event.gate === "G1" && event.attempt === g12.attempt
  );
  const evidenceSetHash = g12 ? computeG1EvidenceSetHash(events, start.runId, g12.attempt, g12.policyVersion) : null;
  const g1Status2 = decision ? decision.decision.outcome : g12?.status ?? "not_started";
  const g22 = events.filter((event) => event.event === "g2_evaluated" && event.runId === start.runId).at(-1);
  const validations = new Map(
    events.filter((event) => event.event === "story_analysis_plan_validated").map((event) => [event.executionId, event])
  );
  const oracleCampaignsByStory = /* @__PURE__ */ new Map();
  for (const gate2 of events.filter((event) => event.event === "story_g3_evaluated")) {
    const results = events.filter((event) => event.event === "story_oracle_finished" && event.campaignId === gate2.campaignId).map((event) => ({
      name: event.name,
      status: event.status,
      code: event.code,
      ownerProjects: event.ownerProjects ?? [],
      target: event.target ?? null,
      buildHosts: event.buildHosts ?? [],
      ownersWithTestTarget: event.ownersWithTestTarget ?? [],
      ownersWithoutTestTarget: event.ownersWithoutTestTarget ?? [],
      exitCode: event.exitCode,
      durationMs: event.durationMs,
      commandHash: event.commandHash
    }));
    const list = oracleCampaignsByStory.get(gate2.storyRunId) ?? [];
    list.push({
      campaignId: gate2.campaignId,
      editId: gate2.editId,
      status: gate2.status,
      specHash: gate2.specHash,
      policyVersion: gate2.policyVersion,
      results
    });
    oracleCampaignsByStory.set(gate2.storyRunId, list);
  }
  const editsByStory = /* @__PURE__ */ new Map();
  for (const edit of events.filter((event) => event.event === "story_edit_finished")) {
    const list = editsByStory.get(edit.storyRunId) ?? [];
    list.push({
      editId: edit.editId,
      status: edit.status,
      outcome: edit.outcome,
      caseId: edit.caseId,
      diffValidation: edit.diffValidation,
      filesModified: edit.filesModified,
      filesCreated: edit.filesCreated
    });
    editsByStory.set(edit.storyRunId, list);
  }
  const g2usByStory = /* @__PURE__ */ new Map();
  for (const ev of events.filter((event) => event.event === "g2_us_evaluated")) {
    g2usByStory.set(ev.storyRunId, ev);
  }
  const executions = events.filter((event) => event.event === "agent_execution_finished");
  const executionsByStory = /* @__PURE__ */ new Map();
  for (const execution2 of executions) {
    const list = executionsByStory.get(execution2.storyRunId) ?? [];
    const validation = validations.get(execution2.executionId);
    list.push({
      executionId: execution2.executionId,
      caseId: execution2.caseId,
      runtime: execution2.runtime,
      role: execution2.role,
      agentName: execution2.agentName,
      namespaceId: execution2.namespaceId,
      status: execution2.status,
      outcome: execution2.outcome,
      caseStatus: execution2.caseStatus ?? null,
      killedByBudget: execution2.killedByBudget === true,
      artifact: execution2.artifact ?? null,
      analysisValidation: validation ? { schemaVersion: validation.planSchemaVersion, status: validation.status, code: validation.code } : null,
      observedAt: execution2.observedAt
    });
    executionsByStory.set(execution2.storyRunId, list);
  }
  return {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    runId: start.runId,
    runType: start.runType,
    workflow: start.workflow,
    workItem: start.workItem,
    roots: start.roots,
    startedAt: start.at,
    status: g1Status2,
    gates: [
      ...g12 ? [
        {
          gate: "G1",
          attempt: g12.attempt,
          status: g1Status2,
          requiredDecision: g12.requiredDecision,
          policyVersion: g12.policyVersion,
          evidenceSetHash,
          decision: decision?.decision ?? null
        }
      ] : [],
      ...g22 ? [
        {
          gate: "G2",
          attempt: g22.attempt,
          status: g22.status,
          code: g22.code,
          policyVersion: g22.policyVersion,
          spec: g22.spec ?? null
        }
      ] : []
    ],
    stories: storyEvents.map((event) => {
      const executions2 = executionsByStory.get(event.runId) ?? [];
      const edits = editsByStory.get(event.runId) ?? [];
      const oracleCampaigns = oracleCampaignsByStory.get(event.runId) ?? [];
      const g2usEvent = g2usByStory.get(event.runId) ?? null;
      const storyG2 = g2usEvent ? {
        gate: "G2-US",
        attempt: g2usEvent.attempt,
        status: g2usEvent.status,
        code: g2usEvent.code,
        policyVersion: g2usEvent.policyVersion,
        storySpec: g2usEvent.storySpec ?? null
      } : null;
      const latestCampaign = oracleCampaigns.at(-1);
      const latestEdit = edits.at(-1);
      const latestExecution = executions2.at(-1);
      const status = latestCampaign?.status ?? latestEdit?.status ?? latestExecution?.status ?? "not_started";
      return {
        runId: event.runId,
        ordinal: event.ordinal,
        status,
        workItem: event.workItem,
        executions: executions2,
        edits,
        oracleCampaigns,
        storyG2
      };
    })
  };
}

// ../lib/workflow-projection.mjs
import { createHash as createHash20 } from "node:crypto";
var WORKFLOW_STATUSES2 = Object.freeze([
  "pending",
  "ready",
  "running",
  "waiting_human",
  "blocked",
  "completed",
  "failed",
  "cancelled"
]);
var WORKFLOW_RESPONSIBILITY_KINDS = Object.freeze(["human", "agent", "code"]);
var WORKFLOW_PROJECTION_LIMITS = Object.freeze({
  id: 128,
  text: 256,
  description: 4096,
  steps: 500,
  dependenciesPerStep: 100,
  actorName: 256
});
var WORKFLOW_PROJECTION_ERROR_CODES = Object.freeze({
  INVALID_PROJECTION: "INVALID_PROJECTION",
  INVALID_SCHEMA_VERSION: "INVALID_SCHEMA_VERSION",
  INVALID_ID: "INVALID_ID",
  INVALID_VALUE: "INVALID_VALUE",
  INVALID_STATUS: "INVALID_STATUS",
  EXCESSIVE_SIZE: "EXCESSIVE_SIZE",
  DUPLICATE_STEP_ID: "DUPLICATE_STEP_ID",
  MISSING_DEPENDENCY: "MISSING_DEPENDENCY",
  SELF_DEPENDENCY: "SELF_DEPENDENCY",
  DEPENDENCY_CYCLE: "DEPENDENCY_CYCLE"
});
var SAFE_ID7 = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/;
var STATUS_SET = new Set(WORKFLOW_STATUSES2);
var ACTOR_KIND_SET = new Set(WORKFLOW_RESPONSIBILITY_KINDS);
var PROJECTION_FIELDS = /* @__PURE__ */ new Set([
  "schemaVersion",
  "workflowId",
  "workflowType",
  "title",
  "status",
  "expectedRevision",
  "steps"
]);
var STEP_V1_FIELDS = /* @__PURE__ */ new Set(["id", "name", "status", "description", "dependsOn"]);
var STEP_V2_FIELDS = /* @__PURE__ */ new Set([...STEP_V1_FIELDS, "responsibility"]);
var RESPONSIBILITY_FIELDS2 = /* @__PURE__ */ new Set(["kind", "name"]);
function failure2(code, path, details = {}) {
  return { ok: false, error: { code, path, details } };
}
function boundedString(value, maximum, path, { safe: safe2 = false, optional = false } = {}) {
  if (optional && value === void 0) return { ok: true, value: void 0 };
  if (typeof value !== "string" || !value.trim())
    return failure2(
      safe2 ? WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID : WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE,
      path
    );
  if (value.length > maximum) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, path, { maximum });
  if (safe2 && !SAFE_ID7.test(value)) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID, path);
  return { ok: true, value };
}
function validateWorkflowProjection(input) {
  if (!input || typeof input !== "object" || Array.isArray(input))
    return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_PROJECTION, "$");
  if (input.schemaVersion !== "1" && input.schemaVersion !== "2")
    return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_SCHEMA_VERSION, "schemaVersion");
  if (Object.keys(input).some((key) => !PROJECTION_FIELDS.has(key)))
    return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, "$", { reason: "unknown_field" });
  const schemaVersion = input.schemaVersion;
  for (const [field, limit, safe2] of [
    ["workflowId", WORKFLOW_PROJECTION_LIMITS.id, true],
    ["workflowType", WORKFLOW_PROJECTION_LIMITS.text, false],
    ["title", WORKFLOW_PROJECTION_LIMITS.text, false]
  ]) {
    const result = boundedString(input[field], limit, field, { safe: safe2 });
    if (!result.ok) return result;
  }
  if (!STATUS_SET.has(input.status)) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_STATUS, "status");
  if (input.expectedRevision !== void 0 && (!Number.isSafeInteger(input.expectedRevision) || input.expectedRevision < 0))
    return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, "expectedRevision");
  if (!Array.isArray(input.steps)) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, "steps");
  if (input.steps.length > WORKFLOW_PROJECTION_LIMITS.steps)
    return failure2(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, "steps", {
      maximum: WORKFLOW_PROJECTION_LIMITS.steps
    });
  const steps = [];
  const ids = /* @__PURE__ */ new Set();
  for (let index = 0; index < input.steps.length; index++) {
    const raw = input.steps[index];
    const base = `steps[${index}]`;
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, base);
    const allowed = schemaVersion === "2" ? STEP_V2_FIELDS : STEP_V1_FIELDS;
    if (Object.keys(raw).some((key) => !allowed.has(key)))
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, base, { reason: "unknown_field" });
    const id2 = boundedString(raw.id, WORKFLOW_PROJECTION_LIMITS.id, `${base}.id`, { safe: true });
    if (!id2.ok) return id2;
    if (ids.has(raw.id))
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: raw.id });
    ids.add(raw.id);
    const name = boundedString(raw.name, WORKFLOW_PROJECTION_LIMITS.text, `${base}.name`);
    if (!name.ok) return name;
    if (!STATUS_SET.has(raw.status)) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_STATUS, `${base}.status`);
    const description = boundedString(raw.description, WORKFLOW_PROJECTION_LIMITS.description, `${base}.description`, {
      optional: true
    });
    if (!description.ok) return description;
    if (raw.dependsOn !== void 0 && !Array.isArray(raw.dependsOn))
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn`);
    const dependencies = raw.dependsOn ?? [];
    if (dependencies.length > WORKFLOW_PROJECTION_LIMITS.dependenciesPerStep)
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.EXCESSIVE_SIZE, `${base}.dependsOn`, {
        maximum: WORKFLOW_PROJECTION_LIMITS.dependenciesPerStep
      });
    const seen = /* @__PURE__ */ new Set();
    for (let i = 0; i < dependencies.length; i++) {
      const dependency = boundedString(dependencies[i], WORKFLOW_PROJECTION_LIMITS.id, `${base}.dependsOn[${i}]`, {
        safe: true
      });
      if (!dependency.ok) return dependency;
      if (seen.has(dependency.value))
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.dependsOn[${i}]`, {
          reason: "duplicate_dependency"
        });
      seen.add(dependency.value);
    }
    let responsibility;
    if (schemaVersion === "2") {
      const actor = raw.responsibility;
      if (!actor || typeof actor !== "object" || Array.isArray(actor))
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility`);
      if (Object.keys(actor).some((key) => !RESPONSIBILITY_FIELDS2.has(key)))
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility`, {
          reason: "unknown_field"
        });
      if (!ACTOR_KIND_SET.has(actor.kind))
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_VALUE, `${base}.responsibility.kind`);
      const actorName = boundedString(actor.name, WORKFLOW_PROJECTION_LIMITS.actorName, `${base}.responsibility.name`, {
        optional: true
      });
      if (!actorName.ok) return actorName;
      responsibility = { kind: actor.kind, ...actorName.value === void 0 ? {} : { name: actorName.value } };
    }
    steps.push({
      id: raw.id,
      name: raw.name,
      status: raw.status,
      ...raw.description === void 0 ? {} : { description: raw.description },
      ...raw.dependsOn === void 0 ? {} : { dependsOn: [...dependencies] },
      ...responsibility ? { responsibility } : {}
    });
  }
  for (const step of steps)
    for (const target of step.dependsOn ?? []) {
      if (target === step.id)
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.SELF_DEPENDENCY, `steps.${step.id}.dependsOn`, {
          stepId: step.id
        });
      if (!ids.has(target))
        return failure2(WORKFLOW_PROJECTION_ERROR_CODES.MISSING_DEPENDENCY, `steps.${step.id}.dependsOn`, {
          stepId: step.id,
          target
        });
    }
  const graph = new Map(steps.map((step) => [step.id, step.dependsOn ?? []])), visiting = /* @__PURE__ */ new Set(), visited = /* @__PURE__ */ new Set();
  function hasCycle(id2) {
    if (visiting.has(id2)) return true;
    if (visited.has(id2)) return false;
    visiting.add(id2);
    for (const target of graph.get(id2)) if (hasCycle(target)) return true;
    visiting.delete(id2);
    visited.add(id2);
    return false;
  }
  for (const step of steps)
    if (hasCycle(step.id))
      return failure2(WORKFLOW_PROJECTION_ERROR_CODES.DEPENDENCY_CYCLE, "steps", { stepId: step.id });
  return {
    ok: true,
    projection: {
      schemaVersion,
      workflowId: input.workflowId,
      workflowType: input.workflowType,
      title: input.title,
      status: input.status,
      steps
    },
    expectedRevision: input.expectedRevision
  };
}

// ../src/domain/forge-bmad/forge-workflow-adapter.ts
var FORGE_WORKFLOW_ERROR_CODES = Object.freeze({
  INVALID_RUN: "INVALID_FORGE_RUN",
  UNKNOWN_DECISION: "UNKNOWN_FORGE_DECISION",
  UNKNOWN_OUTCOME: "UNKNOWN_FORGE_OUTCOME",
  IMPOSSIBLE_GATE_ORDER: "IMPOSSIBLE_FORGE_GATE_ORDER",
  INVALID_PROJECTION: "INVALID_FORGE_PROJECTION"
});
var DECISIONS = /* @__PURE__ */ new Map([
  ["approved", "completed"],
  ["approved-with-changes", "completed"],
  ["rejected", "failed"]
]);
var GATES = Object.freeze([
  ["gate_1", "gate-1", "Ticket"],
  ["gate_2", "gate-2", "Spec"],
  ["gate_3", "gate-3", "Tech Review"],
  ["gate_4", "gate-4", "Func Review"]
]);
var TICKET = /^[A-Z][A-Z0-9]+-\d+$/;
function failure3(code, path, details = {}) {
  return { ok: false, error: { code, path, details } };
}
function validInstant(value) {
  return typeof value === "string" && value.trim().length > 0 && !Number.isNaN(Date.parse(value));
}
function gateStatus(gate2, path) {
  if (!gate2 || typeof gate2 !== "object") return { ok: true, status: "pending", started: false, terminal: false };
  if (gate2.startedAt !== null && gate2.startedAt !== void 0 && !validInstant(gate2.startedAt))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.startedAt`, { reason: "invalid_timestamp" });
  if (gate2.decidedAt !== null && gate2.decidedAt !== void 0 && !validInstant(gate2.decidedAt))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: "invalid_timestamp" });
  if (gate2.decidedAt && !gate2.startedAt)
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: "decided_without_start" });
  if (gate2.startedAt && gate2.decidedAt && Date.parse(gate2.decidedAt) < Date.parse(gate2.startedAt))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, { reason: "decision_before_start" });
  const decision = gate2.humanDecision;
  if (decision !== null && decision !== void 0) {
    const status = DECISIONS.get(decision);
    if (!status)
      return failure3(FORGE_WORKFLOW_ERROR_CODES.UNKNOWN_DECISION, `${path}.humanDecision`, { value: decision });
    if (!gate2.startedAt || !gate2.decidedAt)
      return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, path, { reason: "decision_without_complete_timestamps" });
    return { ok: true, status, started: true, terminal: true };
  }
  if (gate2.decidedAt)
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, `${path}.decidedAt`, {
      reason: "decision_timestamp_without_decision"
    });
  return { ok: true, status: gate2.startedAt ? "running" : "pending", started: !!gate2.startedAt, terminal: false };
}
function adaptForgeRunToWorkflowProjection(run) {
  if (!run || typeof run !== "object" || !TICKET.test(run.ticketId ?? ""))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, "ticketId");
  const states = [];
  for (const [source] of GATES) {
    const state = gateStatus(run.gates?.[source], `gates.${source}`);
    if (!state.ok) return state;
    states.push(state);
  }
  for (let index = 1; index < states.length; index++) {
    if (states[index].started && states[index - 1].status !== "completed") {
      return failure3(FORGE_WORKFLOW_ERROR_CODES.IMPOSSIBLE_GATE_ORDER, `gates.${GATES[index][0]}`, {
        precedingGate: GATES[index - 1][0]
      });
    }
  }
  const outcome = run.runOutcome?.status;
  if (!["in-progress", "completed", "abandoned"].includes(outcome))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.UNKNOWN_OUTCOME, "runOutcome.status", { value: outcome });
  if (outcome === "completed" && states.some((state) => state.status !== "completed"))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, "runOutcome.status", {
      reason: "completed_before_all_gates_approved"
    });
  if (outcome === "in-progress" && states.every((state) => state.status === "completed"))
    return failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_RUN, "runOutcome.status", {
      reason: "all_gates_complete_but_run_in_progress"
    });
  let status;
  if (outcome === "completed") status = "completed";
  else if (outcome === "abandoned") status = "cancelled";
  else if (states.some((state) => state.status === "failed")) status = "failed";
  else if (states.some((state) => state.status === "running")) status = "running";
  else if (states.some((state) => state.status === "completed")) status = "ready";
  else status = "pending";
  const candidate = {
    schemaVersion: "1",
    workflowId: `forge-run-${run.ticketId}`,
    workflowType: "forge-ticket-v1",
    title: run.ticketSummary?.trim() || run.ticketId,
    status,
    steps: GATES.map(([, id2, name], index) => ({
      id: id2,
      name,
      status: outcome === "abandoned" && !states[index].terminal ? "cancelled" : states[index].status,
      dependsOn: index === 0 ? [] : [GATES[index - 1][1]]
    }))
  };
  const validated = validateWorkflowProjection(candidate);
  return validated.ok ? { ok: true, projection: validated.projection } : failure3(FORGE_WORKFLOW_ERROR_CODES.INVALID_PROJECTION, validated.error.path, { validation: validated.error });
}

// ../src/domain/forge-bmad/jira.ts
var COMMENTS_CHAR_BUDGET = 8e3;
function extractTicketId(input) {
  if (!input || typeof input !== "string") return null;
  const urlMatch = input.match(/\/browse\/([A-Z][A-Z0-9]+-\d+)/i);
  if (urlMatch) return urlMatch[1].toUpperCase();
  const idMatch = input.match(/^([A-Z][A-Z0-9]+-\d+)$/i);
  if (idMatch) return idMatch[1].toUpperCase();
  return null;
}
var BLOCK_TYPES = /* @__PURE__ */ new Set([
  "paragraph",
  "heading",
  "listItem",
  "bulletList",
  "orderedList",
  "blockquote",
  "codeBlock",
  "rule"
]);
function extractAdfText(node) {
  if (!node || typeof node !== "object") return "";
  if (node.type === "text" && typeof node.text === "string") return node.text;
  const children = node.content ?? [];
  const parts = children.map(extractAdfText);
  return BLOCK_TYPES.has(node.type) ? parts.join("") + "\n" : parts.join("");
}
function applyCommentBudget(comments, budget) {
  let remaining = budget;
  const included = [];
  for (const c of comments) {
    const size = c.author.length + c.created.length + c.body.length + 50;
    if (remaining <= 0) break;
    included.push(c);
    remaining -= size;
  }
  const omitted = comments.length - included.length;
  return { included, omitted };
}

// ../src/adapters/forge/forge-roots-resolver.ts
import { existsSync as existsSync2, mkdirSync as mkdirSync2, realpathSync as realpathSync2, statSync } from "node:fs";
import { basename, dirname as dirname7, isAbsolute as isAbsolute8, join as join13, resolve as resolve3 } from "node:path";
function resolveExistingDirectory(value, field) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${field} is required`);
  if (!isAbsolute8(value)) throw new Error(`${field} must be an absolute path`);
  try {
    const real = realpathSync2(resolve3(value));
    if (!statSync(real).isDirectory()) throw new Error("not a directory");
    return real;
  } catch {
    throw new Error(`${field} must exist as a directory and resolve without a broken symlink`);
  }
}
function resolveStoreRoot(value) {
  if (typeof value !== "string" || !value.trim()) throw new Error("roots.runStoreRoot is required");
  if (!isAbsolute8(value)) throw new Error("roots.runStoreRoot must be an absolute path");
  const requested = resolve3(value);
  const parent = resolveExistingDirectory(dirname7(requested), "roots.runStoreParent");
  const candidate = join13(parent, basename(requested));
  if (existsSync2(candidate)) return resolveExistingDirectory(candidate, "roots.runStoreRoot");
  return candidate;
}
function resolveForgeRoots(input) {
  if (!input || typeof input !== "object") throw new Error("roots object is required");
  const orchestratorRoot = resolveExistingDirectory(input.orchestratorRoot, "roots.orchestratorRoot");
  const repoRoot = resolveExistingDirectory(input.repoRoot, "roots.repoRoot");
  const forgeRoot = input.forgeRoot === void 0 ? void 0 : resolveExistingDirectory(input.forgeRoot, "roots.forgeRoot");
  const runStoreRoot = resolveStoreRoot(input.runStoreRoot);
  const runStorePolicy = input.runStorePolicy ?? DEFAULT_RUN_STORE_POLICY;
  if (!FORGE_RUN_STORE_POLICIES.includes(runStorePolicy)) {
    throw new Error(
      `roots.runStorePolicy must be ${DEFAULT_RUN_STORE_POLICY}, ${EXTERNAL_RUN_STORE_POLICY}, or ${REPO_RUN_STORE_POLICY}`
    );
  }
  const policy = runStorePolicy;
  if (policy === DEFAULT_RUN_STORE_POLICY && !isWithin(runStoreRoot, orchestratorRoot)) {
    throw new Error(
      "roots.runStoreRoot must remain under roots.orchestratorRoot unless runStorePolicy is external_allowed"
    );
  }
  if (policy === REPO_RUN_STORE_POLICY && !isWithin(runStoreRoot, repoRoot)) {
    throw new Error("roots.runStoreRoot must remain under roots.repoRoot when runStorePolicy is under_repo");
  }
  return Object.freeze({
    schemaVersion: FORGE_ROOTS_SCHEMA_VERSION,
    orchestratorRoot,
    runStoreRoot,
    repoRoot,
    ...forgeRoot ? { forgeRoot } : {},
    runStorePolicy: policy
  });
}
function ensureForgeRunStore(roots) {
  mkdirSync2(roots.runStoreRoot, { recursive: true });
  return resolveExistingDirectory(roots.runStoreRoot, "roots.runStoreRoot");
}

// ../src/adapters/forge/forge-bmad-file-reader.ts
import { existsSync as existsSync3, readFileSync as readFileSync3 } from "node:fs";
import { isAbsolute as isAbsolute9, join as join14 } from "node:path";
function readFileSafe(filePath) {
  if (!existsSync3(filePath)) return null;
  try {
    return readFileSync3(filePath, "utf8");
  } catch {
    return null;
  }
}
function readYamlFile(filePath) {
  const content = readFileSafe(filePath);
  if (content === null) return null;
  try {
    return parseYamlMinimal(content);
  } catch {
    return null;
  }
}
function readForgeRunYaml(repoRoot, ticketId) {
  const yamlPath = join14(repoRoot, "forge", "state", "forge-runs", `${ticketId}.yaml`);
  const raw = readYamlFile(yamlPath);
  if (!raw) return null;
  return normalizeForgeRunYaml(raw, ticketId);
}
function readForgeRunYamlStrict(repoRoot, ticketId) {
  const yamlPath = join14(repoRoot, "forge", "state", "forge-runs", `${ticketId}.yaml`);
  if (!existsSync3(yamlPath)) return { ok: false, error: { code: "FORGE_RUN_NOT_FOUND" } };
  let content;
  try {
    content = readFileSync3(yamlPath, "utf8");
  } catch {
    return { ok: false, error: { code: "FORGE_RUN_READ_FAILURE" } };
  }
  if (!content.trim()) return { ok: false, error: { code: "FORGE_RUN_TRUNCATED" } };
  if (!validateStrictForgeYamlSyntax(content)) return { ok: false, error: { code: "FORGE_RUN_PARSE_INVALID" } };
  let raw;
  try {
    raw = parseYamlMinimal(content);
  } catch {
    return { ok: false, error: { code: "FORGE_RUN_PARSE_INVALID" } };
  }
  const structure = validateForgeRunStructure(raw, ticketId);
  if (!structure.ok) return { ok: false, error: structure.error };
  const normalized = readForgeRunYaml(repoRoot, ticketId);
  if (!normalized) return { ok: false, error: { code: "FORGE_RUN_PARSE_INVALID" } };
  return { ok: true, run: normalized };
}
function readStoryFrontmatter(repoRoot, storePath) {
  const fullPath = isAbsolute9(storePath) ? storePath : join14(repoRoot, storePath);
  const content = readFileSafe(fullPath);
  if (content === null) return null;
  const fmRaw = extractFrontmatter(content);
  if (!fmRaw) return null;
  let parsed;
  try {
    parsed = parseYamlMinimal(fmRaw);
  } catch {
    return null;
  }
  return normalizeStoryFrontmatterFields(parsed);
}
function readSprintStatus(repoRoot, workstreamSlug) {
  const yamlPath = join14(
    repoRoot,
    "forge",
    "bmad",
    "workstreams",
    workstreamSlug,
    "implementation-artifacts",
    "sprint-status.yaml"
  );
  const raw = readYamlFile(yamlPath);
  if (!raw) return null;
  return normalizeSprintStatus(raw);
}

// ../src/adapters/forge/forge-spec-reader.ts
import { readFileSync as readFileSync4, realpathSync as realpathSync3, statSync as statSync2 } from "node:fs";
import { isAbsolute as isAbsolute10, relative as relative4, resolve as resolve4 } from "node:path";
function inside2(child, root) {
  const rel = relative4(root, child);
  return rel === "" || !rel.startsWith("..") && !isAbsolute10(rel);
}
function fail7(code) {
  const error2 = new Error(code);
  error2.code = code;
  throw error2;
}
function loadForgeSpec({
  specPath,
  roots,
  workItem
}) {
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail7("G2_SPEC_PATH_INVALID");
  let path;
  try {
    path = realpathSync3(resolve4(specPath));
    if (!statSync2(path).isFile()) fail7("G2_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail7("G2_SPEC_PATH_INVALID");
  }
  if (!inside2(path, roots.repoRoot) && !(roots.forgeRoot && inside2(path, roots.forgeRoot)))
    fail7("G2_SPEC_OUTSIDE_ROOT");
  const content = readFileSync4(path, "utf8");
  const match2 = content.match(FORGE_SPEC_FRONTMATTER_PATTERN);
  if (!match2) fail7("G2_FRONTMATTER_MISSING");
  const frontmatter = parseForgeSpecFrontmatter(match2[1]);
  validateForgeSpecSchema(frontmatter, workItem);
  return {
    path,
    sha256: computeForgeSpecHash(content),
    schemaVersion: frontmatter.schemaVersion,
    frontmatter
  };
}
function readStorySpec(specPath, roots) {
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail7("G2_US_SPEC_PATH_INVALID");
  let realPath;
  try {
    realPath = realpathSync3(resolve4(specPath));
    if (!statSync2(realPath).isFile()) fail7("G2_US_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail7("G2_US_SPEC_PATH_INVALID");
  }
  if (!inside2(realPath, roots.repoRoot) && !(roots.forgeRoot && inside2(realPath, roots.forgeRoot)))
    fail7("G2_US_SPEC_OUTSIDE_ROOT");
  const rawContent = readFileSync4(realPath, "utf8");
  const match2 = rawContent.match(FORGE_SPEC_FRONTMATTER_PATTERN);
  if (!match2) fail7("G2_FRONTMATTER_MISSING");
  const frontmatter = parseStorySpecFrontmatter(match2[1]);
  validateStorySpec(frontmatter);
  return {
    path: realPath,
    sha256: computeStorySpecHash(rawContent),
    schemaVersion: frontmatter.schemaVersion,
    frontmatter,
    rawContent
  };
}
function hashStorySpec(specPath) {
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail7("G2_US_SPEC_PATH_INVALID");
  let realPath;
  try {
    realPath = realpathSync3(resolve4(specPath));
    if (!statSync2(realPath).isFile()) fail7("G2_US_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail7("G2_US_SPEC_PATH_INVALID");
  }
  const content = readFileSync4(realPath, "utf8");
  return computeStorySpecHash(content);
}

// ../src/adapters/forge/forge-ledger-store.ts
import { appendFileSync as appendFileSync2, readdirSync, readFileSync as readFileSync5 } from "node:fs";
import { join as join15 } from "node:path";
import { randomUUID as randomUUID8 } from "node:crypto";
function assertString(value, name) {
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name} is required`);
}
function assertWorkItem(item, name) {
  if (!item || typeof item !== "object") throw new Error(`${name} is required`);
  assertString(item.id, `${name}.id`);
  assertString(item.kind, `${name}.kind`);
}
function appendForgeLedgerEvent(filePath, event) {
  appendFileSync2(filePath, `${JSON.stringify(event)}
`, "utf8");
}
function createEpicRun({
  roots,
  epic,
  stories,
  runId = `epic_${randomUUID8()}`,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  assertWorkItem(epic, "epic");
  if (!Array.isArray(stories) || stories.length === 0)
    throw new Error("stories must contain at least one explicit Story work item");
  for (const story of stories) {
    assertWorkItem(story, "story");
    if (story.kind !== "Story") throw new Error('every child work item must have kind "Story"');
  }
  const filePath = join15(ensureForgeRunStore(roots), `${runId}.jsonl`);
  const at = now();
  appendForgeLedgerEvent(filePath, {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    event: "run_started",
    runId,
    runType: "EpicRun",
    workflow: FORGE_WORKFLOW_VERSION,
    workItem: epic,
    roots,
    at
  });
  const storyRuns = stories.map((workItem, index) => {
    const storyRunId = `story_${randomUUID8()}`;
    appendForgeLedgerEvent(filePath, {
      schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
      event: "story_run_created",
      runId: storyRunId,
      parentRunId: runId,
      runType: "StoryRun",
      ordinal: index + 1,
      workItem,
      at: now()
    });
    return { runId: storyRunId, parentRunId: runId, ordinal: index + 1, workItem };
  });
  appendForgeLedgerEvent(filePath, {
    schemaVersion: FORGE_LEDGER_SCHEMA_VERSION,
    event: "gate_started",
    runId,
    gate: "G1",
    attempt: 1,
    status: "waiting_human",
    requiredDecision: "intent-approval",
    policyVersion: G1_POLICY_VERSION,
    at: now()
  });
  return { runId, filePath, storyRuns };
}
function parseForgeLedger(filePath) {
  return parseForgeLedgerLines(readFileSync5(filePath, "utf8"));
}
function listForgeRunProjections(runStoreRoot) {
  let files = [];
  try {
    files = readdirSync(runStoreRoot).filter((file) => file.endsWith(".jsonl"));
  } catch {
    return [];
  }
  return files.flatMap((file) => {
    try {
      const projection = projectForgeRun(parseForgeLedger(join15(runStoreRoot, file)));
      return projection ? [projection] : [];
    } catch {
      return [];
    }
  }).sort((a, b) => b.startedAt.localeCompare(a.startedAt));
}

// ../src/adapters/jira/jira-client.ts
function formatCommentsSection(comments, omitted) {
  const parts = comments.map((c) => {
    const date = c.created ? new Date(c.created).toISOString().slice(0, 10) : "";
    return `**${c.author}** (${date}):
${c.body}`;
  });
  let section = parts.join("\n\n---\n\n");
  if (omitted > 0) {
    section += `

*(${omitted} older comment${omitted === 1 ? "" : "s"} omitted \u2014 budget exceeded)*`;
  }
  return section;
}
async function fetchJiraComments(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken) {
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString("base64");
  const base = jiraBaseUrl.replace(/\/$/, "");
  const PAGE_SIZE = 50;
  const allComments = [];
  let startAt = 0;
  while (true) {
    const url = `${base}/rest/api/3/issue/${encodeURIComponent(ticketId)}/comment?orderBy=-created&maxResults=${PAGE_SIZE}&startAt=${startAt}`;
    const res = await fetch(url, {
      method: "GET",
      headers: {
        Authorization: `Basic ${credentials}`,
        Accept: "application/json"
      }
    });
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      throw new Error(`Jira comments API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`);
    }
    const data = await res.json();
    const comments = data.comments ?? [];
    const total = data.total ?? 0;
    for (const c of comments) {
      const authorName = c.author?.displayName ?? c.author?.emailAddress ?? c.author?.accountId ?? "Unknown";
      const created = c.created ?? "";
      let body = "";
      if (c.body) {
        if (typeof c.body === "string") {
          body = c.body;
        } else {
          body = extractAdfText(c.body).trim();
        }
      }
      allComments.push({ author: authorName, created, body });
    }
    startAt += comments.length;
    if (startAt >= total || comments.length === 0) break;
  }
  return allComments;
}
async function fetchJiraTicket(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken) {
  const url = `${jiraBaseUrl.replace(/\/$/, "")}/rest/api/3/issue/${encodeURIComponent(ticketId)}`;
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString("base64");
  const res = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Basic ${credentials}`,
      Accept: "application/json"
    }
  });
  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`Jira API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`);
  }
  const data = await res.json();
  const fields = data.fields ?? {};
  const summary = fields.summary ?? "";
  const parent = fields.parent ?? null;
  const epicKey = parent?.key ?? null;
  const epicSummary = parent?.fields?.summary ?? null;
  let description = "";
  if (fields.description) {
    if (typeof fields.description === "string") {
      description = fields.description;
    } else {
      description = extractAdfText(fields.description).trim();
    }
  }
  let acceptanceCriteria = "";
  for (const [key, value] of Object.entries(fields)) {
    if (!value) continue;
    if (key.toLowerCase().includes("acceptance") || key === "customfield_10016") {
      if (typeof value === "string") {
        acceptanceCriteria = value;
        break;
      } else if (typeof value === "object") {
        acceptanceCriteria = extractAdfText(value).trim();
        break;
      }
    }
  }
  const allComments = await fetchJiraComments(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken);
  const commentCount = allComments.length;
  const { included, omitted } = applyCommentBudget(allComments, COMMENTS_CHAR_BUDGET);
  const commentsIncluded = included.length;
  const commentsTruncated = omitted > 0;
  const sections = [`## Summary
${summary}`];
  if (description) sections.push(`## Description
${description}`);
  if (acceptanceCriteria) sections.push(`## Acceptance criteria
${acceptanceCriteria}`);
  if (included.length > 0) {
    sections.push(`## Comments
${formatCommentsSection(included, omitted)}`);
  }
  const ticketContent = sections.join("\n\n");
  const fieldCount = [summary, description, acceptanceCriteria].filter(Boolean).length;
  return {
    ticketContent,
    summary,
    epicKey,
    epicSummary,
    fieldCount,
    commentCount,
    commentsIncluded,
    commentsTruncated
  };
}

// ../src/application/forge-bmad/forge-human-decision.ts
import { randomUUID as randomUUID9 } from "node:crypto";
import { join as join16 } from "node:path";
function currentGate(events, runId) {
  return events.filter((event) => event.event === "gate_started" && event.runId === runId && event.gate === "G1").at(-1);
}
async function recordHumanDecision({
  roots,
  runId,
  decision,
  identityPort,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  if (!identityPort || typeof identityPort.authorize !== "function")
    throw new Error("an identity authorization port is required");
  if (!decision || typeof decision !== "object") throw new Error("decision is required");
  if (!G1_OUTCOMES.has(decision.outcome)) throw new Error("decision.outcome must be approved or rejected");
  if (!G1_REASON_CODES.has(decision.reasonCode)) throw new Error("decision.reasonCode is invalid");
  if (decision.actorId !== void 0 || decision.actorRole !== void 0)
    throw new Error("actor identity and role must not be declared by the decision payload");
  const filePath = join16(ensureForgeRunStore(roots), `${runId}.jsonl`);
  const events = parseForgeLedger(filePath);
  const gate2 = currentGate(events, runId);
  if (!gate2 || gate2.status !== "waiting_human") throw new Error("G1 is not waiting for a human decision");
  if (decision.gate !== "G1" || decision.attempt !== gate2.attempt || decision.policyVersion !== G1_POLICY_VERSION)
    throw new Error("decision does not match the active G1 attempt or policy");
  const evidenceSetHash = computeG1EvidenceSetHash(events, runId, gate2.attempt, G1_POLICY_VERSION);
  if (decision.evidenceSetHash !== evidenceSetHash) throw new Error("decision evidenceSetHash is stale or invalid");
  const actorId = await identityPort.actorId();
  if (typeof actorId !== "string" || !actorId) throw new Error("verified actor identity is required");
  const authority = await identityPort.authorize({
    actorId,
    runId,
    gate: "G1",
    attempt: gate2.attempt,
    policyVersion: G1_POLICY_VERSION
  });
  if (!authority || typeof authority.authorityId !== "string") throw new Error("actor is not authorized to decide G1");
  const existing = events.find(
    (event2) => event2.event === "human_decision_recorded" && event2.runId === runId && event2.gate === "G1" && event2.attempt === gate2.attempt
  );
  const fingerprint = canonicalG1({
    outcome: decision.outcome,
    reasonCode: decision.reasonCode,
    evidenceSetHash,
    actorId,
    authorityId: authority.authorityId
  });
  if (existing) {
    if (existing.idempotencyKey === fingerprint) return { status: "idempotent", event: existing };
    throw new Error("a conflicting G1 decision already exists");
  }
  const event = {
    schemaVersion: 1,
    event: "human_decision_recorded",
    decisionId: `decision_${randomUUID9()}`,
    runId,
    gate: "G1",
    attempt: gate2.attempt,
    policyVersion: G1_POLICY_VERSION,
    evidenceSetHash,
    decision: {
      actorId,
      authorityId: authority.authorityId,
      outcome: decision.outcome,
      reasonCode: decision.reasonCode
    },
    idempotencyKey: fingerprint,
    at: now()
  };
  appendForgeLedgerEvent(filePath, event);
  return { status: "recorded", event };
}

// ../src/application/forge-bmad/forge-g2.ts
import { join as join17 } from "node:path";
function gate(events, runId, name) {
  return events.filter((event) => event.event === "gate_started" && event.runId === runId && event.gate === name).at(-1);
}
function g1Status(events, runId) {
  const g12 = gate(events, runId, "G1");
  const decision = g12 && events.find(
    (event) => event.event === "human_decision_recorded" && event.runId === runId && event.gate === "G1" && event.attempt === g12.attempt
  );
  return decision?.decision.outcome ?? g12?.status ?? "missing";
}
function evaluateG2({
  roots,
  runId,
  specPath,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  const filePath = join17(ensureForgeRunStore(roots), `${runId}.jsonl`);
  const events = parseForgeLedger(filePath);
  const start = events.find((event) => event.event === "run_started" && event.runId === runId);
  if (!start) throw new Error("G2_RUN_NOT_FOUND");
  const prior = events.filter((event) => event.event === "g2_evaluated" && event.runId === runId).at(-1);
  let spec;
  try {
    spec = loadForgeSpec({ specPath, roots, workItem: start.workItem });
  } catch (error2) {
    const code = error2.code ?? "G2_SPEC_INVALID";
    return record(filePath, runId, prior, null, "blocked", code, now);
  }
  if (prior?.status === "passed" && prior.spec?.sha256 === spec.sha256 && prior.policyVersion === G2_POLICY_VERSION)
    return { status: "idempotent", event: prior };
  if (prior?.status === "passed" && prior.spec?.sha256 !== spec.sha256)
    return { status: "conflict", code: "G2_SPEC_HASH_CHANGED", event: prior };
  if (g1Status(events, runId) !== "approved")
    return record(filePath, runId, prior, spec, "blocked", "G2_G1_NOT_APPROVED", now);
  return record(filePath, runId, prior, spec, "passed", "G2_SPEC_VALID", now);
}
function record(filePath, runId, prior, spec, status, code, now) {
  const attempt = (prior?.attempt ?? 0) + 1;
  const event = {
    schemaVersion: 1,
    event: "g2_evaluated",
    runId,
    gate: "G2",
    attempt,
    status,
    code,
    policyVersion: G2_POLICY_VERSION,
    spec: spec && { path: spec.path, sha256: spec.sha256, schemaVersion: spec.schemaVersion },
    at: now()
  };
  appendForgeLedgerEvent(filePath, event);
  return { status: "recorded", event };
}
function evaluateG2US({
  roots,
  epicRunId,
  storyRunId,
  storySpecPath,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  const filePath = join17(ensureForgeRunStore(roots), `${epicRunId}.jsonl`);
  const events = parseForgeLedger(filePath);
  const prior = events.filter((e) => e.event === "g2_us_evaluated" && e.storyRunId === storyRunId).at(-1);
  const epicStart = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  if (!epicStart)
    return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_EPIC_RUN_NOT_FOUND", now);
  const storyRun = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!storyRun)
    return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_STORY_RUN_NOT_FOUND", now);
  if (g1Status(events, epicRunId) !== "approved")
    return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_G1_NOT_APPROVED", now);
  const g2EpicEvent = events.filter((e) => e.event === "g2_evaluated" && e.runId === epicRunId && e.status === "passed").at(-1);
  if (!g2EpicEvent) return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", "G2_US_G2_NOT_PASSED", now);
  let storySpec;
  try {
    storySpec = readStorySpec(storySpecPath, roots);
  } catch (error2) {
    const code = error2.code ?? "G2_US_SPEC_INVALID";
    return recordUS(filePath, epicRunId, storyRunId, null, prior, "blocked", code, now);
  }
  if (prior?.storySpec?.sha256 === storySpec.sha256 && prior.policyVersion === G2_US_POLICY_VERSION && prior.code !== "G2_US_G1_NOT_APPROVED" && prior.code !== "G2_US_G2_NOT_PASSED")
    return { status: "idempotent", event: prior };
  if (prior && prior.status === "passed" && prior.storySpec?.sha256 !== storySpec.sha256) {
    return { status: "conflict", code: "G2_US_SPEC_HASH_CHANGED", event: prior };
  }
  if (storySpec.frontmatter.workItem?.id !== storyRun.workItem?.id) {
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "blocked", "G2_US_WORK_ITEM_MISMATCH", now);
  }
  let epicSpec;
  try {
    epicSpec = loadForgeSpec({ specPath: g2EpicEvent.spec.path, roots, workItem: epicStart.workItem });
  } catch (error2) {
    const code = error2.code ?? "G2_US_SPEC_INVALID";
    return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "blocked", code, now);
  }
  const { valid, violations } = validateInheritance(storySpec.frontmatter, epicSpec.frontmatter);
  if (!valid) {
    return recordUS(
      filePath,
      epicRunId,
      storyRunId,
      storySpec,
      prior,
      "blocked",
      "G2_US_INHERITANCE_VIOLATION",
      now,
      violations
    );
  }
  return recordUS(filePath, epicRunId, storyRunId, storySpec, prior, "passed", "G2_US_SPEC_VALID", now);
}
function recordUS(filePath, epicRunId, storyRunId, storySpec, prior, status, code, now, violations) {
  const attempt = (prior?.attempt ?? 0) + 1;
  const event = {
    schemaVersion: 1,
    event: "g2_us_evaluated",
    runId: epicRunId,
    storyRunId,
    gate: "G2-US",
    attempt,
    status,
    code,
    policyVersion: G2_US_POLICY_VERSION,
    storySpec: storySpec ? { path: storySpec.path, sha256: storySpec.sha256, schemaVersion: storySpec.schemaVersion } : null,
    ...violations ? { violations } : {},
    at: now()
  };
  appendForgeLedgerEvent(filePath, event);
  return { status: "recorded", event };
}

// ../src/application/forge-bmad/forge-story-analysis.ts
import { mkdirSync as mkdirSync3, renameSync, writeFileSync as writeFileSync2 } from "node:fs";
import { createHash as createHash21, randomUUID as randomUUID10 } from "node:crypto";
import { join as join19, relative as relative5, resolve as resolve5 } from "node:path";

// ../lib/plan.mjs
import { existsSync as existsSync4 } from "node:fs";
import { join as join18, isAbsolute as isAbsolute11 } from "node:path";
function extractJsonFragment(text2) {
  const jsonFenceMatch = text2.match(/```json\s*([\s\S]*?)```/);
  if (jsonFenceMatch) return jsonFenceMatch[1].trim();
  const plainFenceMatch = text2.match(/```\s*([\s\S]*?)```/);
  if (plainFenceMatch) return plainFenceMatch[1].trim();
  const start = text2.indexOf("{");
  if (start < 0) return null;
  let depth = 0;
  for (let i = start; i < text2.length; i++) {
    if (text2[i] === "{") depth++;
    else if (text2[i] === "}") {
      depth--;
      if (depth === 0) return text2.slice(start, i + 1);
    }
  }
  return null;
}
function isSafePath(p) {
  if (typeof p !== "string") return false;
  if (isAbsolute11(p)) return false;
  if (p.split("/").includes("..")) return false;
  return true;
}
function parsePlan(agentMessage) {
  const fragment = extractJsonFragment(agentMessage);
  if (!fragment) {
    return { ok: false, error: "Aucun bloc JSON trouv\xE9 dans la r\xE9ponse de l'analyste." };
  }
  let raw;
  try {
    raw = JSON.parse(fragment);
  } catch (err) {
    return { ok: false, error: `JSON invalide : ${err.message}` };
  }
  if (!Array.isArray(raw.files) || raw.files.length === 0) {
    return { ok: false, error: 'Le plan doit contenir un champ "files" non vide.' };
  }
  for (const f of raw.files) {
    if (!isSafePath(f)) {
      return {
        ok: false,
        error: `Chemin invalide dans "files" : "${f}". Les chemins doivent \xEAtre relatifs \xE0 la racine du d\xE9p\xF4t (pas de chemin absolu, pas de "..")`
      };
    }
  }
  if (typeof raw.doneWhen !== "string" || raw.doneWhen.trim() === "") {
    return { ok: false, error: 'Le plan doit contenir un champ "doneWhen" non vide.' };
  }
  return {
    ok: true,
    plan: {
      files: raw.files,
      doneWhen: raw.doneWhen,
      ...Array.isArray(raw.steps) ? { steps: raw.steps } : {}
    }
  };
}
function checkPlanFiles(files, repoRoot) {
  const missingFiles = files.filter((f) => !existsSync4(join18(repoRoot, f)));
  return {
    plannedFiles: files,
    missingFiles,
    fileCount: files.length
  };
}

// ../src/application/forge-bmad/forge-story-analysis.ts
var AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION = 1;
var STORY_ANALYSIS_POLICY_VERSION = "forge-story-analysis-v2";
var STORY_ANALYSIS_PLAN_SCHEMA_VERSION = 1;
var MAX_FILES = 30;
var MAX_TEXT = 4e3;
function sha2562(content) {
  return `sha256:${createHash21("sha256").update(content).digest("hex")}`;
}
function g1(events, runId) {
  const gate2 = events.filter((e) => e.event === "gate_started" && e.runId === runId && e.gate === "G1").at(-1);
  return gate2 && events.find(
    (e) => e.event === "human_decision_recorded" && e.runId === runId && e.gate === "G1" && e.attempt === gate2.attempt
  )?.decision.outcome;
}
function g2(events, runId, expected) {
  const event = events.filter((e) => e.event === "g2_evaluated" && e.runId === runId && e.status === "passed").at(-1);
  return event && (!expected || event.spec?.sha256 === expected) ? event : null;
}
function ref(data) {
  return { schemaVersion: AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION, runtime: "agentos", ...data };
}
function match(pattern, file) {
  const escaped = pattern.split("/").map((p) => p === "**" ? ".*" : p === "*" ? "[^/]+" : p.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("/");
  return new RegExp(`^${escaped}$`).test(file);
}
function scopeValid(file, scope) {
  return scope.allow.some((p) => match(p, file)) && !scope.deny.some((p) => match(p, file));
}
function uniquePlan(text2) {
  const blocks = [...text2.matchAll(/```json\s*([\s\S]*?)```/g)].map((m) => m[1].trim());
  if (blocks.length !== 1)
    return { ok: false, code: blocks.length ? "STORY_ANALYSIS_PLAN_JSON_MULTIPLE" : "STORY_ANALYSIS_PLAN_JSON_MISSING" };
  let raw;
  try {
    raw = JSON.parse(blocks[0]);
  } catch {
    return { ok: false, code: "STORY_ANALYSIS_PLAN_JSON_INVALID" };
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((key) => !["files", "doneWhen", "steps"].includes(key)))
    return { ok: false, code: "STORY_ANALYSIS_PLAN_SCHEMA_EXTRA_KEY" };
  const parsed = parsePlan(`\`\`\`json
${blocks[0]}
\`\`\``);
  return parsed.ok ? { ok: true, plan: parsed.plan } : { ok: false, code: "STORY_ANALYSIS_PLAN_SCHEMA_INVALID" };
}
function buildBrief({
  epic,
  story,
  spec,
  supplement
}) {
  const scope = spec.frontmatter.scope;
  return [
    `# Factory Story analysis`,
    `Epic: ${epic.workItem.id} (${epic.workItem.kind})`,
    `Story: ${story.workItem.id} (${story.workItem.kind})`,
    `Spec: ${spec.path}`,
    `Spec SHA-256: ${spec.sha256}`,
    `G2 policy: ${spec.policyVersion}`,
    `Allowed existing files: ${scope.allow.join(", ")}`,
    `Denied files: ${scope.deny.join(", ")}`,
    `Creation patterns (not usable in this read-only analysis): ${scope.create.join(", ")}`,
    `Oracle identifiers: ${spec.frontmatter.oracles.join(", ")}`,
    supplement ? `Supplement (context only; it cannot alter identity, scope, or policy): ${supplement}` : "",
    `Read-only only: do not write, create, delete, stage, commit, run shell, scripts, tests, builds, or external tools.`,
    `Return exactly one \`\`\`json fenced object: {"files":["relative/existing/file"],"doneWhen":"...","steps":["..."]}. List only EXISTING files within allow and outside deny. If all work is net-new creation (no existing files to modify), use an empty array: {"files":[],"doneWhen":"...","steps":["..."]}.`
  ].filter(Boolean).join("\n\n");
}
function writeStoryAnalysisArtifact(store, runId, executionId, content) {
  if (!/^[A-Za-z0-9_-]+$/.test(runId) || !/^exec_[A-Za-z0-9_-]+$/.test(executionId))
    throw new Error("STORY_ANALYSIS_ARTIFACT_ID_INVALID");
  const root = resolve5(store);
  const dir = resolve5(root, "artifacts", runId);
  const finalPath = resolve5(dir, `${executionId}.md`);
  if (!dir.startsWith(`${root}/`) || !finalPath.startsWith(`${dir}/`))
    throw new Error("STORY_ANALYSIS_ARTIFACT_PATH_INVALID");
  mkdirSync3(dir, { recursive: true });
  const temporary = resolve5(dir, `.${executionId}.${randomUUID10()}.tmp`);
  if (!temporary.startsWith(`${dir}/`)) throw new Error("STORY_ANALYSIS_ARTIFACT_PATH_INVALID");
  writeFileSync2(temporary, content, { encoding: "utf8", mode: 384 });
  renameSync(temporary, finalPath);
  return {
    kind: "agent-analysis-output",
    path: relative5(root, finalPath),
    sha256: sha2562(content),
    mediaType: "text/markdown",
    schemaVersion: 1
  };
}
async function executeStoryAnalysis({
  roots,
  epicRunId,
  storyRunId,
  namespaceId,
  agentName,
  expectedSpecHash,
  storySpecHash,
  supplement,
  runtime = agentos_operations_exports,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  if (!namespaceId || !agentName) throw new Error("STORY_ANALYSIS_INPUT_INVALID");
  if (supplement !== void 0 && (typeof supplement !== "string" || supplement.length > MAX_TEXT))
    throw new Error("STORY_ANALYSIS_SUPPLEMENT_INVALID");
  const store = ensureForgeRunStore(roots);
  const filePath = join19(store, `${epicRunId}.jsonl`);
  const events = parseForgeLedger(filePath);
  const epic = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  const story = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!epic || !story) throw new Error("STORY_RUN_NOT_FOUND");
  const active = events.some(
    (e) => e.event === "agent_execution_started" && e.storyRunId === storyRunId && e.role === "analyst" && !events.some((f) => f.event === "agent_execution_finished" && f.executionId === e.executionId)
  );
  if (active) throw new Error("STORY_ANALYSIS_ALREADY_RUNNING");
  if (g1(events, epicRunId) !== "approved") throw new Error("STORY_ANALYSIS_G1_NOT_APPROVED");
  const g2Event = g2(events, epicRunId, expectedSpecHash);
  if (!g2Event) throw new Error("STORY_ANALYSIS_G2_NOT_PASSED");
  if (storySpecHash !== void 0) {
    const g2us = events.find(
      (e) => e.event === "g2_us_evaluated" && e.storyRunId === storyRunId && e.status === "passed" && e.storySpec?.sha256 === storySpecHash
    );
    if (!g2us) throw new Error("STORY_ANALYSIS_G2_US_NOT_PASSED");
  }
  const spec = {
    path: g2Event.spec.path,
    sha256: g2Event.spec.sha256,
    policyVersion: g2Event.policyVersion,
    frontmatter: loadForgeSpec({ specPath: g2Event.spec.path, roots, workItem: epic.workItem }).frontmatter
  };
  if (spec.sha256 !== g2Event.spec.sha256) throw new Error("STORY_ANALYSIS_SPEC_HASH_STALE");
  const agent = await runtime.preflightAgent(namespaceId, agentName);
  if (!agent.ok) throw new Error(`STORY_ANALYSIS_AGENT_PREFLIGHT_FAILED:${agent.reason}`);
  const ro = await runtime.preflightReadOnlyWorkspace(namespaceId, agent.agent, roots.repoRoot);
  if (!ro.ok) throw new Error(`STORY_ANALYSIS_READ_ONLY_PREFLIGHT_FAILED:${ro.reason}`);
  const executionId = `exec_${randomUUID10()}`;
  const created = await runtime.createCase(namespaceId, `Forge analysis ${story.workItem.id}`);
  const caseId = created.id;
  const brief = buildBrief({ epic, story, spec, ...supplement !== void 0 ? { supplement } : {} });
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: "agent_execution_started",
    runId: epicRunId,
    parentRunId: epicRunId,
    ...ref({
      executionId,
      caseId,
      storyRunId,
      role: "analyst",
      agentName,
      namespaceId,
      observedAt: now(),
      status: "started"
    }),
    policyVersion: STORY_ANALYSIS_POLICY_VERSION,
    briefArtifact: { kind: "brief", sha256: sha2562(brief), mediaType: "text/plain", schemaVersion: 1 }
  });
  let turn;
  try {
    turn = await runtime.runAgentTurn(caseId, agentName, brief);
  } catch {
    turn = { status: "error", caseStatus: null, killedByBudget: false, message: "" };
  }
  const executionStatus = turn.status === "finished" ? "finished" : "failed";
  const finished = ref({
    executionId,
    caseId,
    storyRunId,
    role: "analyst",
    agentName,
    namespaceId,
    observedAt: now(),
    status: executionStatus
  });
  const base = {
    schemaVersion: 1,
    event: "agent_execution_finished",
    runId: epicRunId,
    parentRunId: epicRunId,
    ...finished,
    policyVersion: STORY_ANALYSIS_POLICY_VERSION,
    outcome: turn.status,
    caseStatus: turn.caseStatus ?? null,
    killedByBudget: turn.killedByBudget === true
  };
  if (turn.status !== "finished") {
    appendForgeLedgerEvent(filePath, base);
    return { execution: finished, outcome: turn.status };
  }
  const output = typeof turn.message === "string" ? turn.message : "";
  if (!output) {
    const validation2 = {
      status: "invalid",
      code: "STORY_ANALYSIS_OUTPUT_MISSING",
      schemaVersion: STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
      message: "Agent runtime finished without a persisted analysis message."
    };
    appendForgeLedgerEvent(filePath, { ...base, analysisValidation: validation2 });
    return { execution: finished, outcome: turn.status, validation: validation2 };
  }
  const descriptor = writeStoryAnalysisArtifact(store, epicRunId, executionId, output);
  appendForgeLedgerEvent(filePath, { ...base, artifact: descriptor });
  const parsed = uniquePlan(output);
  let validation;
  if (!parsed.ok) validation = { status: "invalid", code: parsed.code };
  else if (parsed.plan.files.length > MAX_FILES || parsed.plan.doneWhen.length > MAX_TEXT || parsed.plan.steps?.some((s) => typeof s !== "string" || s.length > MAX_TEXT))
    validation = { status: "invalid", code: "STORY_ANALYSIS_PLAN_LIMIT" };
  else {
    const files = parsed.plan.files.length > 0 ? checkPlanFiles(parsed.plan.files, roots.repoRoot) : { missingFiles: [] };
    const outside = parsed.plan.files.filter((file) => !scopeValid(file, spec.frontmatter.scope));
    validation = files.missingFiles.length ? { status: "invalid", code: "STORY_ANALYSIS_PLAN_FILE_MISSING", missingFiles: files.missingFiles } : outside.length ? { status: "invalid", code: "STORY_ANALYSIS_PLAN_OUT_OF_SCOPE", outsideFiles: outside } : { status: "valid", fileCount: parsed.plan.files.length };
  }
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: "story_analysis_plan_validated",
    runId: epicRunId,
    storyRunId,
    executionId,
    planSchemaVersion: STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
    status: validation.status,
    code: validation.code ?? "STORY_ANALYSIS_PLAN_VALID",
    ...validation.missingFiles ? { missingFiles: validation.missingFiles } : {},
    ...validation.outsideFiles ? { outsideFiles: validation.outsideFiles } : {},
    artifact: descriptor,
    at: now()
  });
  return { execution: finished, outcome: turn.status, validation };
}

// ../src/application/forge-bmad/forge-story-edit.ts
import { existsSync as existsSync5, readFileSync as readFileSync6 } from "node:fs";
import { createHash as createHash22, randomUUID as randomUUID11 } from "node:crypto";
import { join as join20, resolve as resolve6 } from "node:path";
var STORY_EDIT_SCHEMA_VERSION = 1;
var STORY_EDIT_POLICY_VERSION = "forge-story-edit-v1";
var fail8 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var hash3 = (value) => `sha256:${createHash22("sha256").update(value).digest("hex")}`;
var safeArtifact = (store, descriptor) => {
  if (!descriptor?.path || !descriptor?.sha256)
    fail8("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID", "Analysis artifact descriptor requires path and sha256.");
  const root = resolve6(store);
  const path = resolve6(root, descriptor.path);
  if (!path.startsWith(`${root}/`))
    fail8("STORY_EDIT_ANALYSIS_ARTIFACT_PATH_INVALID", "Analysis artifact path escapes the run store.");
  if (!existsSync5(path)) fail8("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID", "Analysis artifact does not exist.");
  const text2 = readFileSync6(path, "utf8");
  if (hash3(text2) !== descriptor.sha256)
    fail8("STORY_EDIT_ANALYSIS_ARTIFACT_HASH_MISMATCH", "Analysis artifact content does not match its SHA-256.");
  return text2;
};
var matches = (pattern, file) => new RegExp(
  `^${pattern.split("/").map((p) => p === "**" ? ".*" : p === "*" ? "[^/]+" : p.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("/")}$`
).test(file);
var allowedModified = (file, scope, plan) => plan.files.includes(file) && !scope.deny.some((p) => matches(p, file));
var allowedCreated = (file, scope) => scope.create.some((p) => matches(p, file)) && !scope.deny.some((p) => matches(p, file));
function planFromArtifact(text2) {
  const blocks = [...text2.matchAll(/```json\s*([\s\S]*?)```/g)].map((m) => m[1].trim());
  if (blocks.length !== 1)
    fail8("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact must contain exactly one JSON plan.");
  let raw;
  try {
    raw = JSON.parse(blocks[0]);
  } catch {
    fail8("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact JSON plan is invalid.");
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((k) => !["files", "doneWhen", "steps"].includes(k)))
    fail8("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact plan schema is invalid.");
  const parsed = parsePlan(`\`\`\`json
${blocks[0]}
\`\`\``);
  if (!parsed.ok) fail8("STORY_EDIT_ANALYSIS_PLAN_INVALID", parsed.error);
  return parsed.plan;
}
async function executeStoryEdit({
  roots,
  epicRunId,
  storyRunId,
  analysisExecutionId,
  namespaceId,
  agentName,
  expectedSpecHash,
  storySpecHash,
  supplement,
  runtime = agentos_operations_exports,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  if (!namespaceId || !agentName) fail8("STORY_EDIT_INPUT_INVALID", "namespaceId and agentName are required.");
  if (supplement !== void 0 && (typeof supplement !== "string" || supplement.length > 4e3))
    fail8("STORY_EDIT_SUPPLEMENT_INVALID", "supplement must be a string of at most 4000 characters.");
  const store = ensureForgeRunStore(roots);
  const filePath = join20(store, `${epicRunId}.jsonl`);
  if (!existsSync5(filePath)) fail8("STORY_EDIT_RUN_NOT_FOUND", `Epic run ${epicRunId} has no ledger.`);
  const events = parseForgeLedger(filePath);
  const epic = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  if (!epic) fail8("STORY_EDIT_RUN_NOT_FOUND", `Epic run ${epicRunId} is absent from its ledger.`);
  const story = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!story) fail8("STORY_EDIT_STORY_NOT_FOUND", `Story run ${storyRunId} is absent from Epic run ${epicRunId}.`);
  if (events.some(
    (e) => e.event === "story_edit_started" && e.storyRunId === storyRunId && !events.some((f) => f.event === "story_edit_finished" && f.editId === e.editId)
  ))
    fail8("STORY_EDIT_ALREADY_RUNNING", "A Story edit is already active.");
  const g1Event = events.find((e) => e.event === "human_decision_recorded" && e.runId === epicRunId && e.gate === "G1");
  if (g1Event?.decision?.outcome !== "approved") fail8("STORY_EDIT_G1_NOT_APPROVED");
  const g22 = events.filter((e) => e.event === "g2_evaluated" && e.runId === epicRunId && e.status === "passed").at(-1);
  if (!g22 || g22.spec?.sha256 !== expectedSpecHash) fail8("STORY_EDIT_G2_NOT_PASSED");
  if (storySpecHash !== void 0) {
    const g2us = events.find(
      (e) => e.event === "g2_us_evaluated" && e.storyRunId === storyRunId && e.status === "passed" && e.storySpec?.sha256 === storySpecHash
    );
    if (!g2us) fail8("STORY_EDIT_G2_US_NOT_PASSED");
  }
  const analysis = events.find(
    (e) => e.event === "agent_execution_finished" && e.executionId === analysisExecutionId && e.storyRunId === storyRunId && e.status === "finished"
  );
  const validation = events.find(
    (e) => e.event === "story_analysis_plan_validated" && e.executionId === analysisExecutionId && e.status === "valid"
  );
  if (!analysis || !validation) fail8("STORY_EDIT_ANALYSIS_NOT_VALID");
  const text2 = safeArtifact(store, analysis.artifact);
  if (validation.artifact?.sha256 !== analysis.artifact?.sha256 || validation.artifact?.path !== analysis.artifact?.path)
    fail8("STORY_EDIT_ANALYSIS_PLAN_STALE", "Analysis validation does not reference the finished artifact.");
  const plan = planFromArtifact(text2);
  const missing = checkPlanFiles(plan.files, roots.repoRoot).missingFiles;
  if (missing.length) fail8("STORY_EDIT_ANALYSIS_PLAN_STALE", `Analysis plan files are missing: ${missing.join(", ")}.`);
  const spec = loadForgeSpec({
    specPath: g22.spec.path,
    roots,
    workItem: epic.workItem
  });
  if (spec.sha256 !== g22.spec.sha256) fail8("STORY_EDIT_SPEC_HASH_STALE");
  const agent = await runtime.preflightAgent(namespaceId, agentName);
  if (!agent.ok) fail8("STORY_EDIT_AGENT_PREFLIGHT_FAILED");
  const writable = await runtime.preflightWritableWorkspace(namespaceId, agent.agent, roots.repoRoot);
  if (!writable.ok) fail8("STORY_EDIT_WRITABLE_PREFLIGHT_FAILED", writable.reason);
  const editId = `edit_${randomUUID11()}`;
  const brief = [
    `Epic: ${epic.workItem.id}`,
    `Story: ${story.workItem.id}`,
    `Spec SHA-256: ${spec.sha256}`,
    `Files to modify: ${plan.files.join(", ")}`,
    `Done when: ${plan.doneWhen}`,
    `Allow: ${spec.frontmatter.scope.allow.join(", ")}`,
    `Create: ${spec.frontmatter.scope.create.join(", ")}`,
    `Deny: ${spec.frontmatter.scope.deny.join(", ")}`,
    supplement ? `Supplement: ${supplement}` : "",
    "Implement only this plan. Do not run shell, git, tests, builds, or oracles."
  ].filter(Boolean).join("\n");
  const before = snapshotDiff(roots.repoRoot);
  const created = await runtime.createCase(namespaceId, `Forge edit ${story.workItem.id}`);
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: "story_edit_started",
    runId: epicRunId,
    storyRunId,
    editId,
    analysisExecutionId,
    caseId: created.id,
    policyVersion: STORY_EDIT_POLICY_VERSION,
    at: now()
  });
  const turn = await runtime.runAgentTurn(created.id, agentName, brief);
  const changed = diffSince(before, roots.repoRoot);
  const invalid3 = [
    ...changed.modified.filter((file) => !allowedModified(file, spec.frontmatter.scope, plan)),
    ...changed.untracked.filter((file) => !allowedCreated(file, spec.frontmatter.scope))
  ];
  const status = turn.status === "finished" && invalid3.length === 0 ? "finished" : "failed";
  appendForgeLedgerEvent(filePath, {
    schemaVersion: 1,
    event: "story_edit_finished",
    runId: epicRunId,
    storyRunId,
    editId,
    caseId: created.id,
    status,
    outcome: turn.status,
    caseStatus: turn.caseStatus ?? null,
    killedByBudget: turn.killedByBudget === true,
    filesModified: changed.modified,
    filesCreated: changed.untracked,
    diffValidation: {
      status: invalid3.length ? "invalid" : "valid",
      code: invalid3.length ? "STORY_EDIT_DIFF_OUT_OF_SCOPE" : "STORY_EDIT_DIFF_VALID",
      invalidFiles: invalid3
    },
    at: now()
  });
  return {
    editId,
    status,
    filesModified: changed.modified,
    filesCreated: changed.untracked,
    diffValidation: { status: invalid3.length ? "invalid" : "valid", invalidFiles: invalid3 }
  };
}

// ../src/application/forge-bmad/forge-story-oracles.ts
import { existsSync as existsSync7 } from "node:fs";
import { createHash as createHash24, randomUUID as randomUUID12 } from "node:crypto";
import { join as join23 } from "node:path";

// ../lib/domains.mjs
import { join as join21, dirname as dirname8, resolve as resolve7 } from "node:path";
import { fileURLToPath as fileURLToPath2 } from "node:url";
var __dirname = dirname8(fileURLToPath2(import.meta.url));
var REPO_ROOT = process.env.FACTORY_ROOT ? resolve7(process.env.FACTORY_ROOT) : join21(__dirname, "..", "..");
var domains = {
  back: {
    oracles: [
      {
        name: "build",
        command: process.env.FACTORY_COMMAND_BACK ?? "./gradlew :agentos-service:build --rerun-tasks --console=plain",
        cwd: process.env.FACTORY_CWD_BACK ?? join21(REPO_ROOT, "agentos")
      }
    ]
    // lock: null  — placeholder pour le verrou lecteurs/écrivain à venir
  },
  front: {
    oracles: [
      {
        // Oracle de build Angular — exécuté EN PREMIER (fail-fast).
        //
        // POURQUOI `build` ET PAS `type-check` (décision du 2026-08-XX)
        // ─────────────────────────────────────────────────────────────────
        // L'oracle `types` (`tsc --noEmit` via la cible Nx `type-check`) échoue
        // systématiquement avec TS5090 (`Non-relative paths are not allowed when
        // baseUrl is not set`) sur plusieurs apps du dépôt cible (admin, agentic-studio,
        // etc.). TS5090 est une erreur de configuration tsconfig, pas une régression
        // produit — mais elle fait échouer l'oracle à chaque run, même sans modification.
        //
        // L'oracle `build` (`@angular/build:application` via `pnpm nx run-many
        // --target=build`) résout les chemins via le bundler esbuild, qui n'émet pas
        // TS5090. Il valide en plus les templates Angular, que `tsc --noEmit` ne vérifie
        // pas. C'est la commande équivalente à ce que CI exécute pour valider les PRs.
        //
        // RÉSOLUTION DES HÔTES BUILDABLES
        // ─────────────────────────────────────────────────────────────────
        // Les libs (projets propriétaires des fichiers modifiés) n'ont pas de cible
        // `build` angulaire. On les mappe vers des apps hôtes via `buildHostArg: true`
        // (voir oracle-command.mjs, `resolveBuildHosts`). Si aucun hôte n'est trouvé,
        // l'oracle retourne ORACLE_INFRASTRUCTURE (gate humain), pas un succès vide.
        //
        // POURQUOI `--configuration=development` ET PAS `production`
        // ─────────────────────────────────────────────────────────────────
        // La configuration `production` active les optimisations (minification, tree
        // shaking, budget de taille), qui augmentent significativement la durée du
        // build. La configuration `development` compile les templates, vérifie les
        // types Angular, et détecte les erreurs d'import — c'est suffisant pour
        // valider une PR. Le coût en temps est acceptable (~2-5 min vs ~10-15 min).
        //
        // `--skip-nx-cache` est obligatoire : le cache Nx est partagé avec
        // l'environnement de dev, et les apps hôtes peuvent avoir été buildées sur
        // cet état. Sans `--skip-nx-cache`, on tomberait dans le piège
        // `executed: 0` (garde A8 bloquante).
        //
        // ANGLE MORT CONNU : le build `development` ne détecte pas les erreurs de
        // budget de taille (production uniquement). Acceptable pour un oracle de
        // validation de PR.
        //
        // ORACLE `types` DÉSACTIVÉ TEMPORAIREMENT
        // ─────────────────────────────────────────────────────────────────
        // L'oracle `types` est commenté ci-dessous. Il sera réactivé quand les
        // tsconfigs des apps cibles auront été réparés (ajout de `baseUrl` dans
        // la chaîne d'extension vers `tsconfig.base.json`). Ne pas le supprimer —
        // il documente l'intention et la raison de la désactivation.
        name: "build",
        command: process.env.FACTORY_COMMAND_FRONT_BUILD ?? "pnpm nx run-many --target=build --configuration=development --skip-nx-cache",
        cwd: process.env.FACTORY_CWD_FRONT ?? REPO_ROOT,
        buildHostArg: true
      },
      // ORACLE `types` DÉSACTIVÉ — TS5090 systématique dans le dépôt cible.
      // Réactiver quand les tsconfigs seront réparés (baseUrl manquant).
      // {
      //   name: 'types',
      //   command: process.env.FACTORY_COMMAND_FRONT_TYPES
      //     ?? 'pnpm nx run-many --target=type-check --projects=aphrodite,admin,agentic-studio,copilot-chat --parallel=4',
      //   cwd: process.env.FACTORY_CWD_FRONT
      //     ?? REPO_ROOT,
      // },
      {
        // Oracle de comportement — exécuté EN SECOND (seulement si `build` passe).
        //
        // Transpile sans vérifier les types (SWC/Babel/isolatedModules) —
        // c'est pour ça que `build` le précède.
        //
        // FACTORY_COMMAND_FRONT surcharge cette commande (comportement historique).
        // La commande surchargée doit contenir `-t <cible>` ou `--target=<cible>`
        // pour que `buildOracleCommand` puisse extraire la cible et construire
        // la commande `run-many` effective. FACTORY_FRONT_TEST_TARGET configure
        // aussi la cible par défaut (frontend-test) sans remplacer la commande.
        //
        // POURQUOI `filesArg: true` — HISTORIQUE DES INCIDENTS
        // ─────────────────────────────────────────────────────────────────────
        // F21 : `affected --base=sprint` incluait 16 427 commits de divergence
        // → 1033 projets, tous en cache, `executed: 0`.
        //
        // A2 (C4) : remplacement par `affected --files=<liste>`. 1 fichier → 53
        // projets (clôture transitive). Correctif efficace sur le périmètre.
        //
        // Incident suivant : avec `--files=<6 fichiers>`, Nx calcule 216 projets
        // (clôture transitive), tous servis depuis le cache. Cause : le cache Nx
        // est partagé entre la factory et l'environnement de dev. `--skip-nx-cache`
        // testé expérimentalement : ne réduit pas le périmètre (375 projets avec
        // 3 fichiers), le problème de périmètre reste entier.
        //
        // SOLUTION RETENUE : `run-many --projects=<projets directs> --skip-nx-cache`
        // ─────────────────────────────────────────────────────────────────────
        // `filesArg: true` signale à `buildOracleCommand` de résoudre les projets
        // propriétaires des fichiers modifiés (pas leurs dépendants transitifs),
        // puis de construire :
        //   pnpm nx run-many --target=<FACTORY_FRONT_TEST_TARGET> --projects=proj1,proj2 --skip-nx-cache
        //
        // La résolution se fait par remontée de dossiers jusqu'au premier
        // `project.json`, en Node pur, sans appel Nx. Pour 6 fichiers dans 3 libs :
        // 3 projets au lieu de 216.
        //
        // LIMITE ASSUMÉE : des régressions dans les consommateurs ne seront pas
        // détectées. Acceptable parce que `verify-build` valide la compatibilité
        // des interfaces via les apps hôtes, les consommateurs ont leurs propres
        // tests en CI, et un oracle bloqué en permanence ne détecte rien du tout.
        //
        // Un oracle sans `filesArg` ni `buildHostArg` (comme `build` Gradle) reçoit
        // sa commande telle quelle — périmètre fixe, indépendant du diff.
        name: "tests",
        command: process.env.FACTORY_COMMAND_FRONT ?? `pnpm nx affected -t ${process.env.FACTORY_FRONT_TEST_TARGET ?? "frontend-test"}`,
        cwd: process.env.FACTORY_CWD_FRONT ?? REPO_ROOT,
        filesArg: true
      }
    ]
    // lock: null  — placeholder pour le verrou lecteurs/écrivain à venir
  }
};

// ../src/application/forge-bmad/forge-front-oracle-resolution.ts
import { existsSync as existsSync6, readFileSync as readFileSync7 } from "node:fs";
import { execFileSync } from "node:child_process";
import { createHash as createHash23 } from "node:crypto";
import { dirname as dirname9, isAbsolute as isAbsolute12, join as join22, relative as relative6, resolve as resolve8 } from "node:path";
var FRONT_ORACLE_MAP_SCHEMA_VERSION = 1;
var INSPECT_TIMEOUT_MS = 1e4;
var INSPECT_MAX_BUFFER = 1024 * 1024;
var fail9 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var hash4 = (value) => `sha256:${createHash23("sha256").update(JSON.stringify(value)).digest("hex")}`;
var validName = (name) => typeof name === "string" && /^[A-Za-z0-9._-]+$/.test(name);
var readProject = (path, label) => {
  try {
    const config = JSON.parse(readFileSync7(path, "utf8"));
    if (!validName(config.name)) fail9("ORACLE_INFRASTRUCTURE", `${label} has an absent or invalid Nx project name.`);
    return config;
  } catch (error2) {
    if (error2.code === "ORACLE_INFRASTRUCTURE") throw error2;
    fail9("ORACLE_INFRASTRUCTURE", `Cannot read ${label}.`);
  }
};
var hostProject = (root, name) => {
  for (const path of [
    join22(root, "apps", name, "project.json"),
    join22(root, "frontend", "apps", name, "project.json"),
    join22(root, name, "project.json")
  ])
    if (existsSync6(path)) return readProject(path, `Build host project.json for ${name}`);
  return null;
};
function resolveOwnerProjectConfigs(files, repoRoot) {
  const root = resolve8(repoRoot);
  const byName = /* @__PURE__ */ new Map();
  for (const file of files) {
    if (typeof file !== "string" || !file || isAbsolute12(file))
      fail9("ORACLE_INFRASTRUCTURE", `Invalid StoryEdit file path: ${String(file)}.`);
    const absolute = resolve8(root, file);
    if (relative6(root, absolute).startsWith(".."))
      fail9("ORACLE_INFRASTRUCTURE", `StoryEdit file escapes repository root: ${file}.`);
    let dir = dirname9(absolute);
    let found = false;
    while (dir === root || dir.startsWith(`${root}/`)) {
      const projectPath = join22(dir, "project.json");
      if (existsSync6(projectPath)) {
        const config = readProject(projectPath, `Owner project.json for ${file}`);
        const previous = byName.get(config.name);
        if (previous && previous.projectPath !== projectPath)
          fail9("ORACLE_INFRASTRUCTURE", `Nx owner ${config.name} resolves to multiple project.json files.`);
        if (!previous) byName.set(config.name, { name: config.name, projectPath, config });
        found = true;
        break;
      }
      const parent = dirname9(dir);
      if (parent === dir) break;
      dir = parent;
    }
    if (!found) continue;
  }
  return [...byName.values()];
}
function inspectNxProject(name, repoRoot) {
  if (!validName(name)) fail9("ORACLE_INFRASTRUCTURE", `Invalid Nx project name for inspection: ${String(name)}.`);
  let output;
  try {
    output = execFileSync("pnpm", ["nx", "show", "project", name, "--json"], {
      cwd: repoRoot,
      encoding: "utf8",
      timeout: INSPECT_TIMEOUT_MS,
      maxBuffer: INSPECT_MAX_BUFFER,
      stdio: ["ignore", "pipe", "pipe"]
    });
  } catch {
    fail9("ORACLE_INFRASTRUCTURE", `Cannot inspect effective Nx configuration for ${name}.`);
  }
  let config;
  try {
    config = JSON.parse(output);
  } catch {
    fail9("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is not valid JSON.`);
  }
  if (config?.name !== name || !config.targets || typeof config.targets !== "object" || Array.isArray(config.targets))
    fail9("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is invalid or mismatched.`);
  return config;
}
var inspectEffectiveProject = (name, repoRoot, projectInspector) => {
  let config;
  try {
    config = projectInspector(name, repoRoot);
  } catch (error2) {
    if (error2?.code === "ORACLE_INFRASTRUCTURE") throw error2;
    fail9("ORACLE_INFRASTRUCTURE", `Cannot inspect effective Nx configuration for ${name}.`);
  }
  if (!config || config.name !== name || !config.targets || typeof config.targets !== "object" || Array.isArray(config.targets))
    fail9("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is invalid or mismatched.`);
  return config;
};
function parseFrontBuildHostMap(raw) {
  if (typeof raw !== "string" || !raw) fail9("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP is required.");
  let map;
  try {
    map = JSON.parse(raw);
  } catch {
    fail9("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP must be valid JSON.");
  }
  if (!map || typeof map !== "object" || Array.isArray(map))
    fail9("ORACLE_INFRASTRUCTURE", "Host map must be an object.");
  for (const [owner, hosts] of Object.entries(map)) {
    if (owner !== "*" && !validName(owner) || !Array.isArray(hosts) || hosts.length === 0 || hosts.some((host) => !validName(host)))
      fail9("ORACLE_INFRASTRUCTURE", "Host map contains an invalid owner or host.");
  }
  return Object.fromEntries(
    Object.entries(map).map(([owner, hosts]) => [owner, [...new Set(hosts)].sort()])
  );
}
function resolveFrontOraclePlan({
  repoRoot,
  files,
  hostMapRaw,
  buildTemplate,
  testsTarget = process.env.FACTORY_FRONT_TEST_TARGET ?? "frontend-test",
  requireBuild = true,
  projectInspector = inspectNxProject
}) {
  const ownerProjects = resolveOwnerProjectConfigs(files, repoRoot);
  const owners = ownerProjects.map((owner) => owner.name);
  if (!owners.length) fail9("ORACLE_INFRASTRUCTURE", "No Nx owner project found for StoryEdit files.");
  const inspected = /* @__PURE__ */ new Map();
  const inspect = (name) => {
    if (!inspected.has(name)) inspected.set(name, inspectEffectiveProject(name, repoRoot, projectInspector));
    return inspected.get(name);
  };
  const map = requireBuild ? parseFrontBuildHostMap(hostMapRaw) : null;
  const hosts = [];
  const ownersWithTestTarget = [];
  const ownersWithoutTestTarget = [];
  for (const owner of ownerProjects) {
    if (requireBuild) {
      const mapped = map[owner.name] ?? map["*"];
      if (!mapped) fail9("ORACLE_INFRASTRUCTURE", `No build host mapping for owner ${owner.name}.`);
      for (const host of mapped) {
        if (!hostProject(repoRoot, host)) fail9("ORACLE_INFRASTRUCTURE", `Build host ${host} does not exist.`);
        if (!inspect(host).targets.build && !inspect(host).targets["build-angular"])
          fail9("ORACLE_INFRASTRUCTURE", `Build host ${host} has no build target.`);
        if (!hosts.includes(host)) hosts.push(host);
      }
    }
    ;
    (inspect(owner.name).targets[testsTarget] ? ownersWithTestTarget : ownersWithoutTestTarget).push(owner.name);
  }
  const buildHosts = [...hosts].sort();
  const build = {
    command: requireBuild ? `${buildTemplate} --projects=${buildHosts.join(",")}` : null,
    cwd: repoRoot,
    owners,
    buildHosts,
    target: "build",
    configuration: "development"
  };
  const tests = {
    command: ownersWithTestTarget.length ? `pnpm nx run-many --target=${testsTarget} --projects=${ownersWithTestTarget.join(",")} --skip-nx-cache` : null,
    cwd: repoRoot,
    owners: ownersWithTestTarget,
    ownersWithTestTarget,
    ownersWithoutTestTarget,
    buildHosts: [],
    target: testsTarget,
    configuration: null
  };
  return {
    schemaVersion: FRONT_ORACLE_MAP_SCHEMA_VERSION,
    owners,
    ownersWithTestTarget,
    ownersWithoutTestTarget,
    build,
    tests,
    commandHash: hash4({ build, tests })
  };
}

// ../src/application/forge-bmad/forge-story-oracles.ts
var STORY_ORACLE_POLICY_VERSION = "forge-story-oracles-v1";
var fail10 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var isAllowedStoryOracleRequestBody = (body) => !!body && typeof body === "object" && !Array.isArray(body) && Object.keys(body).every((key) => ["editId", "expectedSpecHash", "attempt"].includes(key));
var hash5 = (value) => `sha256:${createHash24("sha256").update(value).digest("hex")}`;
var catalog = {
  "front.build": { domain: "front", name: "build" },
  "front.tests": { domain: "front", name: "tests" },
  "back.build": { domain: "back", name: "build" }
};
function resolve9(ids) {
  const result = [];
  for (const id2 of ids) {
    const entry = catalog[id2];
    if (!entry) fail10("STORY_ORACLE_CATALOG_INVALID", `Unknown oracle catalog id: ${id2}`);
    const oracle = domains[entry.domain]?.oracles.find((item) => item.name === entry.name);
    if (!oracle) fail10("STORY_ORACLE_CATALOG_INVALID", `Unavailable oracle catalog id: ${id2}`);
    result.push({ id: id2, oracle });
  }
  return result;
}
async function executeStoryOracles({
  roots,
  epicRunId,
  storyRunId,
  editId,
  expectedSpecHash,
  attempt = 1,
  executor = runCommand,
  commandResolver = buildOracleCommand,
  frontResolver = resolveFrontOraclePlan,
  hostMapRaw = process.env.FACTORY_FRONT_BUILD_HOST_MAP,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  if (!Number.isInteger(attempt) || attempt <= 0)
    fail10("STORY_ORACLE_ATTEMPT_INVALID", "attempt must be a positive integer.");
  const store = ensureForgeRunStore(roots);
  const path = join23(store, `${epicRunId}.jsonl`);
  if (!existsSync7(path)) fail10("STORY_ORACLE_RUN_NOT_FOUND", `Epic run ${epicRunId} has no ledger.`);
  const events = parseForgeLedger(path);
  const start = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  const story = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!start || !story) fail10("STORY_ORACLE_STORY_NOT_FOUND");
  if (events.some(
    (e) => e.event === "story_oracles_started" && e.editId === editId && !events.some((f) => f.event === "story_g3_evaluated" && f.campaignId === e.campaignId)
  ))
    fail10("STORY_ORACLE_ALREADY_RUNNING", "A Story oracle campaign is already active.");
  if (events.some((e) => e.event === "story_g3_evaluated" && e.editId === editId && e.attempt === attempt))
    fail10("STORY_ORACLE_ATTEMPT_COLLISION", "A terminal campaign already exists for this editId and attempt.");
  const g12 = events.find((e) => e.event === "human_decision_recorded" && e.runId === epicRunId && e.gate === "G1");
  if (g12?.decision?.outcome !== "approved") fail10("STORY_ORACLE_G1_NOT_APPROVED");
  const g22 = events.filter((e) => e.event === "g2_evaluated" && e.runId === epicRunId && e.status === "passed").at(-1);
  if (!g22 || g22.spec?.sha256 !== expectedSpecHash) fail10("STORY_ORACLE_G2_NOT_PASSED");
  const edit = events.find(
    (e) => e.event === "story_edit_finished" && e.editId === editId && e.storyRunId === storyRunId
  );
  if (!edit || edit.status !== "finished" || edit.outcome !== "finished" || edit.diffValidation?.status !== "valid")
    fail10("STORY_ORACLE_EDIT_NOT_VALID");
  let spec;
  try {
    spec = loadForgeSpec({ specPath: g22.spec.path, roots, workItem: start.workItem });
  } catch (error2) {
    if (error2.code === "G2_ORACLE_UNKNOWN")
      fail10(
        "STORY_ORACLE_CATALOG_INVALID",
        "The persisted G2 spec references an oracle outside the StoryOracle catalog."
      );
    throw error2;
  }
  if (spec.sha256 !== g22.spec.sha256) fail10("STORY_ORACLE_SPEC_HASH_STALE");
  const entries = resolve9(spec.frontmatter.oracles);
  const files = [...edit.filesModified ?? [], ...edit.filesCreated ?? []];
  let frontPlan;
  try {
    if (entries.some((entry) => entry.id.startsWith("front.")))
      frontPlan = frontResolver({
        repoRoot: roots.repoRoot,
        files,
        hostMapRaw,
        buildTemplate: domains.front.oracles.find((item) => item.name === "build").command,
        testsTarget: process.env.FACTORY_FRONT_TEST_TARGET ?? "frontend-test",
        requireBuild: entries.some((entry) => entry.id === "front.build")
      });
  } catch (error2) {
    const code = error2.code ?? "ORACLE_INFRASTRUCTURE";
    const campaignId2 = `oracle_${randomUUID12()}`;
    appendForgeLedgerEvent(path, {
      schemaVersion: 1,
      event: "story_oracles_started",
      campaignId: campaignId2,
      runId: epicRunId,
      storyRunId,
      editId,
      attempt,
      specHash: spec.sha256,
      policyVersion: STORY_ORACLE_POLICY_VERSION,
      at: now()
    });
    appendForgeLedgerEvent(path, {
      schemaVersion: 1,
      event: "story_oracle_finished",
      campaignId: campaignId2,
      runId: epicRunId,
      storyRunId,
      editId,
      name: "front.infrastructure",
      status: "blocked",
      code,
      exitCode: null,
      durationMs: 0,
      commandHash: null,
      at: now()
    });
    appendForgeLedgerEvent(path, {
      schemaVersion: 1,
      event: "story_g3_evaluated",
      campaignId: campaignId2,
      runId: epicRunId,
      storyRunId,
      editId,
      attempt,
      status: "blocked",
      specHash: spec.sha256,
      policyVersion: STORY_ORACLE_POLICY_VERSION,
      at: now()
    });
    return {
      campaignId: campaignId2,
      status: "blocked",
      results: [
        { name: "front.infrastructure", status: "blocked", code, exitCode: null, durationMs: 0, commandHash: null }
      ]
    };
  }
  const campaignId = `oracle_${randomUUID12()}`;
  appendForgeLedgerEvent(path, {
    schemaVersion: 1,
    event: "story_oracles_started",
    campaignId,
    runId: epicRunId,
    storyRunId,
    editId,
    attempt,
    specHash: spec.sha256,
    policyVersion: STORY_ORACLE_POLICY_VERSION,
    at: now()
  });
  const results = [];
  for (const { id: id2, oracle } of entries) {
    if (id2 === "front.tests" && frontPlan.tests.command === null) {
      const result2 = {
        name: id2,
        ownerProjects: frontPlan.tests.owners,
        ownersWithTestTarget: frontPlan.tests.ownersWithTestTarget,
        ownersWithoutTestTarget: frontPlan.tests.ownersWithoutTestTarget,
        buildHosts: [],
        target: frontPlan.tests.target,
        configuration: null,
        status: "skipped",
        code: "ORACLE_NO_TEST_TARGET",
        exitCode: null,
        durationMs: 0,
        commandHash: null
      };
      appendForgeLedgerEvent(path, {
        schemaVersion: 1,
        event: "story_oracle_finished",
        campaignId,
        runId: epicRunId,
        storyRunId,
        editId,
        ...result2,
        at: now()
      });
      results.push(result2);
      continue;
    }
    let command;
    try {
      command = id2 === "front.build" ? frontPlan.build.command : id2 === "front.tests" ? frontPlan.tests.command : id2 === "back.build" ? {
        noHost: true,
        reason: "back.build is AgentOS-specific in the current domains catalog and is unavailable for a generic target repo."
      } : commandResolver(oracle, files, roots.repoRoot);
    } catch (error2) {
      command = { noHost: true, reason: String(error2) };
    }
    if (typeof command !== "string") {
      const result2 = {
        name: id2,
        status: "blocked",
        code: "ORACLE_INFRASTRUCTURE",
        exitCode: null,
        durationMs: 0,
        commandHash: null
      };
      appendForgeLedgerEvent(path, {
        schemaVersion: 1,
        event: "story_oracle_finished",
        campaignId,
        runId: epicRunId,
        storyRunId,
        editId,
        ...result2,
        at: now()
      });
      results.push(result2);
      break;
    }
    let raw;
    try {
      raw = executor(command, { cwd: roots.repoRoot, timeoutMs: 20 * 60 * 1e3 });
    } catch {
      raw = { exitCode: -1, timedOut: false, durationMs: 0, crashed: true };
    }
    const status2 = raw.timedOut || raw.crashed ? "blocked" : raw.exitCode === 0 ? "passed" : "failed";
    const testFacts = id2 === "front.tests" ? {
      ownersWithTestTarget: frontPlan.tests.ownersWithTestTarget,
      ownersWithoutTestTarget: frontPlan.tests.ownersWithoutTestTarget
    } : {};
    const result = {
      name: id2,
      ownerProjects: id2 === "front.tests" ? frontPlan.tests.owners : frontPlan?.owners ?? [],
      ...testFacts,
      buildHosts: id2 === "front.build" ? frontPlan?.build?.buildHosts ?? [] : [],
      target: id2 === "front.build" ? "build" : id2 === "front.tests" ? frontPlan.tests.target : null,
      configuration: id2 === "front.build" ? "development" : null,
      status: status2,
      code: raw.timedOut ? "ORACLE_TIMEOUT" : raw.crashed ? "ORACLE_CRASH" : raw.exitCode === 0 ? "ORACLE_PASS" : "ORACLE_FAIL",
      exitCode: raw.exitCode,
      durationMs: raw.durationMs ?? 0,
      commandHash: hash5(command)
    };
    appendForgeLedgerEvent(path, {
      schemaVersion: 1,
      event: "story_oracle_finished",
      campaignId,
      runId: epicRunId,
      storyRunId,
      editId,
      ...result,
      at: now()
    });
    results.push(result);
    if (status2 !== "passed") break;
  }
  const complete = results.length === entries.length && results.every(
    (result, index) => result.name === entries[index].id && (result.status === "passed" || result.code === "ORACLE_NO_TEST_TARGET")
  );
  const status = results.some((r) => r.status === "blocked") ? "blocked" : complete ? "passed" : "failed";
  appendForgeLedgerEvent(path, {
    schemaVersion: 1,
    event: "story_g3_evaluated",
    campaignId,
    runId: epicRunId,
    storyRunId,
    editId,
    attempt,
    status,
    specHash: spec.sha256,
    policyVersion: STORY_ORACLE_POLICY_VERSION,
    at: now()
  });
  return { campaignId, status, results };
}

// ../src/application/forge-bmad/forge-workflow-sync.ts
var ATTRIBUTION_FIELDS = /* @__PURE__ */ new Set(["actorId", "agentId", "caseId", "runId"]);
var SAFE_ATTRIBUTION = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/;
var SAFE_FORGE_TICKET_ID = /^[A-Z][A-Z0-9]+-\d+$/;
function sanitizeForgeSyncAttribution(body = {}) {
  if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).some((key) => !ATTRIBUTION_FIELDS.has(key)))
    return { ok: false, error: { code: "INVALID_ATTRIBUTION" } };
  for (const value of Object.values(body))
    if (typeof value !== "string" || !SAFE_ATTRIBUTION.test(value))
      return { ok: false, error: { code: "INVALID_ATTRIBUTION" } };
  return { ok: true, attribution: { ...body } };
}
async function syncForgeWorkflowProjection({
  repoRoot,
  namespaceId,
  ticketId,
  attribution = {},
  store
}) {
  if (typeof repoRoot !== "string" || !SAFE_FORGE_TICKET_ID.test(ticketId ?? ""))
    return { ok: false, error: { code: "INVALID_SYNC_TARGET" } };
  const authoritative = readForgeRunYamlStrict(repoRoot, ticketId);
  if (!authoritative.ok) return authoritative;
  const run = authoritative.run;
  const adapted = adaptForgeRunToWorkflowProjection(run);
  if (!adapted.ok) return adapted;
  const published = await store.publish(namespaceId, adapted.projection, attribution);
  if (!published.ok) return { ok: false, error: published.error };
  return {
    ok: true,
    changed: published.changed,
    workflowId: adapted.projection.workflowId,
    revision: published.snapshot.revision,
    projectionHash: published.snapshot.projectionHash
  };
}
export {
  AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION,
  AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS,
  AGENT_STEP_ATTEMPT_STATUSES,
  AGENT_STEP_ATTEMPT_TERMINAL_STATUSES,
  AGENT_STEP_ATTEMPT_TRANSITIONS,
  AGENT_STEP_RESULT_LIMITS,
  AGENT_STEP_RESULT_STATUSES,
  AgentStepAttemptStore,
  AgentStepResultStore,
  COMMENTS_CHAR_BUDGET,
  DEFAULT_PROCESS_LOCK_FILE,
  DEFAULT_RUN_STORE_POLICY,
  DELIVERY_ADAPTER_OUTCOMES,
  DELIVERY_DEFINITION_SCHEMA_VERSION,
  DELIVERY_EVIDENCE_KINDS,
  DELIVERY_INITIAL_STAGE,
  DELIVERY_OPERATION_ERROR_CODES,
  DELIVERY_OPERATION_KINDS,
  DELIVERY_OPERATION_STATES,
  DELIVERY_STAGES,
  DeliveryController,
  DeliveryDeploymentAdapter,
  DeliveryEvidenceStore,
  DeliveryGitControlPlane,
  DeliveryOperationController,
  DeliveryPullRequestAdapter,
  DeliveryStore,
  DeliveryTargetRegistry,
  DeliveryVerificationAdapter,
  ENVIRONMENT_STORE_ERROR_CODES,
  EXTERNAL_RUN_STORE_POLICY,
  FORGE_LEDGER_SCHEMA_VERSION,
  FORGE_ROOTS_SCHEMA_VERSION,
  FORGE_RUN_STORE_POLICIES,
  FORGE_SPEC_FRONTMATTER_PATTERN,
  FORGE_SPEC_SCHEMA_VERSION,
  FORGE_STORY_SPEC_SCHEMA_VERSION,
  FORGE_WORKFLOW_ERROR_CODES,
  FORGE_WORKFLOW_VERSION,
  FRONT_ORACLE_MAP_SCHEMA_VERSION,
  FilesystemAgentStepAttemptRepository,
  FilesystemAgentStepResultRepository,
  FilesystemOracleExecutionRepository,
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  G1_OUTCOMES,
  G1_POLICY_VERSION,
  G1_REASON_CODES,
  G2_POLICY_VERSION,
  G2_US_POLICY_VERSION,
  HUMAN_INTERACTION_KINDS,
  KeyedLock,
  ORACLE_CATALOG,
  OracleDefinitionRegistry,
  OracleDefinitionRegistryCore,
  REPO_RUN_STORE_POLICY,
  SAFE_FORGE_TICKET_ID,
  STORAGE_FORMAT_VERSION,
  STORAGE_KERNEL_ERROR_CODES,
  STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
  STORY_ANALYSIS_POLICY_VERSION,
  STORY_EDIT_POLICY_VERSION,
  STORY_EDIT_SCHEMA_VERSION,
  STORY_ORACLE_POLICY_VERSION,
  StorageKernelError,
  UnconfiguredDeliveryDeploymentAdapter,
  UnconfiguredDeliveryVerificationAdapter,
  WORKFLOW_DEFINITION_ERROR_CODES,
  WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES,
  WORKFLOW_DEFINITION_RESPONSIBILITIES,
  WORKFLOW_DEFINITION_SCHEMA_VERSION,
  WORKFLOW_EVIDENCE_KINDS,
  WORKFLOW_EVIDENCE_LIMITS,
  WORKFLOW_EVIDENCE_OUTCOMES,
  WORKFLOW_GOVERNANCE_MODE,
  WORKFLOW_HUMAN_INTERACTION_STATUSES,
  WORKFLOW_STATUSES,
  WORKFLOW_TRANSITIONS,
  WORK_UNIT_ENVIRONMENT_ERROR_CODES,
  WORK_UNIT_ENVIRONMENT_STATES,
  WorkUnitEnvironmentController,
  WorkUnitEnvironmentService,
  WorkUnitEnvironmentStore,
  WorkUnitEnvironmentStoreError,
  WorkflowDefinitionRepositoryError,
  WorkflowHumanInteractionRepositoryError,
  WorkflowInstanceRepositoryError,
  acquireProcessLock,
  adaptForgeRunToWorkflowProjection,
  agentStepAttemptKey,
  appendDurableJson,
  appendForgeLedgerEvent,
  applyCommentBudget,
  applyDeliveryPromotion,
  applyHumanCheckpointOpen,
  applyWorkflowTransition,
  artifactEvidenceIdempotencyKey,
  asRuntimeExecutionId,
  assertSupportedFormatVersion,
  atomicTemporaryPath,
  atomicWriteJson,
  bindFactoryStepResult,
  buildOracleCommand,
  buildQuarantineRecord,
  canonicalAgentStepResultJson,
  canonicalDeliveryHash,
  canonicalG1,
  canonicalHumanInteractionInput,
  canonicalJson,
  canonicalOracleDefinition,
  canonicalize2 as canonicalize,
  canonicalizeAgentStepResult,
  canonicalizeWorkflowDefinition,
  classifyOracleExecution,
  classifyOracleResult,
  clearActiveCaseId,
  computeCanonicalHash,
  computeForgeSpecHash,
  computeG1EvidenceSetHash,
  computeStorySpecHash,
  countTaskOutcomes,
  createAgentOsHttpCaseTerminator,
  createAgentOsHttpClient,
  createAgentOsRuntimeAdapter,
  createCase,
  createEpicRun,
  createFilesystemAgentStepAttemptRepository,
  createFilesystemAgentStepResultRepository,
  createFilesystemOracleDefinitionSource,
  createFilesystemOracleExecutionRepository,
  createFilesystemWorkflowDefinitionRepository,
  createFilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowHumanInteractionRepository,
  createFilesystemWorkflowInstanceRepository,
  createKeyedLock,
  createRun,
  createShutdownController,
  createWorkflowEvidence,
  createWorkflowInstance,
  defaultDeliveryDefinition,
  defaultRunStoreRoot,
  deliveryScopeHash,
  deliverySemanticHash,
  deriveDeliveryOperationIdentity,
  diffSince,
  diffSnapshots,
  endCurrentRunOnce,
  endRun,
  ensureForgeRunStore,
  evaluateDeliveryOperationPolicy,
  evaluateDeliveryPromotion,
  evaluateG2,
  evaluateG2US,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  evaluateWorkflowTransition,
  executeAgentStepAttempt,
  executeOracle,
  executeStoryAnalysis,
  executeStoryEdit,
  executeStoryOracles,
  extractAdfText,
  extractFrontmatter,
  extractOracleDiagnostics,
  extractTicketId,
  failPhase,
  fetchJiraComments,
  fetchJiraTicket,
  getActiveCaseId,
  getActiveCaseIds,
  getAgentOsRuntimeAdapter,
  getCase,
  getCurrentRun,
  handleDeliveryRequest,
  handleWorkUnitEnvironmentRequest,
  hashAgentBrief,
  hashAgentStepResult,
  hashDeliveryDefinition,
  hashOracleDefinition,
  hashStorySpec,
  hashStructuredAgentResult,
  hashWorkflowDefinition,
  humanInteractionSemanticHash,
  inspectNxProject,
  installSigtermHandler,
  isAgentStepAttemptStatus,
  isAgentStepAttemptTerminal,
  isAllowedStoryOracleRequestBody,
  isInfrastructureIdentity,
  isNotFoundError,
  isSafeAgentStepResultId,
  isValidAgentStepAttemptInstant,
  isWithin,
  killCase,
  listAgents,
  listEvents,
  listForgeRunProjections,
  listIntegrations,
  loadForgeSpec,
  materializeInlineArtifact,
  normalizeDeliveryAdapterOutcome,
  normalizeDeliveryOperationRequest,
  normalizeDiagnosticLine,
  normalizeForgeRunYaml,
  normalizeSprintStatus,
  normalizeStoryFrontmatterFields,
  openedInteractionRevision,
  oracleArtifact,
  oracleRootIdentity,
  parseAgentStepResult,
  parseForgeLedger,
  parseForgeLedgerLines,
  parseForgeSpecFrontmatter,
  parseFrontBuildHostMap,
  parseStorySpecFrontmatter,
  parseYamlMinimal,
  passPhase,
  postMessage,
  preflightAgent,
  preflightReadOnlyWorkspace,
  preflightWorkspace,
  preflightWritableWorkspace,
  processExit,
  projectForgeRun,
  readForgeRunYaml,
  readForgeRunYamlStrict,
  readFormatVersion,
  readJsonLines,
  readSprintStatus,
  readStoryFrontmatter,
  readStorySpec,
  recordHumanDecision,
  registerActiveCase,
  resolveBuildHosts,
  resolveDeliveryVerificationRequest,
  resolveForgeRoots,
  resolveFrontOraclePlan,
  resolveOwnerProjectConfigs,
  resolveOwnerProjects,
  runAgentTurn,
  runBaselineOracle,
  runCommand,
  safeEqual,
  sanitizeForgeSyncAttribution,
  setActiveCaseId,
  sha256,
  snapshotDiff,
  startPhase,
  storageErrorCode,
  stripAnsi,
  syncDirectory,
  syncForgeWorkflowProjection,
  transitionScopeHash,
  transitionSemanticHash,
  unavailableDeliveryTargetRegistry,
  unregisterActiveCase,
  validateAgentStepAttempt,
  validateAgentStepResultBusiness,
  validateCanonicalAbsolutePath,
  validateDeliveryDefinition,
  validateDeliveryEvidence,
  validateDeliveryOperationRecord,
  validateDeliveryOperationTransition,
  validateDeliveryPromotionRequest,
  validateForgeRunStructure,
  validateForgeSpecSchema,
  validateGitRef,
  validateHumanInteractionOpenInput,
  validateInheritance,
  validateIsoInstant,
  validateNamespaceId,
  validateOracleDefinition,
  validateOracleRoot,
  validateStorySpec,
  validateStrictForgeYamlSyntax,
  validateWorkUnitEnvironment,
  validateWorkflowDefinition,
  validateWorkflowEvidenceInput,
  validateWorkflowTransitionRequest,
  withFormatVersion,
  withProcessLock,
  workflowStartCommandHash,
  wrapStorageError,
  writeStoryAnalysisArtifact
};
