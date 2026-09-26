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
  void idempotencyKey;
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
    void workflowId;
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
    void reply;
    void actorId;
    void evidenceId;
    void transitionRequestId;
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

// ../src/adapters/persistence/filesystem-work-environment-repository.ts
var FilesystemWorkEnvironmentRepository = class {
  constructor(store) {
    this.store = store;
  }
  paths(namespaceId, environmentId) {
    return this.store.paths(namespaceId, environmentId);
  }
  read(namespaceId, environmentId) {
    return this.store.read(namespaceId, environmentId);
  }
  list(namespaceId, filter) {
    return this.store.list(namespaceId, filter);
  }
  reserve(environment) {
    return this.store.reserve(environment);
  }
  transition(namespaceId, environmentId, next, options) {
    return this.store.transition(namespaceId, environmentId, next, options);
  }
};
function createFilesystemWorkEnvironmentRepository(store) {
  return new FilesystemWorkEnvironmentRepository(store);
}

// ../src/adapters/persistence/filesystem-delivery-repository.ts
var FilesystemDeliveryRepository = class {
  constructor(store) {
    this.store = store;
  }
  read(namespaceId, deliveryId) {
    return this.store.read(namespaceId, deliveryId);
  }
  create(input) {
    return this.store.create(input);
  }
  promote(input) {
    return this.store.promote(input);
  }
  readWithOperations(namespaceId, deliveryId) {
    return this.store.readWithOperations(namespaceId, deliveryId);
  }
  inspectDeliveryOperations(namespaceId, deliveryId) {
    return this.store.inspectDeliveryOperations(namespaceId, deliveryId);
  }
  createRollbackRequest(input) {
    return this.store.createRollbackRequest(input);
  }
  approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval) {
    return this.store.approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval);
  }
  createDeliveryOperation(input) {
    return this.store.createDeliveryOperation(input);
  }
  recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options) {
    return this.store.recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options);
  }
  startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation) {
    return this.store.startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation);
  }
  reconcileDeliveryOperation(namespaceId, deliveryId, operationId, observation) {
    return this.store.reconcileDeliveryOperation(namespaceId, deliveryId, operationId, observation);
  }
  hasIndeterminateOperation(namespaceId, deliveryId) {
    return this.store.hasIndeterminateOperation(namespaceId, deliveryId);
  }
  updateSnapshot(namespaceId, deliveryId, patch, operationInput) {
    return this.store.updateSnapshot(namespaceId, deliveryId, patch, operationInput);
  }
};
function createFilesystemDeliveryRepository(store) {
  return new FilesystemDeliveryRepository(store);
}

// ../src/adapters/persistence/sql/db.ts
var DEFAULT_ORGANIZATION_ID = "default";
var DEFAULT_WORKSTREAM_ID = "default";
function resolveSqlDatabaseConfig(env = process.env) {
  const port = Number.parseInt(env.PGPORT ?? "", 10);
  const maxConnections = Number.parseInt(env.PGPOOL_MAX ?? "", 10);
  return {
    host: env.PGHOST ?? "localhost",
    port: Number.isFinite(port) ? port : 5432,
    database: env.PGDATABASE ?? "coday_factory",
    user: env.PGUSER ?? "factory",
    password: env.PGPASSWORD ?? "factory_dev_pass",
    maxConnections: Number.isFinite(maxConnections) && maxConnections > 0 ? maxConnections : 10,
    ssl: env.PGSSL === "true"
  };
}
async function loadDriver(specifier) {
  const imported = await import(specifier);
  const module = imported.default ?? imported;
  if (typeof module?.Pool !== "function") throw new Error(`SQL_DRIVER_INVALID: ${specifier}`);
  return module;
}
async function createPgPoolClient(config = resolveSqlDatabaseConfig(), driver = "pg") {
  const pg = await loadDriver(driver);
  const pool = new pg.Pool({
    host: config.host,
    port: config.port,
    database: config.database,
    user: config.user,
    password: config.password,
    max: config.maxConnections,
    ssl: config.ssl ? { rejectUnauthorized: false } : false
  });
  return {
    query: (text2, params) => pool.query(text2, params)
  };
}
function parseJsonColumn(value) {
  if (typeof value === "string") return JSON.parse(value);
  return value;
}

// ../src/adapters/persistence/sql/sql-workflow-definition-repository.ts
var SELECT_COLUMNS = "organization_id, workstream_id, workflow_type, version, definition_hash, definition_json";
function compareVersions(left, right) {
  const parse = (value) => value.split(".").map((part) => Number.parseInt(part, 10) || 0);
  const a = parse(left);
  const b = parse(right);
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    const difference = (a[index] ?? 0) - (b[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return left.localeCompare(right);
}
function toDefinition(row) {
  return {
    ...parseJsonColumn(row.definition_json),
    definitionHash: row.definition_hash
  };
}
var SqlWorkflowDefinitionRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? null;
  }
  #inScope(row) {
    return this.#workstreamId === null ? row.workstream_id === null || row.workstream_id === void 0 : row.workstream_id === this.#workstreamId;
  }
  async list() {
    const { rows } = await this.#client.query(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions WHERE organization_id = $1`,
      [this.#organizationId]
    );
    return rows.filter((row) => this.#inScope(row)).map(toDefinition).sort((left, right) => {
      const byType = left.workflowType.localeCompare(right.workflowType);
      return byType !== 0 ? byType : compareVersions(left.version, right.version);
    });
  }
  async get(workflowType, version) {
    const { rows } = await this.#client.query(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions
       WHERE organization_id = $1 AND workflow_type = $2 AND version = $3`,
      [this.#organizationId, workflowType, version]
    );
    const row = rows.find((candidate) => this.#inScope(candidate));
    return row ? toDefinition(row) : null;
  }
  async resolveUnique(workflowType) {
    const { rows } = await this.#client.query(
      `SELECT ${SELECT_COLUMNS} FROM workflow_definitions
       WHERE organization_id = $1 AND workflow_type = $2`,
      [this.#organizationId, workflowType]
    );
    const scoped = rows.filter((row) => this.#inScope(row)).map(toDefinition);
    if (scoped.length === 0)
      throw new WorkflowDefinitionRepositoryError(
        WORKFLOW_DEFINITION_REPOSITORY_ERROR_CODES.WORKFLOW_DEFINITION_NOT_FOUND,
        { workflowType }
      );
    return scoped.reduce(
      (latest, candidate) => compareVersions(candidate.version, latest.version) > 0 ? candidate : latest
    );
  }
};
function createSqlWorkflowDefinitionRepository(client, options = {}) {
  return new SqlWorkflowDefinitionRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-workflow-instance-repository.ts
var INSTANCE_COLUMNS = [
  "organization_id",
  "workstream_id",
  "namespace_id",
  "workflow_id",
  "revision",
  "status",
  "instance_json",
  "projection_json",
  "creation_command_hash"
].join(", ");
var INSTANCE_INSERT_COLUMNS = `${INSTANCE_COLUMNS}, created_at, updated_at`;
var ACTIVE_STATUS = "active";
var REMOVED_STATUS = "removed";
function readSnapshot(row) {
  return {
    instance: parseJsonColumn(row.instance_json),
    projection: parseJsonColumn(row.projection_json)
  };
}
var SqlWorkflowInstanceRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  async #select(namespaceId, workflowId) {
    const { rows } = await this.#client.query(
      `SELECT ${INSTANCE_COLUMNS} FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId]
    );
    return rows[0] ?? null;
  }
  async list(namespaceId) {
    const { rows } = await this.#client.query(
      `SELECT ${INSTANCE_COLUMNS} FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND status = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, ACTIVE_STATUS]
    );
    return rows.map((row) => parseJsonColumn(row.projection_json)).sort((left, right) => left.workflowId.localeCompare(right.workflowId));
  }
  async get(namespaceId, workflowId) {
    const row = await this.#select(namespaceId, workflowId);
    if (!row || row.status !== ACTIVE_STATUS) return null;
    return readSnapshot(row);
  }
  async create(namespaceId, command, definition, controllerExecution) {
    const existing = await this.#select(namespaceId, command.workflowId);
    if (existing) {
      const commandHash = workflowStartCommandHash(command, definition);
      if (existing.status !== ACTIVE_STATUS)
        throw new WorkflowInstanceRepositoryError("WORKFLOW_REMOVED", { workflowId: command.workflowId });
      if (existing.creation_command_hash === commandHash) return readSnapshot(existing);
      throw new WorkflowInstanceRepositoryError("WORKFLOW_IDENTITY_CONFLICT", {
        workflowId: command.workflowId
      });
    }
    const created = createWorkflowInstance(command, definition, controllerExecution);
    const observedAt = created.instance.createdAt;
    await this.#client.query(
      `INSERT INTO workflow_instances
         (${INSTANCE_INSERT_COLUMNS})
       VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::jsonb, $9, $10, $11)
       ON CONFLICT DO NOTHING`,
      [
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        command.workflowId,
        1,
        ACTIVE_STATUS,
        JSON.stringify(created.instance),
        JSON.stringify(created.projection),
        created.creationCommandHash,
        observedAt,
        observedAt
      ]
    );
    const stored = await this.#select(namespaceId, command.workflowId);
    if (!stored)
      throw new WorkflowInstanceRepositoryError("WORKFLOW_INSTANCE_CREATE_FAILED", {
        workflowId: command.workflowId
      });
    return readSnapshot(stored);
  }
  async transition(namespaceId, workflowId, transition) {
    const input = transition ?? {};
    const request = input.request;
    const definition = input.definition;
    const current = await this.#select(namespaceId, workflowId);
    if (!current || current.status !== ACTIVE_STATUS)
      throw new WorkflowInstanceRepositoryError("WORKFLOW_NOT_FOUND", { workflowId });
    const snapshot = readSnapshot(current);
    const evaluationSnapshot = {
      ...snapshot.instance,
      instance: snapshot.instance,
      projection: snapshot.projection,
      revision: snapshot.instance.revision
    };
    const execution2 = {
      ...input.execution ?? {},
      namespaceId
    };
    const evidence = input.evidence ?? [];
    const policy = typeof input.policy === "function" ? input.policy : evaluateWorkflowTransition;
    const decision = policy({ request, snapshot: evaluationSnapshot, definition, evidence, execution: execution2 });
    if (!decision?.allowed)
      throw new WorkflowInstanceRepositoryError(
        decision?.code ?? "TRANSITION_REJECTED",
        decision?.missingEvidence ? { missingEvidence: decision.missingEvidence } : {},
        decision
      );
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    const applied = applyWorkflowTransition(evaluationSnapshot, definition, request, observedAt);
    const expectedRevision = typeof request?.expectedRevision === "number" ? request.expectedRevision : snapshot.instance.revision;
    const { rowCount } = await this.#client.query(
      `UPDATE workflow_instances
         SET revision = $1, instance_json = $2::jsonb, projection_json = $3::jsonb, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND workflow_id = $8
         AND revision = $9 AND status = $10`,
      [
        applied.revision,
        JSON.stringify(applied.instance),
        JSON.stringify(applied.projection),
        observedAt,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        workflowId,
        expectedRevision,
        ACTIVE_STATUS
      ]
    );
    if (!rowCount) throw new WorkflowInstanceRepositoryError("REVISION_CONFLICT", { workflowId, expectedRevision });
    return {
      instance: applied.instance,
      projection: applied.projection
    };
  }
  async #setStatus(namespaceId, workflowId, from, to, actor, failureCode) {
    void actor;
    const { rowCount } = await this.#client.query(
      `UPDATE workflow_instances
         SET status = $1, updated_at = $2
       WHERE organization_id = $3 AND workstream_id = $4 AND namespace_id = $5 AND workflow_id = $6 AND status = $7`,
      [to, (/* @__PURE__ */ new Date()).toISOString(), this.#organizationId, this.#workstreamId, namespaceId, workflowId, from]
    );
    if (!rowCount) throw new WorkflowInstanceRepositoryError(failureCode, { workflowId });
  }
  async remove(namespaceId, workflowId, actor) {
    await this.#setStatus(namespaceId, workflowId, ACTIVE_STATUS, REMOVED_STATUS, actor, "WORKFLOW_NOT_FOUND");
  }
  async restore(namespaceId, workflowId, actor) {
    await this.#setStatus(namespaceId, workflowId, REMOVED_STATUS, ACTIVE_STATUS, actor, "WORKFLOW_NOT_FOUND");
  }
  async purge(namespaceId, workflowId, actor) {
    void actor;
    await this.#client.query(
      `DELETE FROM workflow_instances
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId]
    );
  }
};
function createSqlWorkflowInstanceRepository(client, options = {}) {
  return new SqlWorkflowInstanceRepository(client, options);
}

// ../src/adapters/persistence/sql/unit-of-work.ts
async function withTransaction(client, work) {
  await client.query("BEGIN");
  try {
    const result = await work(client);
    await client.query("COMMIT");
    return result;
  } catch (error2) {
    await client.query("ROLLBACK");
    throw error2;
  }
}

// ../src/adapters/persistence/sql/sql-workflow-evidence-repository.ts
import { createHash as createHash7 } from "node:crypto";
var WorkflowEvidenceStoreError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "WorkflowEvidenceStoreError";
    this.code = code;
    this.details = details;
  }
};
var EVIDENCE_COLUMNS = [
  "organization_id",
  "workstream_id",
  "namespace_id",
  "workflow_id",
  "evidence_id",
  "evidence_type",
  "source",
  "producer",
  "payload",
  "created_at"
].join(", ");
var EVIDENCE_INSERT_COLUMNS = `${EVIDENCE_COLUMNS}`;
function semanticHash(value) {
  return createHash7("sha256").update(JSON.stringify(value)).digest("hex");
}
async function selectEvidence(client, organizationId, workstreamId, namespaceId, workflowId) {
  const { rows } = await client.query(
    `SELECT ${EVIDENCE_COLUMNS} FROM workflow_evidence
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
    [organizationId, workstreamId, namespaceId, workflowId]
  );
  return rows.map((row) => parseJsonColumn(row.payload));
}
var SqlWorkflowEvidenceRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  async list(namespaceId, storageId, filter) {
    const items = await selectEvidence(this.#client, this.#organizationId, this.#workstreamId, namespaceId, storageId);
    return items.filter((item) => !filter?.stepId || item.stepId === filter.stepId).sort((left, right) => {
      const chronology = left.observedAt.localeCompare(right.observedAt);
      return chronology !== 0 ? chronology : left.evidenceId.localeCompare(right.evidenceId);
    });
  }
  async record(namespaceId, storageId, input, source) {
    return withTransaction(this.#client, async (tx) => {
      const scope = {
        namespaceId,
        workflowId: input.workflowId,
        stepId: input.stepId,
        source,
        idempotencyKey: input.idempotencyKey
      };
      const scopeHash = semanticHash(scope);
      const fingerprint = semanticHash({ ...input, idempotencyKey: void 0 });
      const existing = await selectEvidence(tx, this.#organizationId, this.#workstreamId, namespaceId, storageId);
      if (input.idempotencyKey) {
        const prior = existing.find((item) => item.idempotency?.scopeHash === scopeHash);
        if (prior) {
          if (prior.idempotency?.semanticHash !== fingerprint)
            throw new WorkflowEvidenceStoreError("IDEMPOTENCY_KEY_COLLISION");
          return { created: false, idempotent: true, evidence: prior };
        }
      }
      const evidence = createWorkflowEvidence(input, namespaceId, source);
      const stored = input.idempotencyKey ? { ...evidence, idempotency: { scopeHash, semanticHash: fingerprint } } : { ...evidence };
      await tx.query(
        `INSERT INTO workflow_evidence (${EVIDENCE_INSERT_COLUMNS}) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          input.workflowId,
          evidence.evidenceId,
          input.kind,
          source.kind ?? source.runtimeId ?? "unknown",
          source.agentId ?? source.actorId ?? "system",
          JSON.stringify(stored),
          evidence.observedAt
        ]
      );
      return { created: true, idempotent: false, evidence: stored };
    });
  }
};
function createSqlWorkflowEvidenceRepository(client, options = {}) {
  return new SqlWorkflowEvidenceRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-workflow-human-interaction-repository.ts
import { randomUUID as randomUUID4 } from "node:crypto";
var WorkflowHumanInteractionError = class extends Error {
  code;
  decision;
  constructor(code, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "WorkflowHumanInteractionError";
    this.code = code;
  }
};
var WorkflowHumanInteractionRepositoryError2 = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "WorkflowHumanInteractionRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var EVENT_COLUMNS = [
  "organization_id",
  "workstream_id",
  "namespace_id",
  "workflow_id",
  "interaction_id",
  "event_id",
  "event_type",
  "actor_id",
  "payload",
  "created_at"
].join(", ");
function dbStatus(status) {
  return status === "replied" ? "answered" : "waiting";
}
async function selectEvents(client, organizationId, workstreamId, namespaceId, workflowId) {
  const { rows } = await client.query(
    `SELECT ${EVENT_COLUMNS} FROM human_interaction_events
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4`,
    [organizationId, workstreamId, namespaceId, workflowId]
  );
  return rows.slice().sort((left, right) => {
    if (left.created_at < right.created_at) return -1;
    if (left.created_at > right.created_at) return 1;
    return 0;
  }).map((row) => parseJsonColumn(row.payload));
}
function projectEvents(events) {
  const projected = /* @__PURE__ */ new Map();
  for (const event of events) {
    if (event.event === "interaction_opening") {
      const interaction = event.interaction;
      if (!interaction?.interactionId || projected.has(interaction.interactionId))
        throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
      projected.set(interaction.interactionId, { ...interaction, status: "opening" });
    } else if (event.event === "interaction_opened") {
      const interaction = event.interaction;
      const current = interaction?.interactionId ? projected.get(interaction.interactionId) : void 0;
      const revision = openedInteractionRevision(event);
      if (current?.status === "opening") {
        const validRevision = current.interactionType === "retry" ? revision === current.expectedRevision : (revision ?? 0) > current.expectedRevision;
        if (!Number.isSafeInteger(revision) || !validRevision)
          throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
        projected.set(current.interactionId, { ...current, status: "open", revision });
      } else if (!current) {
        if (!interaction?.interactionId || !Number.isSafeInteger(revision) || revision < 1)
          throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
        projected.set(interaction.interactionId, { ...interaction, status: "open", revision });
      } else {
        throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
      }
    } else if (event.event === "interaction_open_aborted") {
      const interactionId = event.interactionId;
      const current = interactionId ? projected.get(interactionId) : void 0;
      if (!current || current.status !== "opening")
        throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
      const aborted = { ...current, status: "aborted" };
      if (event.errorCode !== void 0) aborted.errorCode = event.errorCode;
      projected.set(interactionId, aborted);
    } else if (event.event === "interaction_transitioned") {
      const interactionId = event.interactionId;
      const current = interactionId ? projected.get(interactionId) : void 0;
      if (!current || current.status !== "open") throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
      const replied = { ...current, status: "replied" };
      if (event.reply !== void 0) replied.reply = event.reply;
      if (event.actorId !== void 0) replied.actorId = event.actorId;
      if (event.repliedAt !== void 0) replied.repliedAt = event.repliedAt;
      if (event.evidenceId !== void 0) replied.evidenceId = event.evidenceId;
      if (event.transitionRequestId !== void 0) replied.transitionRequestId = event.transitionRequestId;
      if (event.revision !== void 0) replied.revision = event.revision;
      projected.set(interactionId, replied);
    } else {
      throw new WorkflowHumanInteractionError("CORRUPT_INTERACTION_STORAGE");
    }
  }
  return [...projected.values()];
}
function interactionIdOf(event) {
  return event.interaction?.interactionId ?? event.interactionId ?? "";
}
async function insertEvent(client, organizationId, workstreamId, namespaceId, workflowId, event, createdAt) {
  await client.query(
    `INSERT INTO human_interaction_events (${EVENT_COLUMNS}) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
    [
      organizationId,
      workstreamId,
      namespaceId,
      workflowId,
      interactionIdOf(event),
      randomUUID4(),
      event.event,
      event.actorId ?? "system",
      JSON.stringify(event),
      createdAt
    ]
  );
}
async function upsertInteractionRow(client, organizationId, workstreamId, namespaceId, workflowId, record2, timestamp) {
  const status = dbStatus(record2.status);
  const revision = Number.isSafeInteger(record2.revision) && record2.revision >= 1 ? record2.revision : 1;
  const { rows } = await client.query(
    `SELECT interaction_id FROM human_interactions
     WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4 AND interaction_id = $5`,
    [organizationId, workstreamId, namespaceId, workflowId, record2.interactionId]
  );
  if (rows.length > 0) {
    await client.query(
      `UPDATE human_interactions SET status = $1, revision = $2, payload = $3::jsonb, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND workflow_id = $8 AND interaction_id = $9`,
      [
        status,
        revision,
        JSON.stringify(record2),
        timestamp,
        organizationId,
        workstreamId,
        namespaceId,
        workflowId,
        record2.interactionId
      ]
    );
    return;
  }
  await client.query(
    `INSERT INTO human_interactions
       (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, interaction_type, status, revision, payload, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10, $11)`,
    [
      organizationId,
      workstreamId,
      namespaceId,
      workflowId,
      record2.interactionId,
      record2.interactionType ?? record2.kind,
      status,
      revision,
      JSON.stringify(record2),
      timestamp,
      timestamp
    ]
  );
}
async function insertOutboxEvent(client, organizationId, workstreamId, eventType, payload) {
  await client.query(
    `INSERT INTO outbox_events (organization_id, id, workstream_id, event_type, payload, status, attempts, created_at)
     VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8)`,
    [
      organizationId,
      randomUUID4(),
      workstreamId,
      eventType,
      JSON.stringify(payload),
      "pending",
      0,
      (/* @__PURE__ */ new Date()).toISOString()
    ]
  );
}
async function insertEvidenceRecord(client, organizationId, workstreamId, evidence) {
  await client.query(
    `INSERT INTO workflow_evidence
       (organization_id, workstream_id, namespace_id, workflow_id, evidence_id, evidence_type, source, producer, payload, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
    [
      organizationId,
      workstreamId,
      evidence.namespaceId,
      evidence.workflowId,
      evidence.evidenceId,
      evidence.kind,
      evidence.source?.kind ?? evidence.source?.runtimeId ?? "unknown",
      evidence.source?.agentId ?? evidence.source?.actorId ?? "system",
      JSON.stringify(evidence),
      evidence.observedAt
    ]
  );
}
var SqlWorkflowHumanInteractionRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  #lastMillis = 0;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  /** Strictly increasing ISO timestamps so the append-only log keeps its insertion order. */
  #timestamp() {
    const now = Math.max(Date.now(), this.#lastMillis + 1);
    this.#lastMillis = now;
    return new Date(now).toISOString();
  }
  async #events(client, namespaceId, workflowId) {
    return selectEvents(client, this.#organizationId, this.#workstreamId, namespaceId, workflowId);
  }
  async #project(client, namespaceId, workflowId) {
    return projectEvents(await this.#events(client, namespaceId, workflowId));
  }
  async list(namespaceId, storageId, options) {
    const records = await this.#project(this.#client, namespaceId, storageId);
    return records.filter((record2) => !options?.openOnly || record2.status === "open").sort((left, right) => {
      const chronology = left.openedAt.localeCompare(right.openedAt);
      return chronology !== 0 ? chronology : left.interactionId.localeCompare(right.interactionId);
    });
  }
  async events(namespaceId, storageId) {
    return this.#events(this.#client, namespaceId, storageId);
  }
  async reconcileOpen(namespaceId, storageId, input, snapshot, options) {
    const workflowFacts = options?.workflowFacts ?? [];
    const outcome = await withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId);
      const candidates = items.filter(
        (item) => item.workflowId === input.workflowId && item.stepId === input.stepId && (item.status === "opening" || item.status === "aborted")
      );
      if (candidates.length === 0) throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_NOT_FOUND");
      if (candidates.length !== 1) throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_AMBIGUOUS");
      const opening = candidates[0];
      if (opening.semanticHash !== humanInteractionSemanticHash(input))
        throw new WorkflowHumanInteractionError("IDEMPOTENCY_KEY_COLLISION");
      const view = snapshot ?? void 0;
      const step = view?.instance?.steps?.find((candidate) => candidate.id === opening.stepId);
      if (!Number.isSafeInteger(view?.revision) || !step)
        throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_SNAPSHOT_INVALID");
      if (step.status === "ready") {
        if (view?.revision !== opening.expectedRevision)
          throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_REVISION_DIVERGED");
        if (opening.status === "opening") {
          await insertEvent(
            tx,
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            opening.workflowId,
            {
              event: "interaction_open_aborted",
              interactionId: opening.interactionId,
              errorCode: "RECOVERED_OPENING_WITH_READY_STEP",
              recoveredAt: (/* @__PURE__ */ new Date()).toISOString()
            },
            this.#timestamp()
          );
        }
        return { reopen: true, interaction: opening };
      }
      if (opening.status !== "opening") throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_STATE_DIVERGED");
      if (step.status !== "waiting_human")
        throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_STATE_DIVERGED");
      const revision = view?.revision;
      if (revision <= opening.expectedRevision)
        throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_REVISION_DIVERGED");
      const provesTransition = workflowFacts.some(
        (fact) => fact?.kind === "transition_accepted" && fact?.revision === revision && fact?.transitionDelta?.steps?.some(
          (change) => change.stepId === opening.stepId && change.status?.from === "ready" && change.status?.to === "waiting_human"
        )
      );
      if (!provesTransition) throw new WorkflowHumanInteractionError("INTERACTION_RECOVERY_TRANSITION_UNPROVEN");
      const opened = { ...opening, status: "open", revision };
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        opening.workflowId,
        {
          event: "interaction_opened",
          interaction: { ...opening, revision },
          revision,
          recovery: "authoritative-workflow-transition"
        },
        this.#timestamp()
      );
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        opening.workflowId,
        opened,
        this.#timestamp()
      );
      return { reopen: false, interaction: opened };
    });
    if (outcome.reopen)
      throw new WorkflowHumanInteractionRepositoryError2("INTERACTION_RECOVERY_NOT_FOUND", { namespaceId, storageId });
    return outcome.interaction;
  }
  async recordOpen(namespaceId, storageId, input, options) {
    const transition = options?.transition;
    if (!transition)
      throw new WorkflowHumanInteractionRepositoryError2("HUMAN_INTERACTION_TRANSITION_REQUIRED", {
        namespaceId,
        storageId
      });
    const normalized = validateHumanInteractionOpenInput(input);
    if (!normalized) throw new WorkflowHumanInteractionError("INVALID_INTERACTION");
    const hash7 = humanInteractionSemanticHash(normalized);
    return withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId);
      const prior = items.find((item) => item.idempotencyKey === normalized.idempotencyKey);
      if (prior) {
        if (prior.semanticHash !== hash7) throw new WorkflowHumanInteractionError("IDEMPOTENCY_KEY_COLLISION");
        if (prior.status === "open") return { created: false, idempotent: true, interaction: prior };
        throw new WorkflowHumanInteractionError("INTERACTION_OPEN_INDETERMINATE");
      }
      if (items.some(
        (item) => item.workflowId === normalized.workflowId && item.stepId === normalized.stepId && (item.status === "opening" || item.status === "open")
      ))
        throw new WorkflowHumanInteractionError("INTERACTION_ALREADY_OPEN");
      const interaction = {
        interactionId: normalized.interactionId ?? randomUUID4(),
        workflowId: normalized.workflowId,
        stepId: normalized.stepId,
        expectedRevision: normalized.expectedRevision,
        kind: normalized.kind,
        prompt: normalized.prompt,
        actions: normalized.actions,
        idempotencyKey: normalized.idempotencyKey,
        semanticHash: hash7,
        openedAt: (/* @__PURE__ */ new Date()).toISOString()
      };
      if (normalized.interactionType !== void 0) interaction.interactionType = normalized.interactionType;
      if (normalized.reasonCode !== void 0) interaction.reasonCode = normalized.reasonCode;
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        { event: "interaction_opening", interaction },
        this.#timestamp()
      );
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        interaction,
        this.#timestamp()
      );
      const result = await transition(interaction);
      if (!result?.ok) {
        const error2 = new WorkflowHumanInteractionError(result?.error?.code ?? "INVALID_INTERACTION_TRANSACTION");
        if (result?.decision !== void 0) error2.decision = result.decision;
        throw error2;
      }
      const revision = result.snapshot?.revision;
      const opened = { ...interaction, status: "open" };
      if (typeof revision === "number") opened.revision = revision;
      const openedEvent = {
        event: "interaction_opened",
        interaction: { ...interaction, ...typeof revision === "number" ? { revision } : {} }
      };
      if (typeof revision === "number") openedEvent.revision = revision;
      if (result.requestId !== void 0) openedEvent.transitionRequestId = result.requestId;
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        openedEvent,
        this.#timestamp()
      );
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        normalized.workflowId,
        opened,
        this.#timestamp()
      );
      await insertOutboxEvent(tx, this.#organizationId, this.#workstreamId, "human_interaction.opened", {
        namespaceId,
        workflowId: normalized.workflowId,
        interactionId: interaction.interactionId,
        revision,
        interaction: opened
      });
      const idempotent = Boolean(result.idempotent);
      return { created: !idempotent, idempotent, interaction: opened };
    });
  }
  async recordTransition(namespaceId, storageId, interactionId, reply, actorId, evidenceId, transitionRequestId, options) {
    const action = options?.action;
    if (!action)
      throw new WorkflowHumanInteractionRepositoryError2("HUMAN_INTERACTION_ACTION_REQUIRED", {
        namespaceId,
        storageId,
        interactionId
      });
    void reply;
    void actorId;
    void evidenceId;
    void transitionRequestId;
    return withTransaction(this.#client, async (tx) => {
      const items = await this.#project(tx, namespaceId, storageId);
      const interaction = items.find((item) => item.interactionId === interactionId);
      if (!interaction) throw new WorkflowHumanInteractionError("INTERACTION_NOT_FOUND");
      if (interaction.status !== "open") throw new WorkflowHumanInteractionError("INTERACTION_CLOSED");
      const result = await action(interaction);
      if (!result?.transition?.ok) throw new WorkflowHumanInteractionError("INVALID_INTERACTION_TRANSACTION");
      const revision = result.transition.snapshot?.revision;
      const replied = { ...interaction, status: "replied" };
      if (result.reply !== void 0) replied.reply = result.reply;
      if (result.actorId !== void 0) replied.actorId = result.actorId;
      if (result.evidenceId !== void 0) replied.evidenceId = result.evidenceId;
      if (typeof revision === "number") replied.revision = revision;
      const transitionedEvent = {
        event: "interaction_transitioned",
        interactionId,
        expectedRevision: interaction.expectedRevision,
        repliedAt: (/* @__PURE__ */ new Date()).toISOString()
      };
      if (result.reply !== void 0) transitionedEvent.reply = result.reply;
      if (result.actorId !== void 0) transitionedEvent.actorId = result.actorId;
      if (result.evidenceId !== void 0) transitionedEvent.evidenceId = result.evidenceId;
      if (result.transition.requestId !== void 0) transitionedEvent.transitionRequestId = result.transition.requestId;
      if (typeof revision === "number") transitionedEvent.revision = revision;
      if (result.evidence)
        await insertEvidenceRecord(tx, this.#organizationId, this.#workstreamId, result.evidence);
      await insertEvent(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        interaction.workflowId,
        transitionedEvent,
        this.#timestamp()
      );
      await upsertInteractionRow(
        tx,
        this.#organizationId,
        this.#workstreamId,
        namespaceId,
        interaction.workflowId,
        replied,
        this.#timestamp()
      );
      await insertOutboxEvent(tx, this.#organizationId, this.#workstreamId, "human_interaction.transitioned", {
        namespaceId,
        workflowId: interaction.workflowId,
        interactionId,
        revision,
        reply: result.reply,
        evidenceId: result.evidenceId,
        transitionRequestId: result.transition.requestId
      });
      return replied;
    });
  }
};
function createSqlWorkflowHumanInteractionRepository(client, options = {}) {
  return new SqlWorkflowHumanInteractionRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-agent-step-attempt-repository.ts
import { randomUUID as randomUUID5 } from "node:crypto";
var ATTEMPT_DB_STATUS = Object.freeze({
  starting: "running",
  running: "running",
  succeeded: "completed",
  failed: "failed",
  indeterminate: "timed_out",
  interrupted: "cancelled"
});
var SqlAgentStepAttemptRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  async #selectAttempt(client, namespaceId, workflowId, storageId, attemptId) {
    const { rows } = await client.query(
      `SELECT revision, payload FROM agent_step_attempts
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND workflow_id = $4 AND step_id = $5 AND attempt_id = $6`,
      [this.#organizationId, this.#workstreamId, namespaceId, workflowId, storageId, attemptId]
    );
    return rows[0] ?? null;
  }
  async list(namespaceId, storageId) {
    const { rows } = await this.#client.query(
      `SELECT created_at, payload FROM agent_step_attempt_events
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId]
    );
    return [...rows].sort((left, right) => String(left.created_at).localeCompare(String(right.created_at))).map((row) => parseJsonColumn(row.payload));
  }
  async append(namespaceId, storageId, attempt) {
    const validated = validateAgentStepAttempt(attempt);
    if (validated.namespaceId !== namespaceId) throw new Error("AGENT_STEP_ATTEMPT_NAMESPACE_MISMATCH");
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectAttempt(tx, namespaceId, validated.workflowId, storageId, validated.attemptId);
      if (existing) {
        const previous = parseJsonColumn(existing.payload);
        if (AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS.some((field) => previous[field] !== validated[field]))
          throw new Error("AGENT_STEP_ATTEMPT_IDENTITY_CONFLICT");
        const allowed = AGENT_STEP_ATTEMPT_TRANSITIONS[previous.status] ?? [];
        if (!allowed.includes(validated.status)) throw new Error("INVALID_AGENT_STEP_ATTEMPT_TRANSITION");
        const nextRevision = existing.revision + 1;
        const { rowCount } = await tx.query(
          `UPDATE agent_step_attempts
             SET status = $1, revision = $2, payload = $3::jsonb, updated_at = $4
           WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7
             AND workflow_id = $8 AND step_id = $9 AND attempt_id = $10 AND revision = $11`,
          [
            ATTEMPT_DB_STATUS[validated.status],
            nextRevision,
            JSON.stringify(validated),
            observedAt,
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            validated.workflowId,
            storageId,
            validated.attemptId,
            existing.revision
          ]
        );
        if (!rowCount) throw new Error("AGENT_STEP_ATTEMPT_REVISION_CONFLICT");
      } else {
        if (validated.status !== "starting") throw new Error("AGENT_STEP_ATTEMPT_MUST_START");
        await tx.query(
          `INSERT INTO agent_step_attempts
             (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, agent_id,
              status, revision, payload, created_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12)`,
          [
            this.#organizationId,
            this.#workstreamId,
            namespaceId,
            validated.workflowId,
            storageId,
            validated.attemptId,
            validated.agentName,
            ATTEMPT_DB_STATUS[validated.status],
            1,
            JSON.stringify(validated),
            observedAt,
            observedAt
          ]
        );
      }
      await tx.query(
        `INSERT INTO agent_step_attempt_events
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            event_id, event_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          validated.workflowId,
          storageId,
          validated.attemptId,
          randomUUID5(),
          validated.status,
          JSON.stringify(validated),
          observedAt
        ]
      );
      return validated;
    });
  }
};
function createSqlAgentStepAttemptRepository(client, options = {}) {
  return new SqlAgentStepAttemptRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-agent-step-result-repository.ts
import { randomBytes as randomBytes4, randomUUID as randomUUID6 } from "node:crypto";
var IDENTITY_FIELDS2 = ["attemptId", "workflowId", "stepId", "namespaceId", "caseId", "agentName"];
var CAPABILITY_MATCH_FIELDS2 = [...IDENTITY_FIELDS2, "briefHash"];
var BRIEF_HASH3 = /^sha256:[0-9a-f]{64}$/;
var CAPABILITY_TYPE = "agent_step_submit";
var DEFAULT_TTL_MS = 15 * 60 * 1e3;
var RESULT_DB_STATUS = Object.freeze({
  PASS: "success",
  FAIL: "failure"
});
var ATTEMPT_TERMINAL_STATUS = Object.freeze({
  PASS: "completed",
  FAIL: "failed"
});
var SqlAgentStepResultRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  #clock;
  #ttlMs;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
    this.#clock = options.clock ?? (() => /* @__PURE__ */ new Date());
    this.#ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
  }
  async #selectCapability(client, namespaceId, storageId, attemptId) {
    const { rows } = await client.query(
      `SELECT step_id, payload FROM result_capabilities
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND step_id = $4 AND attempt_id = $5 AND capability_type = $6`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId, attemptId, CAPABILITY_TYPE]
    );
    const row = rows[0];
    if (!row) return null;
    return { row, record: parseJsonColumn(row.payload) };
  }
  async #selectResult(client, namespaceId, storageId, attemptId) {
    const { rows } = await client.query(
      `SELECT payload FROM agent_step_results
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
         AND step_id = $4 AND attempt_id = $5`,
      [this.#organizationId, this.#workstreamId, namespaceId, storageId, attemptId]
    );
    const row = rows[0];
    return row ? parseJsonColumn(row.payload) : null;
  }
  async #findByToken(tokenHash) {
    const { rows } = await this.#client.query(
      `SELECT step_id, payload FROM result_capabilities
       WHERE organization_id = $1 AND workstream_id = $2 AND capability_type = $3`,
      [this.#organizationId, this.#workstreamId, CAPABILITY_TYPE]
    );
    for (const row of rows) {
      const record2 = parseJsonColumn(row.payload);
      if (record2?.type === "capability-issued" && typeof record2.tokenHash === "string" && safeEqual(record2.tokenHash, tokenHash))
        return { storageId: row.step_id, record: record2 };
    }
    return null;
  }
  async issue(namespaceId, storageId, identity) {
    for (const key of IDENTITY_FIELDS2)
      if (!isSafeAgentStepResultId(identity[key])) throw new Error("INVALID_RESULT_CAPABILITY_IDENTITY");
    if (identity.namespaceId !== namespaceId || !BRIEF_HASH3.test(identity.briefHash ?? ""))
      throw new Error("INVALID_RESULT_CAPABILITY_IDENTITY");
    const token = randomBytes4(32).toString("base64url");
    const now = this.#clock();
    const record2 = {
      type: "capability-issued",
      capabilityId: randomUUID6(),
      tokenHash: sha256(token),
      attemptId: identity.attemptId,
      workflowId: identity.workflowId,
      stepId: identity.stepId,
      namespaceId: identity.namespaceId,
      caseId: identity.caseId,
      agentName: identity.agentName,
      briefHash: identity.briefHash,
      issuedAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + this.#ttlMs).toISOString(),
      submissionBudget: 1
    };
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectCapability(tx, namespaceId, storageId, identity.attemptId);
      if (existing) {
        const same = CAPABILITY_MATCH_FIELDS2.every((field) => existing.record[field] === identity[field]);
        throw new Error(same ? "RESULT_CAPABILITY_ALREADY_ISSUED" : "RESULT_CAPABILITY_IDENTITY_CONFLICT");
      }
      await tx.query(
        `INSERT INTO result_capabilities
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            result_id, capability_id, capability_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          identity.workflowId,
          storageId,
          identity.attemptId,
          "",
          record2.capabilityId,
          CAPABILITY_TYPE,
          JSON.stringify(record2),
          now.toISOString()
        ]
      );
      return { token, expiresAt: record2.expiresAt };
    });
  }
  async submit(token, business, observed = {}) {
    if (!validateAgentStepResultBusiness(business)) return { ok: false, code: "RESULT_SCHEMA_INVALID" };
    const located = await this.#findByToken(sha256(token));
    if (!located) return { ok: false, code: "RESULT_CAPABILITY_INVALID" };
    const issued = located.record;
    if (observed.attemptId !== issued.attemptId || observed.caseId !== issued.caseId || observed.agentName !== issued.agentName)
      return { ok: false, code: "RESULT_IDENTITY_MISMATCH" };
    const resultHash = sha256(canonicalAgentStepResultJson(business));
    return withTransaction(this.#client, async (tx) => {
      const existing = await this.#selectResult(tx, issued.namespaceId, located.storageId, issued.attemptId);
      if (existing)
        return existing.resultHash === resultHash ? { ok: true, idempotent: true, result: existing } : { ok: false, code: "RESULT_SEMANTIC_COLLISION" };
      if (this.#clock().getTime() > Date.parse(issued.expiresAt))
        return { ok: false, code: "RESULT_CAPABILITY_EXPIRED" };
      const result = {
        type: "result-submitted",
        resultId: randomUUID6(),
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
        submittedAt: this.#clock().toISOString(),
        resultHash
      };
      await tx.query(
        `INSERT INTO agent_step_results
           (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id,
            result_id, result_status, semantic_signature, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11)`,
        [
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId,
          result.resultId,
          RESULT_DB_STATUS[result.status],
          resultHash,
          JSON.stringify(result),
          result.submittedAt
        ]
      );
      const attemptRevision = await tx.query(
        `SELECT revision FROM agent_step_attempts
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
           AND workflow_id = $4 AND step_id = $5 AND attempt_id = $6`,
        [
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId
        ]
      );
      await tx.query(
        `UPDATE agent_step_attempts
           SET status = $1, revision = $2, updated_at = $3
         WHERE organization_id = $4 AND workstream_id = $5 AND namespace_id = $6
           AND workflow_id = $7 AND step_id = $8 AND attempt_id = $9`,
        [
          ATTEMPT_TERMINAL_STATUS[result.status],
          (attemptRevision.rows[0]?.revision ?? 0) + 1,
          result.submittedAt,
          this.#organizationId,
          this.#workstreamId,
          issued.namespaceId,
          issued.workflowId,
          located.storageId,
          issued.attemptId
        ]
      );
      await tx.query(
        `INSERT INTO outbox_events
           (organization_id, id, workstream_id, event_type, payload, status, created_at)
         VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7)`,
        [
          this.#organizationId,
          randomUUID6(),
          this.#workstreamId,
          "result_submitted",
          JSON.stringify({
            aggregateType: "agent_step_result",
            attemptId: issued.attemptId,
            resultId: result.resultId,
            status: result.status
          }),
          "pending",
          result.submittedAt
        ]
      );
      return { ok: true, idempotent: false, result };
    });
  }
  async getByAttempt(namespaceId, storageId, attemptId) {
    return this.#selectResult(this.#client, namespaceId, storageId, attemptId);
  }
  async list(namespaceId, storageId) {
    const [capabilities, results] = await Promise.all([
      this.#client.query(
        `SELECT created_at, payload FROM result_capabilities
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
        [this.#organizationId, this.#workstreamId, namespaceId, storageId]
      ),
      this.#client.query(
        `SELECT created_at, payload FROM agent_step_results
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND step_id = $4`,
        [this.#organizationId, this.#workstreamId, namespaceId, storageId]
      )
    ]);
    return [...capabilities.rows, ...results.rows].sort((left, right) => String(left.created_at).localeCompare(String(right.created_at))).map((row) => parseJsonColumn(row.payload));
  }
};
function createSqlAgentStepResultRepository(client, options = {}) {
  return new SqlAgentStepResultRepository(client, options);
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

// ../src/adapters/persistence/sql/sql-oracle-execution-repository.ts
var SqlOracleExecutionRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  #toDefinition(payload) {
    try {
      return validateOracleDefinition(parseJsonColumn(payload));
    } catch {
      return null;
    }
  }
  async list() {
    const { rows } = await this.#client.query(
      `SELECT created_at, payload FROM oracle_executions
       WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    );
    const byId = /* @__PURE__ */ new Map();
    for (const row of [...rows].sort(
      (left, right) => String(left.created_at).localeCompare(String(right.created_at))
    )) {
      const definition = this.#toDefinition(row.payload);
      if (definition && !byId.has(definition.id)) byId.set(definition.id, definition);
    }
    return [...byId.values()];
  }
  async get(id2) {
    const { rows } = await this.#client.query(
      `SELECT created_at, payload FROM oracle_executions
       WHERE organization_id = $1 AND workstream_id = $2 AND oracle_id = $3`,
      [this.#organizationId, this.#workstreamId, id2]
    );
    for (const row of rows) {
      const definition = this.#toDefinition(row.payload);
      if (definition && definition.id === id2) return definition;
    }
    return null;
  }
  /**
   * Terminalizes an oracle execution and, atomically, publishes its linked
   * artifact (Amendment 5: upload-then-commit).
   */
  async terminalize(input) {
    const updatedAt = input.updatedAt ?? (/* @__PURE__ */ new Date()).toISOString();
    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query(
        `SELECT revision, artifact_id FROM oracle_executions
         WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3
           AND workflow_id = $4 AND execution_id = $5`,
        [this.#organizationId, this.#workstreamId, input.namespaceId, input.workflowId, input.executionId]
      );
      const existing = rows[0];
      const revision = (existing?.revision ?? 0) + 1;
      const artifactId = input.artifactId ?? existing?.artifact_id ?? null;
      await tx.query(
        `UPDATE oracle_executions
           SET status = $1, revision = $2, updated_at = $3
         WHERE organization_id = $4 AND workstream_id = $5 AND namespace_id = $6
           AND workflow_id = $7 AND execution_id = $8`,
        [
          input.status,
          revision,
          updatedAt,
          this.#organizationId,
          this.#workstreamId,
          input.namespaceId,
          input.workflowId,
          input.executionId
        ]
      );
      if (artifactId)
        await tx.query(
          `UPDATE artifacts
             SET availability_status = $1, updated_at = $2
           WHERE organization_id = $3 AND workstream_id = $4 AND namespace_id = $5
             AND workflow_id = $6 AND artifact_id = $7`,
          [
            "available",
            updatedAt,
            this.#organizationId,
            this.#workstreamId,
            input.namespaceId,
            input.workflowId,
            artifactId
          ]
        );
      return { executionId: input.executionId, status: input.status, revision, artifactId };
    });
  }
};
function createSqlOracleExecutionRepository(client, options = {}) {
  return new SqlOracleExecutionRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-work-environment-repository.ts
import { createHash as createHash9 } from "node:crypto";
var ERROR_CODES = Object.freeze({
  INVALID_ENVIRONMENT: "INVALID_ENVIRONMENT",
  INVALID_NAMESPACE: "INVALID_NAMESPACE",
  NOT_FOUND: "NOT_FOUND",
  REVISION_CONFLICT: "REVISION_CONFLICT",
  INVALID_TRANSITION: "INVALID_TRANSITION",
  CORRUPT_STORAGE: "CORRUPT_STORAGE"
});
var SAFE_ID7 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var ENV_TYPE = "work-unit-environment";
var IMMUTABLE_FIELDS = [
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
];
var SqlWorkEnvironmentRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "SqlWorkEnvironmentRepositoryError";
    this.code = code;
    this.details = details;
  }
};
function statusForState(state) {
  switch (state) {
    case "provisioning":
      return "provisioning";
    case "active":
      return "ready";
    case "completed":
    case "abandoned":
    case "error":
      return "busy";
    case "removed":
      return "decommissioned";
  }
}
function snapshotHash(environment) {
  return createHash9("sha256").update(JSON.stringify(environment, Object.keys(environment).sort())).digest("hex");
}
var SqlWorkEnvironmentRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  #locks;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
    this.#locks = /* @__PURE__ */ new Map();
  }
  #assertNamespace(namespaceId) {
    if (!validateNamespaceId(namespaceId).ok) throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.INVALID_NAMESPACE);
  }
  #assertEnvironmentId(environmentId) {
    if (typeof environmentId !== "string" || !SAFE_ID7.test(environmentId))
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.INVALID_ENVIRONMENT);
  }
  /**
   * Virtual artifact paths. The SQL adapter does no filesystem I/O; the paths
   * follow the same digest algebra as the filesystem store (raw environment id
   * never a path segment) so callers can reason about them uniformly.
   */
  paths(namespaceId, environmentId) {
    this.#assertNamespace(namespaceId);
    this.#assertEnvironmentId(environmentId);
    const digest4 = createHash9("sha256").update(`${namespaceId}:${environmentId}`).digest("hex");
    const directory = `sql://work-environments/${this.#organizationId}/${this.#workstreamId}/${namespaceId}/${digest4}`;
    return {
      directory,
      snapshot: `${directory}/environment.json`,
      events: `${directory}/events.jsonl`,
      pending: `${directory}/pending.json`
    };
  }
  #locked(namespaceId, environmentId, action) {
    const key = `${namespaceId}\0${environmentId}`;
    const prior = this.#locks.get(key) ?? Promise.resolve();
    const operation = prior.then(action);
    const tail = operation.catch(() => {
    });
    this.#locks.set(key, tail);
    return operation.finally(() => {
      if (this.#locks.get(key) === tail) this.#locks.delete(key);
    });
  }
  async #selectRow(client, environmentId) {
    const { rows } = await client.query(
      `SELECT * FROM work_environments
       WHERE organization_id = $1 AND workstream_id = $2 AND environment_id = $3`,
      [this.#organizationId, this.#workstreamId, environmentId]
    );
    return rows[0] ?? null;
  }
  #snapshotFromRow(row) {
    const environment = validateWorkUnitEnvironment(parseJsonColumn(row.payload));
    if (!environment.ok)
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.CORRUPT_STORAGE, { artifact: "snapshot" });
    if (!Number.isSafeInteger(row.revision) || row.revision < 1)
      throw new SqlWorkEnvironmentRepositoryError(ERROR_CODES.CORRUPT_STORAGE, { artifact: "revision" });
    return {
      revision: row.revision,
      environmentHash: snapshotHash(environment.environment),
      environment: environment.environment
    };
  }
  async #read(client, namespaceId, environmentId) {
    this.#assertNamespace(namespaceId);
    this.#assertEnvironmentId(environmentId);
    const row = await this.#selectRow(client, environmentId);
    if (!row) return null;
    const snapshot = this.#snapshotFromRow(row);
    return snapshot.environment.namespaceId === namespaceId ? snapshot : null;
  }
  async #write(client, current, environment) {
    const revision = (current?.revision ?? 0) + 1;
    const environmentHash = snapshotHash(environment);
    const snapshot = { revision, environmentHash, environment };
    const status = statusForState(environment.lifecycleState);
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    const payload = JSON.stringify(environment);
    if (current) {
      const { rowCount } = await client.query(
        `UPDATE work_environments
           SET revision = $1, status = $2, payload = $3::jsonb, updated_at = $4
         WHERE organization_id = $5 AND workstream_id = $6 AND environment_id = $7 AND revision = $8`,
        [
          revision,
          status,
          payload,
          observedAt,
          this.#organizationId,
          this.#workstreamId,
          environment.environmentId,
          current.revision
        ]
      );
      if (!rowCount) return { ok: false, error: { code: ERROR_CODES.REVISION_CONFLICT } };
    } else {
      await client.query(
        `INSERT INTO work_environments
           (organization_id, workstream_id, environment_id, env_type, status, revision, payload, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          environment.environmentId,
          ENV_TYPE,
          status,
          revision,
          payload,
          observedAt,
          observedAt
        ]
      );
    }
    return { ok: true, changed: true, snapshot };
  }
  async read(namespaceId, environmentId) {
    return this.#read(this.#client, namespaceId, environmentId);
  }
  async list(namespaceId, filter = {}) {
    this.#assertNamespace(namespaceId);
    const { rows } = await this.#client.query(
      `SELECT * FROM work_environments WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    );
    return rows.map((row) => this.#snapshotFromRow(row)).filter(
      (snapshot) => snapshot.environment.namespaceId === namespaceId && (!filter.states || filter.states.includes(snapshot.environment.lifecycleState))
    ).sort((left, right) => left.environment.environmentId.localeCompare(right.environment.environmentId));
  }
  async reserve(environment) {
    const validated = validateWorkUnitEnvironment(environment);
    if (!validated.ok) return validated;
    this.#assertNamespace(validated.environment.namespaceId);
    return this.#locked(
      validated.environment.namespaceId,
      validated.environment.environmentId,
      () => withTransaction(this.#client, async (tx) => {
        const row = await this.#selectRow(tx, validated.environment.environmentId);
        if (row) {
          const current = this.#snapshotFromRow(row);
          if (current.environment.namespaceId !== validated.environment.namespaceId)
            return { ok: false, error: { code: ERROR_CODES.INVALID_TRANSITION } };
          return JSON.stringify(current.environment) === JSON.stringify(validated.environment) ? { ok: true, changed: false, snapshot: current } : { ok: false, error: { code: ERROR_CODES.INVALID_TRANSITION } };
        }
        return this.#write(tx, null, validated.environment);
      })
    );
  }
  async transition(namespaceId, environmentId, next, options = {}) {
    this.#assertNamespace(namespaceId);
    void options.errorCode;
    return this.#locked(
      namespaceId,
      environmentId,
      () => withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, environmentId);
        if (!current) return { ok: false, error: { code: ERROR_CODES.NOT_FOUND } };
        if (options.expectedRevision !== void 0 && options.expectedRevision !== current.revision)
          return { ok: false, error: { code: ERROR_CODES.REVISION_CONFLICT } };
        if (JSON.stringify(current.environment) === JSON.stringify(next))
          return { ok: true, changed: false, snapshot: current };
        for (const field of IMMUTABLE_FIELDS)
          if (current.environment[field] !== next[field])
            return { ok: false, error: { code: ERROR_CODES.INVALID_TRANSITION } };
        if (current.environment.parentCaseId && next.parentCaseId !== current.environment.parentCaseId)
          return { ok: false, error: { code: ERROR_CODES.INVALID_TRANSITION } };
        const from = current.environment.lifecycleState;
        const to = next.lifecycleState;
        const allowed = from === "provisioning" && ["provisioning", "active", "error"].includes(to) || from === "active" && ["completed", "abandoned", "error"].includes(to) || ["completed", "abandoned", "error"].includes(from) && to === "removed";
        if (!allowed) return { ok: false, error: { code: ERROR_CODES.INVALID_TRANSITION } };
        const validated = validateWorkUnitEnvironment(next);
        if (!validated.ok) return validated;
        return this.#write(tx, current, validated.environment);
      })
    );
  }
};
function createSqlWorkEnvironmentRepository(client, options = {}) {
  return new SqlWorkEnvironmentRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-delivery-repository.ts
import { createHash as createHash13 } from "node:crypto";

// ../src/domain/delivery/delivery-operation-definition.ts
import { createHash as createHash10 } from "node:crypto";
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
var SAFE3 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SHA2 = /^[0-9a-f]{40}$/i;
var DIGEST = /^sha256:[0-9a-f]{64}$/i;
var MEDIA = /^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}\/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$/i;
var canonical = (value) => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((k) => [k, canonical(value[k])])
) : value;
var canonicalDeliveryHash = (value) => `sha256:${createHash10("sha256").update(JSON.stringify(canonical(value))).digest("hex")}`;
var fail2 = (path, reason = "invalid_value") => ({
  ok: false,
  error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_REQUEST, path, reason }
});
var exact = (v, fields) => v !== null && typeof v === "object" && !Array.isArray(v) && Object.keys(v).every((k) => fields.includes(k));
var id = (v) => typeof v === "string" && SAFE3.test(v);
var digest = (v) => typeof v === "string" && DIGEST.test(v);
var sha = (v) => typeof v === "string" && SHA2.test(v);
function artifact(v, path = "artifactRef") {
  if (!exact(v, ["digest", "mediaType", "producerRef", "buildRef", "sourceCommit"]) || !digest(v.digest) || !MEDIA.test(v.mediaType ?? "") || !id(v.producerRef) || !id(v.buildRef) || !sha(v.sourceCommit))
    return fail2(path);
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
  if (!exact(v, ["releaseId", "artifactDigest", "sourceCommit", "approvedEvidenceId"]) || !id(v.releaseId) || !digest(v.artifactDigest) || !sha(v.sourceCommit) || !id(v.approvedEvidenceId))
    return fail2(path);
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
  if (!exact(v, ["operationId", "kind", "state", "targetHash", "sourceCommit", "artifactDigest"]) || !id(v.operationId) || v.kind !== kind || v.state !== "succeeded" || !digest(v.targetHash) || !sha(v.sourceCommit) || !digest(v.artifactDigest))
    return fail2(path);
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
    return fail2("$", "unknown_or_missing_field");
  if (!Number.isSafeInteger(candidate.expectedRevision) || candidate.expectedRevision < 1 || !id(candidate.idempotencyKey) || !id(candidate.targetId))
    return fail2("$");
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
      return fail2("releaseRef", "artifact_identity_mismatch");
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
      return fail2("$", "rollback_identity_mismatch");
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
  for (const v of [namespaceId, workflowId, deliveryId, caseId, runtimeId]) if (!id(v)) return fail2("scope");
  if (!digest(targetHash)) return fail2("targetHash");
  const scopeHash = canonicalDeliveryHash({
    namespaceId,
    workflowId,
    deliveryId,
    caseId,
    runtimeId,
    idempotencyKey: request.idempotencyKey
  });
  const semanticHash2 = canonicalDeliveryHash({
    kind: request.kind,
    expectedRevision: request.expectedRevision,
    targetHash,
    ...Object.fromEntries(
      Object.entries(request).filter(([k]) => /Ref$/.test(k) || ["rollbackRequestId", "approvedEvidenceId"].includes(k))
    )
  });
  return { ok: true, value: { operationId: `dop_${scopeHash.slice(7, 39)}`, scopeHash, semanticHash: semanticHash2 } };
}
var ALLOWED = {
  pending: ["running", "failed"],
  running: ["succeeded", "failed", "indeterminate"],
  indeterminate: ["succeeded", "failed"],
  succeeded: [],
  failed: []
};
function validateDeliveryOperationTransition(previous, next, { inspectedObservation } = {}) {
  if (!previous || !next || previous.operationId !== next.operationId || !DELIVERY_OPERATION_STATES.includes(previous.state) || !ALLOWED[previous.state]?.includes(next.state))
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
  if (!exact(v, fields) || v.recordType !== "delivery-operation" || !id(v.operationId) || !DELIVERY_OPERATION_KINDS.includes(v.kind) || !DELIVERY_OPERATION_STATES.includes(v.state) || !Number.isSafeInteger(v.expectedRevision) || !Number.isSafeInteger(v.attempt) || v.attempt < 0 || !digest(v.scopeHash) || !digest(v.semanticHash))
    return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD } };
  for (const key of ["requestedAt", "startedAt", "completedAt"])
    if (v[key] !== void 0 && (!Number.isFinite(Date.parse(v[key])) || new Date(Date.parse(v[key])).toISOString() !== v[key]))
      return { ok: false, error: { code: DELIVERY_OPERATION_ERROR_CODES.INVALID_RECORD, path: key } };
  return { ok: true, value: Object.freeze({ ...v }) };
}

// ../src/domain/delivery/delivery-policy.ts
import { createHash as createHash12, randomUUID as randomUUID7 } from "node:crypto";

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
var fail3 = (path, reason = "invalid_value") => ({
  ok: false,
  error: { code: "INVALID_DELIVERY_DEFINITION", path, reason }
});
var canonical2 = (value) => Array.isArray(value) ? value.map(canonical2) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((key) => [key, canonical2(value[key])])
) : value;
function validateDeliveryDefinition(input) {
  if (!input || typeof input !== "object" || Array.isArray(input) || Object.keys(input).some((key) => !TOP.has(key)))
    return fail3("$");
  const candidate = input;
  if (candidate.schemaVersion !== DELIVERY_DEFINITION_SCHEMA_VERSION) return fail3("schemaVersion");
  if (!SAFE4.test(candidate.deliveryType ?? "") || !SEMVER2.test(candidate.version ?? "") || typeof candidate.title !== "string" || !candidate.title.trim() || candidate.title.length > 256)
    return fail3("$");
  if (!Array.isArray(candidate.checkpoints) || candidate.checkpoints.length !== DELIVERY_STAGES.length)
    return fail3("checkpoints");
  const checkpoints = [];
  for (let index = 0; index < candidate.checkpoints.length; index++) {
    const raw = candidate.checkpoints[index];
    if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((key) => !CHECKPOINT.has(key)) || raw.stage !== DELIVERY_STAGES[index])
      return fail3(`checkpoints[${index}]`);
    const responsibility = raw.responsibility;
    if (!responsibility || Object.keys(responsibility).some((key) => !RESPONSIBILITY.has(key)) || !["code", "human"].includes(responsibility.kind) || !SAFE4.test(responsibility.name ?? ""))
      return fail3(`checkpoints[${index}].responsibility`);
    if (raw.stage === "release-approved" && responsibility.kind !== "human")
      return fail3(`checkpoints[${index}].responsibility`, "release_requires_human");
    if (raw.stage !== "release-approved" && responsibility.kind !== "code")
      return fail3(`checkpoints[${index}].responsibility`, "factory_code_required");
    if (!Array.isArray(raw.requiredEvidence) || raw.requiredEvidence.length === 0 || raw.requiredEvidence.length > 16)
      return fail3(`checkpoints[${index}].requiredEvidence`);
    const requiredEvidence = [];
    for (let evidenceIndex = 0; evidenceIndex < raw.requiredEvidence.length; evidenceIndex++) {
      const item = raw.requiredEvidence[evidenceIndex];
      if (!item || typeof item !== "object" || Array.isArray(item) || Object.keys(item).some((key) => !EVIDENCE.has(key)) || !DELIVERY_EVIDENCE_KINDS.includes(item.kind) || !["pass", "fail", "indeterminate", "approved", "rejected"].includes(item.outcome))
        return fail3(`checkpoints[${index}].requiredEvidence[${evidenceIndex}]`);
      if (item.oracleId !== void 0 && !SAFE4.test(item.oracleId))
        return fail3(`checkpoints[${index}].requiredEvidence[${evidenceIndex}].oracleId`);
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
      return fail3(field);
  }
  return { ok: true, definition: canonical2({ ...candidate, checkpoints }) };
}
function hashDeliveryDefinition(definition) {
  return createHash11("sha256").update(JSON.stringify(canonical2(definition))).digest("hex");
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
      requestId: randomUUID7(),
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

// ../src/adapters/persistence/sql/sql-delivery-repository.ts
var UUID2 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE6 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SHA3 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var HASH2 = /^sha256:[0-9a-f]{64}$/;
var canonical3 = (value) => Array.isArray(value) ? value.map(canonical3) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).filter((key) => value[key] !== void 0).sort().map((key) => [key, canonical3(value[key])])
) : value;
var hash2 = (value) => createHash13("sha256").update(JSON.stringify(canonical3(value))).digest("hex");
var validSnapshot = (value) => value !== null && typeof value === "object" && value.schemaVersion === "1" && UUID2.test(value.namespaceId ?? "") && SAFE6.test(value.deliveryId ?? "") && SAFE6.test(value.workflowId ?? "") && UUID2.test(value.environmentId ?? "") && HASH2.test(value.environmentHash ?? "") && UUID2.test(value.parentCaseId ?? "") && SAFE6.test(value.runtimeId ?? "") && SHA3.test(value.baseCommit ?? "") && SHA3.test(value.headCommit ?? "") && Number.isSafeInteger(value.revision) && value.revision > 0;
var SqlDeliveryRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "SqlDeliveryRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var OPERATION_TERMINAL_STATES = ["succeeded", "failed"];
var SqlDeliveryRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  #locks;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
    this.#locks = /* @__PURE__ */ new Map();
  }
  #assertScope(namespaceId, deliveryId) {
    if (!UUID2.test(namespaceId ?? "") || !SAFE6.test(deliveryId ?? ""))
      throw new SqlDeliveryRepositoryError("INVALID_DELIVERY_SCOPE");
  }
  #locked(namespaceId, deliveryId, action) {
    const key = `${namespaceId}\0${deliveryId}`;
    const prior = this.#locks.get(key) ?? Promise.resolve();
    const operation = prior.then(action);
    const tail = operation.catch(() => {
    });
    this.#locks.set(key, tail);
    return operation.finally(() => {
      if (this.#locks.get(key) === tail) this.#locks.delete(key);
    });
  }
  async #read(client, namespaceId, deliveryId) {
    this.#assertScope(namespaceId, deliveryId);
    const { rows } = await client.query(
      `SELECT * FROM deliveries
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    );
    const row = rows[0];
    if (!row) return null;
    const snapshot = parseJsonColumn(row.payload);
    if (!validSnapshot(snapshot) || snapshot.snapshotHash !== hash2({ ...snapshot, snapshotHash: void 0 }))
      throw new SqlDeliveryRepositoryError("CORRUPT_DELIVERY_STORAGE");
    return snapshot;
  }
  async #journal(client, namespaceId, deliveryId) {
    this.#assertScope(namespaceId, deliveryId);
    const { rows } = await client.query(
      `SELECT * FROM delivery_journal
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    );
    return rows.sort((left, right) => (left.record_sequence ?? 0) - (right.record_sequence ?? 0)).map((row) => parseJsonColumn(row.payload));
  }
  async #append(client, namespaceId, deliveryId, records) {
    const { rows } = await client.query(
      `SELECT record_sequence FROM delivery_journal
       WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND delivery_id = $4`,
      [this.#organizationId, this.#workstreamId, namespaceId, deliveryId]
    );
    let sequence = rows.reduce((maximum, row) => Math.max(maximum, Number(row.record_sequence) || 0), 0) + 1;
    for (const record2 of records) {
      await client.query(
        `INSERT INTO delivery_journal
           (organization_id, workstream_id, namespace_id, delivery_id, record_sequence, record_id, record_type, payload, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId,
          sequence,
          `${deliveryId}:${sequence}`,
          record2.recordType ?? null,
          JSON.stringify(record2),
          (/* @__PURE__ */ new Date()).toISOString()
        ]
      );
      sequence++;
    }
  }
  #projection(records) {
    const history = records.filter((record2) => record2.recordType === "delivery-operation");
    const current = /* @__PURE__ */ new Map();
    const resolved = new Set(
      history.filter((record2) => record2.resolvedOperationId && OPERATION_TERMINAL_STATES.includes(record2.state)).map((record2) => record2.resolvedOperationId)
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
  async #write(client, current, value, operationInput) {
    const namespaceId = value.namespaceId;
    const deliveryId = value.deliveryId;
    const operationId = createHash13("sha256").update(`${namespaceId}:${deliveryId}:${operationInput.idempotencyKey}`).digest("hex");
    const operation = {
      schemaVersion: "1",
      operationId,
      deliveryId,
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
    const running = { ...operation, state: "running", timestamp: (/* @__PURE__ */ new Date()).toISOString() };
    const succeeded = {
      ...operation,
      state: "succeeded",
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      resultHash: snapshot.snapshotHash
    };
    await this.#append(client, namespaceId, deliveryId, [
      operation,
      running,
      succeeded
    ]);
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    const payload = JSON.stringify(snapshot);
    const updatedAt = typeof snapshot.updatedAt === "string" ? snapshot.updatedAt : observedAt;
    if (current) {
      const { rowCount } = await client.query(
        `UPDATE deliveries
           SET revision = $1, stage = $2, payload = $3::jsonb, updated_at = $4
         WHERE organization_id = $5 AND workstream_id = $6 AND namespace_id = $7 AND delivery_id = $8`,
        [
          snapshot.revision,
          snapshot.stage,
          payload,
          updatedAt,
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId
        ]
      );
      if (!rowCount) return { ok: false, error: { code: "REVISION_CONFLICT" } };
    } else {
      const createdAt = typeof snapshot.createdAt === "string" ? snapshot.createdAt : observedAt;
      await client.query(
        `INSERT INTO deliveries
           (organization_id, workstream_id, namespace_id, delivery_id, revision, stage, payload, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9)`,
        [
          this.#organizationId,
          this.#workstreamId,
          namespaceId,
          deliveryId,
          snapshot.revision,
          snapshot.stage,
          payload,
          createdAt,
          updatedAt
        ]
      );
    }
    return { ok: true, changed: true, idempotent: false, snapshot };
  }
  async read(namespaceId, deliveryId) {
    return this.#read(this.#client, namespaceId, deliveryId);
  }
  async create(input) {
    const namespaceId = input.namespaceId;
    const deliveryId = input.deliveryId;
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, deliveryId);
        if (current)
          return hash2({ ...current, snapshotHash: void 0 }) === hash2(input) ? { ok: true, changed: false, snapshot: current } : { ok: false, error: { code: "DELIVERY_IDENTITY_CONFLICT" } };
        if (!validSnapshot(input)) return { ok: false, error: { code: "INVALID_DELIVERY_SNAPSHOT" } };
        return this.#write(tx, null, input, {
          kind: "delivery_created",
          idempotencyKey: `create:${deliveryId}`
        });
      })
    );
  }
  async promote({
    namespaceId,
    request,
    definition,
    evidence,
    execution: execution2
  }) {
    return this.#locked(
      namespaceId,
      request.deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, request.deliveryId);
        const records = await this.#journal(tx, namespaceId, request.deliveryId);
        const scopeHash = deliveryScopeHash(namespaceId, request, execution2);
        const semanticHash2 = deliverySemanticHash(request);
        const prior = records.find((item) => item.scopeHash === scopeHash && item.state === "succeeded");
        if (prior) {
          if (prior.semanticHash !== semanticHash2)
            return { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
          return { ok: true, changed: false, idempotent: true, snapshot: current };
        }
        const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution: execution2 });
        if (!decision.allowed) return { ok: false, error: decision };
        const next = applyDeliveryPromotion(current, request);
        return this.#write(tx, current, next, {
          kind: "delivery_promoted",
          idempotencyKey: request.idempotencyKey,
          scopeHash,
          semanticHash: semanticHash2,
          evidenceIds: request.evidenceIds
        });
      })
    );
  }
  async readWithOperations(namespaceId, deliveryId) {
    const snapshot = await this.#read(this.#client, namespaceId, deliveryId);
    if (!snapshot) return null;
    const projection = this.#projection(await this.#journal(this.#client, namespaceId, deliveryId));
    return { ...snapshot, deliveryOperations: projection.operations, rollbackRequests: projection.rollbackRequests };
  }
  async inspectDeliveryOperations(namespaceId, deliveryId) {
    return this.#projection(await this.#journal(this.#client, namespaceId, deliveryId));
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
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const snapshot = await this.#read(tx, namespaceId, deliveryId);
        if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
        if (snapshot.workflowId !== workflowId || snapshot.parentCaseId !== caseId || snapshot.runtimeId !== runtimeId)
          return { ok: false, error: { code: "DELIVERY_SCOPE_MISMATCH" } };
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId));
        const prior = projection.rollbackRequestHistory.find((record3) => record3.scopeHash === request.scopeHash);
        if (prior)
          return prior.semanticHash === request.semanticHash ? {
            ok: true,
            changed: false,
            idempotent: true,
            request: projection.rollbackRequests.find((item) => item.rollbackRequestId === prior.rollbackRequestId) ?? prior
          } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        if (snapshot.revision !== request.expectedRevision)
          return { ok: false, error: { code: "REVISION_CONFLICT" } };
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
        await this.#append(tx, namespaceId, deliveryId, [record2]);
        return { ok: true, changed: true, idempotent: false, request: record2 };
      })
    );
  }
  async approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval) {
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const snapshot = await this.#read(tx, namespaceId, deliveryId);
        if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId));
        const current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId);
        if (!current) return { ok: false, error: { code: "ROLLBACK_REQUEST_NOT_FOUND" } };
        const scopeHash = `sha256:${hash2({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`;
        const semanticHash2 = `sha256:${hash2({
          rollbackRequestId,
          expectedRevision: approval.expectedRevision,
          actorId: approval.execution.actorId
        })}`;
        const prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash);
        if (prior)
          return prior.approvalSemanticHash === semanticHash2 ? { ok: true, changed: false, idempotent: true, request: prior } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision)
          return { ok: false, error: { code: "REVISION_CONFLICT" } };
        if (current.status !== "requested")
          return { ok: false, error: { code: "ROLLBACK_REQUEST_ALREADY_DECIDED" } };
        const record2 = {
          ...current,
          status: "approved",
          approvedAt: (/* @__PURE__ */ new Date()).toISOString(),
          approvedBy: canonical3(approval.execution),
          approvalScopeHash: scopeHash,
          approvalSemanticHash: semanticHash2,
          approvalIdempotencyKey: approval.idempotencyKey
        };
        await this.#append(tx, namespaceId, deliveryId, [record2]);
        return { ok: true, changed: true, idempotent: false, request: record2 };
      })
    );
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
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const normalized = normalizeDeliveryOperationRequest(request);
        if (!normalized.ok) return normalized;
        const snapshot = await this.#read(tx, namespaceId, deliveryId);
        if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
        const targetHash = targetRef?.targetHash;
        const identity = deriveDeliveryOperationIdentity(
          { namespaceId, workflowId, deliveryId, caseId, runtimeId },
          normalized.value,
          targetHash
        );
        if (!identity.ok) return identity;
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId));
        const existing = projection.history.find((record2) => record2.scopeHash === identity.value.scopeHash);
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
        const now = (/* @__PURE__ */ new Date()).toISOString();
        const source = normalized.value.artifactRef ?? normalized.value.priorArtifactRef;
        const operation = {
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
        const persisted = Object.fromEntries(
          Object.entries(operation).filter(([, value]) => value !== void 0)
        );
        const contract = validateDeliveryOperationRecord(persisted);
        if (!contract.ok) return { ok: false, error: contract.error };
        await this.#append(tx, namespaceId, deliveryId, [persisted]);
        return { ok: true, changed: true, idempotent: false, operation: persisted };
      })
    );
  }
  async recordDeliveryOperation(namespaceId, deliveryId, operationId, transition, options = {}) {
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        await this.#read(tx, namespaceId, deliveryId);
        const projection = this.#projection(await this.#journal(tx, namespaceId, deliveryId));
        const previous = projection.operations.find((record2) => record2.operationId === operationId);
        if (!previous) return { ok: false, error: { code: "DELIVERY_OPERATION_NOT_FOUND" } };
        const now = (/* @__PURE__ */ new Date()).toISOString();
        const state = transition.state;
        const next = {
          ...previous,
          state,
          attempt: state === "running" ? previous.attempt + 1 : previous.attempt,
          startedAt: state === "running" ? now : previous.startedAt,
          completedAt: OPERATION_TERMINAL_STATES.includes(state) ? now : void 0,
          adapterCorrelation: transition.adapterCorrelation ?? previous.adapterCorrelation,
          result: transition.result,
          error: transition.error,
          resolvedOperationId: transition.resolvedOperationId
        };
        const clean = Object.fromEntries(
          Object.entries(next).filter(([, value]) => value !== void 0)
        );
        const valid = validateDeliveryOperationTransition(previous, clean, options);
        if (!valid.ok) return valid;
        const contract = validateDeliveryOperationRecord(clean);
        if (!contract.ok) return contract;
        await this.#append(tx, namespaceId, deliveryId, [clean]);
        return { ok: true, changed: true, operation: clean };
      })
    );
  }
  async startDeliveryOperation(namespaceId, deliveryId, operationId, adapterCorrelation) {
    return this.recordDeliveryOperation(namespaceId, deliveryId, operationId, {
      state: "running",
      adapterCorrelation
    });
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
   * Atomically patches specific fields in the delivery snapshot and appends a
   * journal record. Supports dot-notation keys like `git.checkpoint` to set
   * nested properties.
   */
  async updateSnapshot(namespaceId, deliveryId, patch, operationInput) {
    return this.#locked(
      namespaceId,
      deliveryId,
      () => withTransaction(this.#client, async (tx) => {
        const current = await this.#read(tx, namespaceId, deliveryId);
        if (!current) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
        const updated = { ...current };
        for (const [key, value] of Object.entries(patch)) {
          const parts = key.split(".");
          if (parts.length === 1) {
            updated[key] = value;
          } else if (parts.length === 2) {
            const head = parts[0];
            const tail = parts[1];
            updated[head] = { ...updated[head] ?? {}, [tail]: value };
          } else {
            updated[key] = value;
          }
        }
        updated.updatedAt = patch.updatedAt ?? (/* @__PURE__ */ new Date()).toISOString();
        return this.#write(tx, current, updated, operationInput);
      })
    );
  }
};
function createSqlDeliveryRepository(client, options = {}) {
  return new SqlDeliveryRepository(client, options);
}

// ../src/domain/work-unit.ts
var WORK_UNIT_STATES = Object.freeze([
  "created",
  "assigned",
  "running",
  "completed",
  "failed",
  "cancelled"
]);
var WORK_UNIT_TERMINAL_STATES = Object.freeze(["completed", "failed", "cancelled"]);
var WORK_UNIT_ERROR_CODES = Object.freeze({
  INVALID_WORK_UNIT: "INVALID_WORK_UNIT",
  INVALID_STATE: "INVALID_STATE",
  INVALID_TRANSITION: "INVALID_TRANSITION",
  REVISION_CONFLICT: "REVISION_CONFLICT",
  NOT_FOUND: "NOT_FOUND"
});
var WORK_UNIT_TRANSITIONS = {
  created: ["assigned", "cancelled"],
  assigned: ["running", "created", "cancelled", "failed"],
  running: ["completed", "failed", "cancelled"],
  completed: [],
  failed: [],
  cancelled: []
};
var SAFE_ID8 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var STATE_SET = new Set(WORK_UNIT_STATES);
var TERMINAL_SET = new Set(WORK_UNIT_TERMINAL_STATES);
function fail4(code, path) {
  return { ok: false, error: { code, path } };
}
function isPlainRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isWorkUnitIsoInstant(value) {
  if (typeof value !== "string") return false;
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString() === value;
}
function isWorkUnitState(value) {
  return typeof value === "string" && STATE_SET.has(value);
}
function canTransitionWorkUnit(from, to) {
  return WORK_UNIT_TRANSITIONS[from].includes(to);
}
function validateWorkUnit(input) {
  if (!isPlainRecord(input)) return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "$");
  if (typeof input.workUnitId !== "string" || !SAFE_ID8.test(input.workUnitId))
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "workUnitId");
  if (typeof input.unitType !== "string" || !SAFE_ID8.test(input.unitType))
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "unitType");
  if (!isWorkUnitState(input.status)) return fail4(WORK_UNIT_ERROR_CODES.INVALID_STATE, "status");
  if (typeof input.revision !== "number" || !Number.isSafeInteger(input.revision) || input.revision < 1)
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "revision");
  if (typeof input.priority !== "number" || !Number.isSafeInteger(input.priority))
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "priority");
  if (input.notBefore !== null && !isWorkUnitIsoInstant(input.notBefore))
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "notBefore");
  if (typeof input.attemptCount !== "number" || !Number.isSafeInteger(input.attemptCount) || input.attemptCount < 0)
    return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "attemptCount");
  if (!isPlainRecord(input.payload)) return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "payload");
  if (!isWorkUnitIsoInstant(input.createdAt)) return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "createdAt");
  if (!isWorkUnitIsoInstant(input.updatedAt)) return fail4(WORK_UNIT_ERROR_CODES.INVALID_WORK_UNIT, "updatedAt");
  const workUnit = {
    workUnitId: input.workUnitId,
    unitType: input.unitType,
    status: input.status,
    revision: input.revision,
    priority: input.priority,
    notBefore: input.notBefore,
    attemptCount: input.attemptCount,
    payload: input.payload,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt
  };
  return { ok: true, workUnit };
}

// ../src/adapters/persistence/sql/sql-work-unit-repository.ts
var SqlWorkUnitRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "SqlWorkUnitRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var WORK_UNIT_COLUMNS = [
  "work_unit_id",
  "unit_type",
  "status",
  "revision",
  "priority",
  "not_before",
  "attempt_count",
  "payload",
  "created_at",
  "updated_at"
].join(", ");
function toIsoInstant(value) {
  return value instanceof Date ? value.toISOString() : String(value);
}
function toIsoInstantOrNull(value) {
  if (value === null || value === void 0) return null;
  return toIsoInstant(value);
}
function compareNotBefore(left, right) {
  if (left === null && right === null) return 0;
  if (left === null) return 1;
  if (right === null) return -1;
  return left.localeCompare(right);
}
var SqlWorkUnitRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  get scope() {
    return { organizationId: this.#organizationId, workstreamId: this.#workstreamId };
  }
  #toWorkUnit(row) {
    const validated = validateWorkUnit({
      workUnitId: row.work_unit_id,
      unitType: row.unit_type,
      status: row.status,
      revision: Number(row.revision),
      priority: Number(row.priority),
      notBefore: toIsoInstantOrNull(row.not_before),
      attemptCount: Number(row.attempt_count),
      payload: parseJsonColumn(row.payload),
      createdAt: toIsoInstant(row.created_at),
      updatedAt: toIsoInstant(row.updated_at)
    });
    if (!validated.ok) throw new SqlWorkUnitRepositoryError("CORRUPT_STORAGE", { path: validated.error.path });
    return validated.workUnit;
  }
  async #select(workUnitId) {
    const { rows } = await this.#client.query(
      `SELECT ${WORK_UNIT_COLUMNS} FROM work_units
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`,
      [this.#organizationId, this.#workstreamId, workUnitId]
    );
    const row = rows[0];
    return row ? this.#toWorkUnit(row) : null;
  }
  /** Compare-and-swap write: the row is only updated when `revision` still matches. */
  async #write(workUnit, expectedRevision) {
    const { rowCount } = await this.#client.query(
      `UPDATE work_units
         SET unit_type = $1, status = $2, revision = $3, priority = $4, not_before = $5,
             attempt_count = $6, payload = $7::jsonb, updated_at = $8
       WHERE organization_id = $9 AND workstream_id = $10 AND work_unit_id = $11 AND revision = $12`,
      [
        workUnit.unitType,
        workUnit.status,
        workUnit.revision,
        workUnit.priority,
        workUnit.notBefore,
        workUnit.attemptCount,
        JSON.stringify(workUnit.payload),
        workUnit.updatedAt,
        this.#organizationId,
        this.#workstreamId,
        workUnit.workUnitId,
        expectedRevision
      ]
    );
    if (!rowCount)
      throw new SqlWorkUnitRepositoryError("REVISION_CONFLICT", {
        workUnitId: workUnit.workUnitId,
        expectedRevision
      });
  }
  async get(workUnitId) {
    return this.#select(workUnitId);
  }
  async create(input) {
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    const validated = validateWorkUnit({
      workUnitId: input.workUnitId,
      unitType: input.unitType,
      status: input.status ?? "created",
      revision: input.revision ?? 1,
      priority: input.priority ?? 0,
      notBefore: input.notBefore ?? null,
      attemptCount: input.attemptCount ?? 0,
      payload: input.payload ?? {},
      createdAt: observedAt,
      updatedAt: observedAt
    });
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path });
    const existing = await this.#select(input.workUnitId);
    if (existing) throw new SqlWorkUnitRepositoryError("WORK_UNIT_ALREADY_EXISTS", { workUnitId: input.workUnitId });
    const workUnit = validated.workUnit;
    await this.#client.query(
      `INSERT INTO work_units
         (organization_id, workstream_id, work_unit_id, unit_type, status, revision, priority, not_before,
          attempt_count, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb, $11, $12)`,
      [
        this.#organizationId,
        this.#workstreamId,
        workUnit.workUnitId,
        workUnit.unitType,
        workUnit.status,
        workUnit.revision,
        workUnit.priority,
        workUnit.notBefore,
        workUnit.attemptCount,
        JSON.stringify(workUnit.payload),
        workUnit.createdAt,
        workUnit.updatedAt
      ]
    );
    return workUnit;
  }
  async update(workUnitId, patch, expectedRevision) {
    const current = await this.#select(workUnitId);
    if (!current) throw new SqlWorkUnitRepositoryError("NOT_FOUND", { workUnitId });
    if (current.revision !== expectedRevision)
      throw new SqlWorkUnitRepositoryError("REVISION_CONFLICT", {
        workUnitId,
        expectedRevision,
        actualRevision: current.revision
      });
    if (patch.workUnitId !== void 0 && patch.workUnitId !== current.workUnitId)
      throw new SqlWorkUnitRepositoryError("INVALID_WORK_UNIT", { path: "workUnitId" });
    const status = patch.status ?? current.status;
    if (!isWorkUnitState(status)) throw new SqlWorkUnitRepositoryError("INVALID_STATE", { path: "status" });
    if (status !== current.status && !canTransitionWorkUnit(current.status, status))
      throw new SqlWorkUnitRepositoryError("INVALID_TRANSITION", { from: current.status, to: status });
    const validated = validateWorkUnit({
      workUnitId: current.workUnitId,
      unitType: patch.unitType ?? current.unitType,
      status,
      revision: current.revision + 1,
      priority: patch.priority ?? current.priority,
      notBefore: patch.notBefore === void 0 ? current.notBefore : patch.notBefore,
      attemptCount: patch.attemptCount ?? current.attemptCount,
      payload: patch.payload ?? current.payload,
      createdAt: current.createdAt,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString()
    });
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path });
    await this.#write(validated.workUnit, current.revision);
    return validated.workUnit;
  }
  async transition(workUnitId, nextState, expectedRevision, payloadUpdate) {
    if (!isWorkUnitState(nextState)) throw new SqlWorkUnitRepositoryError("INVALID_STATE", { path: "status" });
    const current = await this.#select(workUnitId);
    if (!current) throw new SqlWorkUnitRepositoryError("NOT_FOUND", { workUnitId });
    if (current.revision !== expectedRevision)
      throw new SqlWorkUnitRepositoryError("REVISION_CONFLICT", {
        workUnitId,
        expectedRevision,
        actualRevision: current.revision
      });
    if (!canTransitionWorkUnit(current.status, nextState))
      throw new SqlWorkUnitRepositoryError("INVALID_TRANSITION", { from: current.status, to: nextState });
    const validated = validateWorkUnit({
      ...current,
      status: nextState,
      revision: current.revision + 1,
      payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString()
    });
    if (!validated.ok) throw new SqlWorkUnitRepositoryError(validated.error.code, { path: validated.error.path });
    await this.#write(validated.workUnit, current.revision);
    return validated.workUnit;
  }
  async list(filter = {}) {
    const { rows } = await this.#client.query(
      `SELECT ${WORK_UNIT_COLUMNS} FROM work_units WHERE organization_id = $1 AND workstream_id = $2`,
      [this.#organizationId, this.#workstreamId]
    );
    const statuses = filter.status === void 0 ? null : Array.isArray(filter.status) ? filter.status : [filter.status];
    const units = rows.map((row) => this.#toWorkUnit(row)).filter((unit) => statuses === null || statuses.includes(unit.status)).filter((unit) => filter.priorityMin === void 0 || unit.priority >= filter.priorityMin).sort(
      (left, right) => right.priority - left.priority || compareNotBefore(left.notBefore, right.notBefore) || left.workUnitId.localeCompare(right.workUnitId)
    );
    return filter.limit === void 0 ? units : units.slice(0, Math.max(0, filter.limit));
  }
};
function createSqlWorkUnitRepository(client, options = {}) {
  return new SqlWorkUnitRepository(client, options);
}

// ../src/domain/worker.ts
var WORKER_STATES = Object.freeze(["offline", "idle", "busy", "maintenance"]);
var WORKER_ERROR_CODES = Object.freeze({
  INVALID_WORKER: "INVALID_WORKER",
  INVALID_STATE: "INVALID_STATE",
  INVALID_TRANSITION: "INVALID_TRANSITION",
  REVISION_CONFLICT: "REVISION_CONFLICT",
  NOT_FOUND: "NOT_FOUND"
});
var WORKER_TRANSITIONS = {
  offline: ["idle", "maintenance"],
  idle: ["busy", "offline", "maintenance"],
  busy: ["idle", "offline", "maintenance"],
  maintenance: ["offline", "idle"]
};
var SAFE_ID9 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var STATE_SET2 = new Set(WORKER_STATES);
function fail5(code, path) {
  return { ok: false, error: { code, path } };
}
function isPlainRecord2(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
function isWorkerIsoInstant(value) {
  if (typeof value !== "string") return false;
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) && new Date(milliseconds).toISOString() === value;
}
function isWorkerState(value) {
  return typeof value === "string" && STATE_SET2.has(value);
}
function canTransitionWorker(from, to) {
  return WORKER_TRANSITIONS[from].includes(to);
}
function validateWorker(input) {
  if (!isPlainRecord2(input)) return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "$");
  if (typeof input.workerId !== "string" || !SAFE_ID9.test(input.workerId))
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "workerId");
  if (typeof input.workerType !== "string" || !SAFE_ID9.test(input.workerType))
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "workerType");
  if (!isWorkerState(input.status)) return fail5(WORKER_ERROR_CODES.INVALID_STATE, "status");
  if (typeof input.revision !== "number" || !Number.isSafeInteger(input.revision) || input.revision < 1)
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "revision");
  if (input.lastHeartbeatAt !== null && !isWorkerIsoInstant(input.lastHeartbeatAt))
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "lastHeartbeatAt");
  if (input.protocolVersion !== null && typeof input.protocolVersion !== "string")
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "protocolVersion");
  if (!Array.isArray(input.capabilities) || !input.capabilities.every((entry) => typeof entry === "string"))
    return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "capabilities");
  if (!isPlainRecord2(input.payload)) return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "payload");
  if (!isWorkerIsoInstant(input.createdAt)) return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "createdAt");
  if (!isWorkerIsoInstant(input.updatedAt)) return fail5(WORKER_ERROR_CODES.INVALID_WORKER, "updatedAt");
  const worker = {
    workerId: input.workerId,
    workerType: input.workerType,
    status: input.status,
    revision: input.revision,
    lastHeartbeatAt: input.lastHeartbeatAt,
    protocolVersion: input.protocolVersion,
    capabilities: [...input.capabilities],
    payload: input.payload,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt
  };
  return { ok: true, worker };
}

// ../src/adapters/persistence/sql/sql-worker-repository.ts
var SqlWorkerRepositoryError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "SqlWorkerRepositoryError";
    this.code = code;
    this.details = details;
  }
};
var WORKER_COLUMNS = [
  "worker_id",
  "worker_type",
  "status",
  "revision",
  "last_heartbeat_at",
  "protocol_version",
  "capabilities",
  "payload",
  "created_at",
  "updated_at"
].join(", ");
function toIsoInstant2(value) {
  return value instanceof Date ? value.toISOString() : String(value);
}
function toIsoInstantOrNull2(value) {
  if (value === null || value === void 0) return null;
  return toIsoInstant2(value);
}
var SqlWorkerRepository = class {
  #client;
  #organizationId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
  }
  get scope() {
    return { organizationId: this.#organizationId };
  }
  #toWorker(row) {
    const validated = validateWorker({
      workerId: row.worker_id,
      workerType: row.worker_type,
      status: row.status,
      revision: Number(row.revision),
      lastHeartbeatAt: toIsoInstantOrNull2(row.last_heartbeat_at),
      protocolVersion: row.protocol_version === void 0 ? null : row.protocol_version,
      capabilities: parseJsonColumn(row.capabilities),
      payload: parseJsonColumn(row.payload),
      createdAt: toIsoInstant2(row.created_at),
      updatedAt: toIsoInstant2(row.updated_at)
    });
    if (!validated.ok) throw new SqlWorkerRepositoryError("CORRUPT_STORAGE", { path: validated.error.path });
    return validated.worker;
  }
  async #select(workerId) {
    const { rows } = await this.#client.query(
      `SELECT ${WORKER_COLUMNS} FROM workers WHERE organization_id = $1 AND worker_id = $2`,
      [this.#organizationId, workerId]
    );
    const row = rows[0];
    return row ? this.#toWorker(row) : null;
  }
  /** Compare-and-swap write: the row is only updated when `revision` still matches. */
  async #write(worker, expectedRevision) {
    const { rowCount } = await this.#client.query(
      `UPDATE workers
         SET worker_type = $1, status = $2, revision = $3, last_heartbeat_at = $4,
             protocol_version = $5, capabilities = $6::jsonb, payload = $7::jsonb, updated_at = $8
       WHERE organization_id = $9 AND worker_id = $10 AND revision = $11`,
      [
        worker.workerType,
        worker.status,
        worker.revision,
        worker.lastHeartbeatAt,
        worker.protocolVersion,
        JSON.stringify(worker.capabilities),
        JSON.stringify(worker.payload),
        worker.updatedAt,
        this.#organizationId,
        worker.workerId,
        expectedRevision
      ]
    );
    if (!rowCount)
      throw new SqlWorkerRepositoryError("REVISION_CONFLICT", { workerId: worker.workerId, expectedRevision });
  }
  async get(workerId) {
    return this.#select(workerId);
  }
  async create(input) {
    const observedAt = (/* @__PURE__ */ new Date()).toISOString();
    const validated = validateWorker({
      workerId: input.workerId,
      workerType: input.workerType,
      status: input.status ?? "offline",
      revision: input.revision ?? 1,
      lastHeartbeatAt: input.lastHeartbeatAt ?? null,
      protocolVersion: input.protocolVersion ?? null,
      capabilities: input.capabilities ?? [],
      payload: input.payload ?? {},
      createdAt: observedAt,
      updatedAt: observedAt
    });
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path });
    const existing = await this.#select(input.workerId);
    if (existing) throw new SqlWorkerRepositoryError("WORKER_ALREADY_EXISTS", { workerId: input.workerId });
    const worker = validated.worker;
    await this.#client.query(
      `INSERT INTO workers
         (organization_id, worker_id, worker_type, status, revision, last_heartbeat_at, protocol_version,
          capabilities, payload, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9::jsonb, $10, $11)`,
      [
        this.#organizationId,
        worker.workerId,
        worker.workerType,
        worker.status,
        worker.revision,
        worker.lastHeartbeatAt,
        worker.protocolVersion,
        JSON.stringify(worker.capabilities),
        JSON.stringify(worker.payload),
        worker.createdAt,
        worker.updatedAt
      ]
    );
    return worker;
  }
  async update(workerId, patch, expectedRevision) {
    const current = await this.#select(workerId);
    if (!current) throw new SqlWorkerRepositoryError("NOT_FOUND", { workerId });
    if (current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError("REVISION_CONFLICT", {
        workerId,
        expectedRevision,
        actualRevision: current.revision
      });
    if (patch.workerId !== void 0 && patch.workerId !== current.workerId)
      throw new SqlWorkerRepositoryError("INVALID_WORKER", { path: "workerId" });
    const status = patch.status ?? current.status;
    if (!isWorkerState(status)) throw new SqlWorkerRepositoryError("INVALID_STATE", { path: "status" });
    if (status !== current.status && !canTransitionWorker(current.status, status))
      throw new SqlWorkerRepositoryError("INVALID_TRANSITION", { from: current.status, to: status });
    const validated = validateWorker({
      workerId: current.workerId,
      workerType: patch.workerType ?? current.workerType,
      status,
      revision: current.revision + 1,
      lastHeartbeatAt: patch.lastHeartbeatAt === void 0 ? current.lastHeartbeatAt : patch.lastHeartbeatAt,
      protocolVersion: patch.protocolVersion === void 0 ? current.protocolVersion : patch.protocolVersion,
      capabilities: patch.capabilities ?? current.capabilities,
      payload: patch.payload ?? current.payload,
      createdAt: current.createdAt,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString()
    });
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path });
    await this.#write(validated.worker, current.revision);
    return validated.worker;
  }
  async transition(workerId, nextState, expectedRevision, payloadUpdate) {
    if (!isWorkerState(nextState)) throw new SqlWorkerRepositoryError("INVALID_STATE", { path: "status" });
    const current = await this.#select(workerId);
    if (!current) throw new SqlWorkerRepositoryError("NOT_FOUND", { workerId });
    if (current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError("REVISION_CONFLICT", {
        workerId,
        expectedRevision,
        actualRevision: current.revision
      });
    if (!canTransitionWorker(current.status, nextState))
      throw new SqlWorkerRepositoryError("INVALID_TRANSITION", { from: current.status, to: nextState });
    const validated = validateWorker({
      ...current,
      status: nextState,
      revision: current.revision + 1,
      payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString()
    });
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path });
    await this.#write(validated.worker, current.revision);
    return validated.worker;
  }
  async heartbeat(workerId, heartbeatAt, expectedRevision) {
    if (!isWorkerIsoInstant(heartbeatAt))
      throw new SqlWorkerRepositoryError("INVALID_WORKER", { path: "lastHeartbeatAt" });
    const current = await this.#select(workerId);
    if (!current) throw new SqlWorkerRepositoryError("NOT_FOUND", { workerId });
    if (expectedRevision !== void 0 && current.revision !== expectedRevision)
      throw new SqlWorkerRepositoryError("REVISION_CONFLICT", {
        workerId,
        expectedRevision,
        actualRevision: current.revision
      });
    const validated = validateWorker({
      ...current,
      revision: current.revision + 1,
      lastHeartbeatAt: heartbeatAt,
      updatedAt: (/* @__PURE__ */ new Date()).toISOString()
    });
    if (!validated.ok) throw new SqlWorkerRepositoryError(validated.error.code, { path: validated.error.path });
    await this.#write(validated.worker, current.revision);
    return validated.worker;
  }
  async list(filter = {}) {
    const { rows } = await this.#client.query(
      `SELECT ${WORKER_COLUMNS} FROM workers WHERE organization_id = $1`,
      [this.#organizationId]
    );
    const statuses = filter.status === void 0 ? null : Array.isArray(filter.status) ? filter.status : [filter.status];
    return rows.map((row) => this.#toWorker(row)).filter((worker) => statuses === null || statuses.includes(worker.status)).filter((worker) => filter.workerType === void 0 || worker.workerType === filter.workerType).sort((left, right) => left.workerId.localeCompare(right.workerId));
  }
};
function createSqlWorkerRepository(client, options = {}) {
  return new SqlWorkerRepository(client, options);
}

// ../src/adapters/persistence/sql/sql-lease-repository.ts
import { randomUUID as randomUUID8 } from "node:crypto";

// ../src/domain/lease/lease.ts
var WORK_UNIT_LEASE_STATUSES = Object.freeze(["active", "released", "expired"]);
var LEASE_ELIGIBLE_WORK_UNIT_STATUSES = Object.freeze(["created", "failed"]);
var LEASE_RELEASE_RESULT_STATUSES = Object.freeze(["completed", "failed", "created"]);
var LEASE_EXPIRY_REASONS = Object.freeze({
  HEARTBEAT_TIMEOUT: "heartbeat_timeout",
  WORKER_LOST: "worker_lost",
  RECLAIMED: "reclaimed"
});
var LEASE_ERROR_CODES = Object.freeze({
  /** A stale / mismatched fencing token was presented. */
  LEASE_FENCED: "LEASE_FENCED",
  /** No lease row matches the addressed identity. */
  LEASE_NOT_FOUND: "LEASE_NOT_FOUND",
  /** The lease is past its deadline and can no longer be renewed. */
  LEASE_EXPIRED: "LEASE_EXPIRED",
  /** The eligible-work-unit scan found nothing to lease. */
  NO_ELIGIBLE_WORK_UNIT: "NO_ELIGIBLE_WORK_UNIT",
  /** The work unit addressed by the operation does not exist. */
  WORK_UNIT_NOT_FOUND: "WORK_UNIT_NOT_FOUND",
  /** The operation is not legal for the lease's current state. */
  INVALID_LEASE_STATE: "INVALID_LEASE_STATE"
});
var LeaseError = class extends Error {
  code;
  details;
  constructor(code, details = {}, cause) {
    super(code, cause === void 0 ? void 0 : { cause });
    this.name = "LeaseError";
    this.code = code;
    this.details = details;
  }
};
function isLeaseStatus(value) {
  return typeof value === "string" && WORK_UNIT_LEASE_STATUSES.includes(value);
}
function computeLeaseExpiresAt(nowIso, ttlMs) {
  if (!Number.isFinite(ttlMs) || ttlMs <= 0) throw new RangeError("INVALID_LEASE_TTL");
  return new Date(Date.parse(nowIso) + ttlMs).toISOString();
}
function isLeaseExpiredByTime(lease, nowIso) {
  if (lease.status !== "active" || lease.leaseExpiresAt === null) return false;
  return Date.parse(lease.leaseExpiresAt) <= Date.parse(nowIso);
}
function isFencingTokenCurrent(current, incoming) {
  return Number.isFinite(incoming) && incoming === current;
}
function assertFencingToken(current, incoming) {
  if (!isFencingTokenCurrent(current, incoming)) {
    throw new LeaseError(LEASE_ERROR_CODES.LEASE_FENCED, {
      currentFencingToken: current,
      incomingFencingToken: incoming
    });
  }
}
function assertLeaseRenewable(lease, nowIso) {
  if (lease.status !== "active") {
    throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId, status: lease.status });
  }
  if (isLeaseExpiredByTime(lease, nowIso)) {
    throw new LeaseError(LEASE_ERROR_CODES.LEASE_EXPIRED, {
      leaseId: lease.leaseId,
      leaseExpiresAt: lease.leaseExpiresAt
    });
  }
}

// ../src/adapters/persistence/sql/sql-lease-repository.ts
function toIso(value) {
  if (value === null || value === void 0) return null;
  if (value instanceof Date) return value.toISOString();
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}
function toIsoRequired(value) {
  return toIso(value) ?? (/* @__PURE__ */ new Date(0)).toISOString();
}
function mapLease(row) {
  const status = isLeaseStatus(row.status) ? row.status : "expired";
  return {
    organizationId: row.organization_id,
    workstreamId: row.workstream_id,
    workUnitId: row.work_unit_id,
    leaseId: row.lease_id,
    workerId: row.worker_id,
    environmentId: row.environment_id ?? null,
    status,
    fencingToken: Number(row.fencing_token ?? 0),
    acquiredAt: toIso(row.acquired_at),
    leaseExpiresAt: toIso(row.lease_expires_at),
    heartbeatAt: toIso(row.heartbeat_at),
    releasedAt: toIso(row.released_at),
    expiryReason: row.expiry_reason ?? null,
    createdAt: toIsoRequired(row.created_at)
  };
}
var SqlLeaseRepository = class {
  #client;
  #organizationId;
  #workstreamId;
  constructor(client, options = {}) {
    this.#client = client;
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  }
  async #selectLease(client, organizationId, workstreamId, workUnitId, leaseId, forUpdate = false) {
    const { rows } = await client.query(
      `SELECT * FROM work_unit_leases
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4${forUpdate ? " FOR UPDATE" : ""}`,
      [organizationId, workstreamId, workUnitId, leaseId]
    );
    const row = rows[0];
    return row ? mapLease(row) : null;
  }
  async acquire(options) {
    const organizationId = options.organizationId ?? this.#organizationId;
    const workstreamId = options.workstreamId ?? this.#workstreamId;
    const nowIso = (options.now ?? /* @__PURE__ */ new Date()).toISOString();
    const leaseExpiresAt = computeLeaseExpiresAt(nowIso, options.ttlMs);
    const environmentId = options.environmentId ?? null;
    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query(
        `SELECT work_unit_id, revision, attempt_count FROM work_units
         WHERE organization_id = $1 AND workstream_id = $2
           AND status IN ('created', 'failed')
           AND (not_before IS NULL OR not_before <= $3)
         ORDER BY priority DESC, created_at ASC
         LIMIT 1
         FOR UPDATE SKIP LOCKED`,
        [organizationId, workstreamId, nowIso]
      );
      const candidate = rows[0];
      if (!candidate) return null;
      const sequence = await tx.query(`SELECT nextval('work_unit_lease_fencing_seq') AS fencing_token`);
      const fencingToken = Number(sequence.rows[0]?.fencing_token ?? 0);
      const leaseId = `lease_${randomUUID8()}`;
      await tx.query(
        `INSERT INTO work_unit_leases
           (organization_id, workstream_id, work_unit_id, lease_id, worker_id, environment_id, status,
            fencing_token, acquired_at, lease_expires_at, heartbeat_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, $8, $9, $10, $11)`,
        [
          organizationId,
          workstreamId,
          candidate.work_unit_id,
          leaseId,
          options.workerId,
          environmentId,
          fencingToken,
          nowIso,
          leaseExpiresAt,
          nowIso,
          nowIso
        ]
      );
      const { rowCount } = await tx.query(
        `UPDATE work_units
           SET status = 'running', attempt_count = attempt_count + 1, revision = revision + 1, updated_at = $1
         WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4`,
        [nowIso, organizationId, workstreamId, candidate.work_unit_id]
      );
      if (!rowCount) {
        throw new LeaseError(LEASE_ERROR_CODES.WORK_UNIT_NOT_FOUND, { workUnitId: candidate.work_unit_id });
      }
      const lease = await this.#selectLease(tx, organizationId, workstreamId, candidate.work_unit_id, leaseId);
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId });
      return { lease, workUnitId: candidate.work_unit_id };
    });
  }
  async renew(options) {
    const organizationId = options.organizationId ?? this.#organizationId;
    const workstreamId = options.workstreamId ?? this.#workstreamId;
    const nowIso = (options.now ?? /* @__PURE__ */ new Date()).toISOString();
    const leaseExpiresAt = computeLeaseExpiresAt(nowIso, options.ttlMs);
    return withTransaction(this.#client, async (tx) => {
      const lease = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId, true);
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId });
      assertLeaseRenewable(lease, nowIso);
      assertFencingToken(lease.fencingToken, options.fencingToken);
      await tx.query(
        `UPDATE work_unit_leases SET lease_expires_at = $1, heartbeat_at = $2
         WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5 AND lease_id = $6`,
        [leaseExpiresAt, nowIso, organizationId, workstreamId, options.workUnitId, options.leaseId]
      );
      const updated = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId);
      if (!updated) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId });
      return updated;
    });
  }
  async release(options) {
    const organizationId = options.organizationId ?? this.#organizationId;
    const workstreamId = options.workstreamId ?? this.#workstreamId;
    const nowIso = (options.now ?? /* @__PURE__ */ new Date()).toISOString();
    const resultStatus = options.resultStatus ?? "completed";
    return withTransaction(this.#client, async (tx) => {
      const lease = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId, true);
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId });
      if (options.fencingToken !== void 0) assertFencingToken(lease.fencingToken, options.fencingToken);
      if (lease.status !== "active") {
        throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId, status: lease.status });
      }
      await tx.query(
        `UPDATE work_unit_leases SET status = 'released', released_at = $1
         WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4 AND lease_id = $5`,
        [nowIso, organizationId, workstreamId, options.workUnitId, options.leaseId]
      );
      const { rowCount } = await tx.query(
        `UPDATE work_units SET status = $1, revision = revision + 1, updated_at = $2
         WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5`,
        [resultStatus, nowIso, organizationId, workstreamId, options.workUnitId]
      );
      if (!rowCount) throw new LeaseError(LEASE_ERROR_CODES.WORK_UNIT_NOT_FOUND, { workUnitId: options.workUnitId });
      const released = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId);
      if (!released) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId });
      return released;
    });
  }
  async expire(options) {
    const organizationId = options.organizationId ?? this.#organizationId;
    const workstreamId = options.workstreamId ?? this.#workstreamId;
    const nowIso = (options.now ?? /* @__PURE__ */ new Date()).toISOString();
    const expiryReason = options.expiryReason ?? LEASE_EXPIRY_REASONS.HEARTBEAT_TIMEOUT;
    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query(
        `SELECT * FROM work_unit_leases
         WHERE organization_id = $1 AND workstream_id = $2 AND status = 'active' AND lease_expires_at < $3
         FOR UPDATE`,
        [organizationId, workstreamId, nowIso]
      );
      const expired = [];
      for (const row of rows) {
        const lease = mapLease(row);
        await tx.query(
          `UPDATE work_unit_leases SET status = 'expired', released_at = $1, expiry_reason = $2
           WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5 AND lease_id = $6`,
          [nowIso, expiryReason, organizationId, workstreamId, lease.workUnitId, lease.leaseId]
        );
        await tx.query(
          `UPDATE work_units SET status = 'created', revision = revision + 1, updated_at = $1
           WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4`,
          [nowIso, organizationId, workstreamId, lease.workUnitId]
        );
        expired.push({ ...lease, status: "expired", releasedAt: nowIso, expiryReason });
      }
      return expired;
    });
  }
  async findByLeaseId(organizationId, workstreamId, workUnitId, leaseId) {
    return this.#selectLease(this.#client, organizationId, workstreamId, workUnitId, leaseId);
  }
  async findActiveLeaseByWorkUnit(organizationId, workstreamId, workUnitId) {
    const { rows } = await this.#client.query(
      `SELECT * FROM work_unit_leases
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND status = 'active'
       ORDER BY fencing_token DESC
       LIMIT 1`,
      [organizationId, workstreamId, workUnitId]
    );
    const row = rows[0];
    return row ? mapLease(row) : null;
  }
};
function createSqlLeaseRepository(client, options = {}) {
  return new SqlLeaseRepository(client, options);
}

// ../src/adapters/persistence/migration/one-shot-import.ts
import { createHash as createHash16 } from "node:crypto";
import { readFile as readFile5, readdir as readdir4 } from "node:fs/promises";
import { join as join8 } from "node:path";

// ../src/adapters/persistence/delivery-store.ts
import { createHash as createHash14, randomBytes as randomBytes5 } from "node:crypto";
import { appendFile as appendFile2, mkdir as mkdir2, open as open2, readFile as readFile2, rename as rename2, rm as rm2 } from "node:fs/promises";
import { dirname as dirname3, isAbsolute as isAbsolute2, join as join5 } from "node:path";
var UUID3 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE7 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SHA4 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var HASH3 = /^sha256:[0-9a-f]{64}$/;
var canonical4 = (value) => Array.isArray(value) ? value.map(canonical4) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).filter((key) => value[key] !== void 0).sort().map((key) => [key, canonical4(value[key])])
) : value;
var hash3 = (value) => createHash14("sha256").update(JSON.stringify(canonical4(value))).digest("hex");
async function sync(path) {
  const handle = await open2(path, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}
async function atomic(path, value) {
  await mkdir2(dirname3(path), { recursive: true });
  const temp = `${path}.tmp-${process.pid}-${randomBytes5(6).toString("hex")}`;
  const handle = await open2(temp, "wx", 384);
  try {
    await handle.writeFile(`${JSON.stringify(value)}
`);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename2(temp, path);
  await sync(dirname3(path));
}
async function append(path, value) {
  await mkdir2(dirname3(path), { recursive: true });
  await appendFile2(path, `${JSON.stringify(value)}
`, { mode: 384 });
  await sync(path);
}
var validSnapshot2 = (value) => value !== null && typeof value === "object" && value.schemaVersion === "1" && UUID3.test(value.namespaceId ?? "") && SAFE7.test(value.deliveryId ?? "") && SAFE7.test(value.workflowId ?? "") && UUID3.test(value.environmentId ?? "") && HASH3.test(value.environmentHash ?? "") && UUID3.test(value.parentCaseId ?? "") && SAFE7.test(value.runtimeId ?? "") && SHA4.test(value.baseCommit ?? "") && SHA4.test(value.headCommit ?? "") && Number.isSafeInteger(value.revision) && value.revision > 0;
var DeliveryStore = class {
  dataRoot;
  fault;
  locks;
  constructor(dataRoot, { fault = async () => {
  } } = {}) {
    if (!isAbsolute2(dataRoot)) throw new Error("INVALID_DATA_ROOT");
    this.dataRoot = dataRoot;
    this.fault = fault;
    this.locks = /* @__PURE__ */ new Map();
  }
  async initialize() {
    await mkdir2(join5(this.dataRoot, "deliveries"), { recursive: true });
  }
  paths(namespaceId, deliveryId) {
    if (!UUID3.test(namespaceId ?? "") || !SAFE7.test(deliveryId ?? "")) throw new Error("INVALID_DELIVERY_SCOPE");
    const directory = join5(
      this.dataRoot,
      "deliveries",
      namespaceId,
      createHash14("sha256").update(`${namespaceId}:${deliveryId}`).digest("hex")
    );
    return {
      directory,
      snapshot: join5(directory, "delivery.json"),
      journal: join5(directory, "operations.jsonl"),
      pending: join5(directory, "pending.json")
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
      return JSON.parse(await readFile2(path, "utf8"));
    } catch (error2) {
      if (error2?.code === "ENOENT") return null;
      throw Object.assign(new Error("CORRUPT_DELIVERY_STORAGE"), { code: "CORRUPT_DELIVERY_STORAGE" });
    }
  }
  async journal(namespaceId, deliveryId) {
    try {
      return (await readFile2(this.paths(namespaceId, deliveryId).journal, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
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
    if (last.state === "succeeded" && pending.snapshotHash === hash3(pendingSnapshot)) {
      await atomic(paths.snapshot, pendingSnapshot);
      await rm2(paths.pending, { force: true });
      return;
    }
    throw Object.assign(new Error("DELIVERY_OPERATION_INDETERMINATE"), { code: "DELIVERY_OPERATION_INDETERMINATE" });
  }
  async read(namespaceId, deliveryId) {
    const paths = this.paths(namespaceId, deliveryId);
    await this._recover(paths);
    const snapshot = await this._json(paths.snapshot);
    if (!snapshot) return null;
    if (!validSnapshot2(snapshot) || snapshot.snapshotHash !== hash3({ ...snapshot, snapshotHash: void 0 }))
      throw Object.assign(new Error("CORRUPT_DELIVERY_STORAGE"), { code: "CORRUPT_DELIVERY_STORAGE" });
    return snapshot;
  }
  async create(input) {
    return this._locked(input.namespaceId, input.deliveryId, async () => {
      const current = await this.read(input.namespaceId, input.deliveryId);
      if (current)
        return JSON.stringify({ ...current, snapshotHash: void 0 }) === JSON.stringify(input) ? { ok: true, changed: false, snapshot: current } : { ok: false, error: { code: "DELIVERY_IDENTITY_CONFLICT" } };
      if (!validSnapshot2(input)) return { ok: false, error: { code: "INVALID_DELIVERY_SNAPSHOT" } };
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
      const scopeHash = deliveryScopeHash(namespaceId, request, execution2), semanticHash2 = deliverySemanticHash(request), prior = records.find((item) => item.scopeHash === scopeHash && item.state === "succeeded");
      if (prior) {
        if (prior.semanticHash !== semanticHash2) return { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        return { ok: true, changed: false, idempotent: true, snapshot: current };
      }
      const decision = evaluateDeliveryPromotion({ request, snapshot: current, definition, evidence, execution: execution2 });
      if (!decision.allowed) return { ok: false, error: decision };
      const next = applyDeliveryPromotion(current, request);
      return this._write(current, next, {
        kind: "delivery_promoted",
        idempotencyKey: request.idempotencyKey,
        scopeHash,
        semanticHash: semanticHash2,
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
    const snapshot = { ...clean, snapshotHash: hash3(clean) };
    await atomic(paths.pending, { operation, snapshot, snapshotHash: hash3(snapshot) });
    await append(paths.journal, operation);
    await this.fault("after-pending-journal");
    const running = { ...operation, state: "running", timestamp: (/* @__PURE__ */ new Date()).toISOString() };
    await append(paths.journal, running);
    await this.fault("after-running");
    const succeeded = {
      ...operation,
      state: "succeeded",
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      resultHash: snapshot.snapshotHash
    };
    await append(paths.journal, succeeded);
    await this.fault("after-success");
    await atomic(paths.snapshot, snapshot);
    await this.fault("after-snapshot");
    await rm2(paths.pending, { force: true });
    return { ok: true, changed: true, idempotent: false, snapshot };
  }
  async recordOperation(namespaceId, deliveryId, input) {
    return this._locked(namespaceId, deliveryId, async () => {
      const records = await this.journal(namespaceId, deliveryId), operationId = createHash14("sha256").update(`${namespaceId}:${deliveryId}:${input.idempotencyKey}`).digest("hex"), prior = records.filter((item) => item.operationId === operationId).at(-1);
      const semanticHash2 = hash3(input.facts);
      if (prior) {
        if (prior.semanticHash !== semanticHash2) return { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
        return { ok: true, changed: false, operation: prior };
      }
      const operation = {
        schemaVersion: "1",
        operationId,
        deliveryId,
        kind: input.kind,
        state: input.state,
        semanticHash: semanticHash2,
        facts: input.facts,
        timestamp: (/* @__PURE__ */ new Date()).toISOString()
      };
      await append(this.paths(namespaceId, deliveryId).journal, operation);
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
        deploymentRef: canonical4(request.deploymentRef),
        priorArtifactRef: canonical4(request.priorArtifactRef),
        priorReleaseRef: canonical4(request.priorReleaseRef),
        reasonCode: request.reasonCode,
        ...request.reason ? { reason: request.reason } : {},
        requestedAt: (/* @__PURE__ */ new Date()).toISOString(),
        requestedBy: canonical4(execution2)
      };
      await append(this.paths(namespaceId, deliveryId).journal, record2);
      return { ok: true, changed: true, idempotent: false, request: record2 };
    });
  }
  async approveRollbackRequest(namespaceId, deliveryId, rollbackRequestId, approval) {
    return this._locked(namespaceId, deliveryId, async () => {
      const snapshot = await this.read(namespaceId, deliveryId);
      if (!snapshot) return { ok: false, error: { code: "DELIVERY_NOT_FOUND" } };
      const projection = this._deliveryOperationProjection(await this.journal(namespaceId, deliveryId)), current = projection.rollbackRequests.find((item) => item.rollbackRequestId === rollbackRequestId);
      if (!current) return { ok: false, error: { code: "ROLLBACK_REQUEST_NOT_FOUND" } };
      const scopeHash = `sha256:${hash3({ rollbackRequestId, idempotencyKey: approval.idempotencyKey })}`, semanticHash2 = `sha256:${hash3({ rollbackRequestId, expectedRevision: approval.expectedRevision, actorId: approval.execution.actorId })}`, prior = projection.rollbackRequestHistory.find((item) => item.approvalScopeHash === scopeHash);
      if (prior)
        return prior.approvalSemanticHash === semanticHash2 ? { ok: true, changed: false, idempotent: true, request: prior } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      if (snapshot.revision !== approval.expectedRevision || current.expectedRevision !== approval.expectedRevision)
        return { ok: false, error: { code: "REVISION_CONFLICT" } };
      if (current.status !== "requested") return { ok: false, error: { code: "ROLLBACK_REQUEST_ALREADY_DECIDED" } };
      const record2 = {
        ...current,
        status: "approved",
        approvedAt: (/* @__PURE__ */ new Date()).toISOString(),
        approvedBy: canonical4(approval.execution),
        approvalScopeHash: scopeHash,
        approvalSemanticHash: semanticHash2,
        approvalIdempotencyKey: approval.idempotencyKey
      };
      await append(this.paths(namespaceId, deliveryId).journal, record2);
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
        targetRef: canonical4(targetRef),
        artifactRef: normalized.value.artifactRef ?? normalized.value.priorArtifactRef,
        releaseRef: normalized.value.releaseRef ?? normalized.value.priorReleaseRef,
        deploymentRef: normalized.value.deploymentRef,
        rollbackRef: normalized.value.rollbackRef,
        state: "pending",
        attempt: 0,
        requestedAt: now,
        startedAt: void 0,
        completedAt: void 0,
        execution: canonical4(execution2),
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
      await append(this.paths(namespaceId, deliveryId).journal, persisted);
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
      await append(this.paths(namespaceId, deliveryId).journal, clean);
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

// ../src/adapters/persistence/work-unit-environment-store.ts
import { createHash as createHash15, randomBytes as randomBytes6 } from "node:crypto";
import { appendFile as appendFile3, lstat, mkdir as mkdir3, open as open3, readFile as readFile3, readdir as readdir2, realpath, rename as rename3, rm as rm3 } from "node:fs/promises";
import { dirname as dirname4, isAbsolute as isAbsolute3, join as join6, relative, sep } from "node:path";
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
var digest2 = (value) => createHash15("sha256").update(value).digest("hex");
var snapshotHash2 = (e) => digest2(JSON.stringify(e, Object.keys(e).sort()));
var contained = (root, path) => {
  const r = relative(root, path);
  return r !== "" && !r.startsWith(`..${sep}`) && r !== ".." && !isAbsolute3(r);
};
async function syncDir(p) {
  const h = await open3(p, "r");
  try {
    await h.sync();
  } finally {
    await h.close();
  }
}
async function atomic2(p, v) {
  await mkdir3(dirname4(p), { recursive: true });
  const t = `${p}.tmp-${process.pid}-${randomBytes6(6).toString("hex")}`;
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
async function append2(p, v) {
  await appendFile3(p, `${JSON.stringify(v)}
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
    if (typeof dataRoot !== "string" || !isAbsolute3(dataRoot))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_DATA_ROOT);
    this.dataRoot = dataRoot;
    this.fault = fault;
    this.locks = /* @__PURE__ */ new Map();
    this.root = null;
  }
  async initialize() {
    await mkdir3(join6(this.dataRoot, "environments"), { recursive: true });
    this.root = await realpath(join6(this.dataRoot, "environments"));
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
    const canonical6 = await realpath(path);
    if (path !== this.root && !contained(this.root, canonical6))
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
    const directory = join6(this.root, ns, digest2(`${ns}:${id2}`));
    if (!contained(this.root, directory))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT);
    return {
      directory,
      snapshot: join6(directory, "environment.json"),
      events: join6(directory, "events.jsonl"),
      pending: join6(directory, "pending.json")
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
      return JSON.parse(await readFile3(p, "utf8"));
    } catch (e) {
      if (e?.code === "ENOENT") return missing;
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {}, e);
    }
  }
  async _recover(p, ns, id2) {
    const q = await this._json(p.pending);
    if (!q) return;
    const valid = q && Number.isSafeInteger(q.revision) && q.revision > 0 && q.environment?.namespaceId === ns && q.environment?.environmentId === id2 && validateWorkUnitEnvironment(q.environment).ok && q.environmentHash === snapshotHash2(q.environment);
    if (!valid)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "pending" });
    let facts;
    try {
      facts = (await readFile3(p.events, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    } catch (e) {
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "journal" }, e);
    }
    if (!facts.some((f) => f.revision === q.revision && f.environmentId === id2 && f.environmentHash === q.environmentHash))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: "recovery_binding"
      });
    await atomic2(p.snapshot, q);
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
    if (!Number.isSafeInteger(s.revision) || s.environmentHash !== snapshotHash2(s.environment) || !validateWorkUnitEnvironment(s.environment).ok)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE);
    return s;
  }
  async list(ns, { states } = {}) {
    this._namespace(ns);
    let es;
    const root = join6(this.root, ns);
    const probe = this.paths(ns, "list-probe");
    await this._guard(probe);
    try {
      es = await readdir2(root, { withFileTypes: true });
    } catch (e) {
      if (e?.code === "ENOENT") return [];
      throw e;
    }
    const out = [];
    for (const x of es) {
      const d = join6(root, x.name);
      const p = {
        directory: d,
        snapshot: join6(d, "environment.json"),
        events: join6(d, "events.jsonl"),
        pending: join6(d, "pending.json")
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
  async transition(ns, id2, next, { expectedRevision, errorCode: errorCode2 } = {}) {
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
        errorCode2
      );
    });
  }
  async _write(c, e, kind, errorCode2) {
    const p = this.paths(e.namespaceId, e.environmentId);
    await this._guard(p);
    const revision = (c?.revision ?? 0) + 1;
    const environmentHash = snapshotHash2(e);
    const s = { revision, environmentHash, environment: e };
    await mkdir3(p.directory, { recursive: true });
    await this._guard(p, { environmentMayBeMissing: false });
    await atomic2(p.pending, s);
    await this.fault("after-pending");
    await append2(p.events, {
      kind,
      revision,
      environmentId: e.environmentId,
      environmentHash,
      lifecycleState: e.lifecycleState,
      timestamp: (/* @__PURE__ */ new Date()).toISOString(),
      ...errorCode2 ? { errorCode: errorCode2 } : {}
    });
    await this.fault("after-journal");
    await atomic2(p.snapshot, s);
    await this.fault("after-snapshot");
    await rm3(p.pending, { force: true });
    return { ok: true, changed: true, snapshot: s };
  }
};

// ../src/application/oracle/oracle-definition-registry.ts
import { readFile as readFile4, readdir as readdir3 } from "node:fs/promises";
import { join as join7 } from "node:path";
function createFilesystemOracleDefinitionSource(root) {
  return {
    listFiles: () => readdir3(root),
    readFile: (fileName) => readFile4(join7(root, fileName), "utf8")
  };
}
var OracleDefinitionRegistry = class extends OracleDefinitionRegistryCore {
  constructor(root) {
    super(createFilesystemOracleDefinitionSource(root));
  }
};

// ../src/adapters/persistence/migration/one-shot-import.ts
async function listDirectoryNames(path) {
  try {
    const entries = await readdir4(path, { withFileTypes: true });
    return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name).sort((left, right) => left.localeCompare(right));
  } catch (error2) {
    if (error2?.code === "ENOENT") return [];
    throw error2;
  }
}
async function readJsonIfExists(path) {
  try {
    return JSON.parse(await readFile5(path, "utf8"));
  } catch (error2) {
    if (error2?.code === "ENOENT") return null;
    throw error2;
  }
}
var FilesystemDefinitionRegistry = class {
  constructor(root) {
    this.root = root;
  }
  loaded = null;
  async load() {
    if (this.loaded) return this.loaded;
    const definitions = /* @__PURE__ */ new Map();
    for (const type of await listDirectoryNames(this.root)) {
      const typeRoot = join8(this.root, type);
      const files = (await readdir4(typeRoot, { withFileTypes: true })).filter(
        (entry) => entry.isFile() && entry.name.endsWith(".json")
      );
      for (const file of files) {
        const parsed = JSON.parse(await readFile5(join8(typeRoot, file.name), "utf8"));
        const validated = validateWorkflowDefinition(parsed);
        if (!validated.ok) throw new Error(`INVALID_DEFINITION_FILE:${file.name}`);
        const expectedVersion = file.name.slice(0, -".json".length);
        if (validated.definition.workflowType !== type || validated.definition.version !== expectedVersion)
          throw new Error(`DEFINITION_PATH_MISMATCH:${file.name}`);
        const definitionHash = hashWorkflowDefinition(validated.definition);
        definitions.set(
          `${validated.definition.workflowType}@${validated.definition.version}`,
          Object.freeze({ ...validated.definition, definitionHash })
        );
      }
    }
    this.loaded = definitions;
    return definitions;
  }
  async list() {
    return [...(await this.load()).values()];
  }
  async get(workflowType, version) {
    return (await this.load()).get(`${workflowType}@${version}`) ?? null;
  }
  async resolveUnique(workflowType) {
    const matches2 = [...(await this.load()).values()].filter((item) => item.workflowType === workflowType);
    if (matches2.length === 0) throw new Error("WORKFLOW_DEFINITION_NOT_FOUND");
    return matches2[0];
  }
};
var FilesystemProjectionReader = class {
  constructor(dataRoot) {
    this.dataRoot = dataRoot;
  }
  async initialize() {
    return void 0;
  }
  directory(namespaceId, workflowId) {
    const digest4 = createHash16("sha256").update(`${namespaceId}:${workflowId}`, "utf8").digest("hex");
    return join8(this.dataRoot, "workflows", namespaceId, digest4);
  }
  async read(namespaceId, workflowId) {
    const snapshot = await readJsonIfExists(join8(this.directory(namespaceId, workflowId), "projection.json"));
    if (!snapshot) return null;
    return { instance: snapshot.instance, projection: snapshot.projection };
  }
  async list(namespaceId) {
    const out = [];
    const namespaceRoot = join8(this.dataRoot, "workflows", namespaceId);
    for (const directory of await listDirectoryNames(namespaceRoot)) {
      const snapshot = await readJsonIfExists(join8(namespaceRoot, directory, "projection.json"));
      if (snapshot) out.push({ instance: snapshot.instance, projection: snapshot.projection });
    }
    return out;
  }
  async start() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async transition() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async remove() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async restore() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async purge() {
    throw new Error("IMPORT_READ_ONLY");
  }
};
var FilesystemEvidenceReader = class {
  constructor(dataRoot) {
    this.dataRoot = dataRoot;
  }
  async list(namespaceId, storageId) {
    return readJsonLines(join8(this.dataRoot, "workflows", namespaceId, storageId, "evidence.jsonl"));
  }
  async record() {
    throw new Error("IMPORT_READ_ONLY");
  }
};
var FilesystemInteractionReader = class {
  constructor(dataRoot) {
    this.dataRoot = dataRoot;
  }
  path(namespaceId, storageId) {
    return join8(this.dataRoot, "workflows", namespaceId, storageId, "human-interactions.jsonl");
  }
  async events(namespaceId, storageId) {
    return readJsonLines(join8(this.path(namespaceId, storageId)));
  }
  async list(namespaceId, storageId) {
    return projectInteractionEvents(await this.events(namespaceId, storageId));
  }
  async reconcileOpen() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async open() {
    throw new Error("IMPORT_READ_ONLY");
  }
  async transact() {
    throw new Error("IMPORT_READ_ONLY");
  }
};
function projectInteractionEvents(events) {
  const projected = /* @__PURE__ */ new Map();
  for (const event of events) {
    if (event.event === "interaction_opening") {
      const interactionId = event.interaction?.interactionId;
      if (!interactionId || projected.has(interactionId)) throw new Error("CORRUPT_INTERACTION_STORAGE");
      projected.set(interactionId, { ...event.interaction, status: "opening" });
    } else if (event.event === "interaction_opened") {
      const interactionId = event.interaction?.interactionId;
      const current = interactionId ? projected.get(interactionId) : void 0;
      const revision = openedInteractionRevision(event);
      if (current?.status === "opening") {
        const validRevision = current.interactionType === "retry" ? revision === current.expectedRevision : revision > current.expectedRevision;
        if (!Number.isSafeInteger(revision) || !validRevision) throw new Error("CORRUPT_INTERACTION_STORAGE");
        projected.set(interactionId, { ...current, status: "open", revision });
      } else if (!current) {
        if (!interactionId || !Number.isSafeInteger(revision) || revision < 1)
          throw new Error("CORRUPT_INTERACTION_STORAGE");
        projected.set(interactionId, { ...event.interaction, status: "open", revision });
      } else {
        throw new Error("CORRUPT_INTERACTION_STORAGE");
      }
    } else if (event.event === "interaction_open_aborted") {
      const current = projected.get(event.interactionId);
      if (!current || current.status !== "opening") throw new Error("CORRUPT_INTERACTION_STORAGE");
      projected.set(event.interactionId, { ...current, status: "aborted", errorCode: event.errorCode });
    } else if (event.event === "interaction_transitioned") {
      const current = projected.get(event.interactionId);
      if (!current || current.status !== "open") throw new Error("CORRUPT_INTERACTION_STORAGE");
      projected.set(event.interactionId, {
        ...current,
        status: "replied",
        reply: event.reply,
        actorId: event.actorId,
        repliedAt: event.repliedAt,
        evidenceId: event.evidenceId,
        transitionRequestId: event.transitionRequestId,
        revision: event.revision
      });
    } else {
      throw new Error("CORRUPT_INTERACTION_STORAGE");
    }
  }
  return [...projected.values()].sort(
    (left, right) => String(left.openedAt).localeCompare(String(right.openedAt)) || String(left.interactionId).localeCompare(String(right.interactionId))
  );
}
var ATTEMPT_DB_STATUS2 = Object.freeze({
  starting: "running",
  running: "running",
  succeeded: "completed",
  failed: "failed",
  indeterminate: "timed_out",
  interrupted: "cancelled"
});
var RESULT_DB_STATUS2 = Object.freeze({
  PASS: "success",
  FAIL: "failure"
});
var ENVIRONMENT_DB_STATUS = Object.freeze({
  provisioning: "busy",
  active: "ready",
  completed: "busy",
  abandoned: "busy",
  error: "busy",
  removed: "decommissioned"
});
function interactionDbStatus(status) {
  return status === "replied" ? "answered" : "waiting";
}
function evidenceSourceOf(record2) {
  const source = record2?.source;
  if (typeof source === "string") return source;
  return source?.kind ?? source?.runtimeId ?? "unknown";
}
function evidenceProducerOf(record2) {
  const source = record2?.source;
  return source?.agentId ?? source?.actorId ?? "system";
}
function workflowDefinitionPlan(options) {
  const repository = new FilesystemWorkflowDefinitionRepository(
    new FilesystemDefinitionRegistry(options.definitionsRoot)
  );
  return {
    context: "workflow-definition",
    table: "workflow_definitions",
    primaryKey: ["organization_id", "workflow_type", "version"],
    selectColumns: ["workflow_type", "version", "definition_hash", "definition_json"],
    filterWorkstream: false,
    async load() {
      const list = await repository.list();
      return list.map((item) => {
        const { definitionHash, ...definition } = item;
        return {
          key: `${item.workflowType}@${item.version}`,
          columns: {
            organization_id: options.organizationId,
            workstream_id: null,
            workflow_type: item.workflowType,
            version: item.version,
            definition_hash: definitionHash,
            definition_json: definition
          },
          jsonColumns: ["definition_json"],
          aggregate: { ...definition, definitionHash }
        };
      });
    },
    fromRow(row) {
      const definition = parseJsonColumn(row.definition_json);
      return {
        key: `${row.workflow_type}@${row.version}`,
        aggregate: { ...definition, definitionHash: row.definition_hash }
      };
    }
  };
}
function workflowInstancePlan(options) {
  const repository = new FilesystemWorkflowInstanceRepository(new FilesystemProjectionReader(options.dataRoot));
  return {
    context: "workflow-instance",
    table: "workflow_instances",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "workflow_id"],
    selectColumns: ["namespace_id", "workflow_id", "instance_json", "projection_json"],
    filterWorkstream: true,
    async load() {
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "workflows"))) {
        const snapshots = await repository.list(namespaceId);
        for (const projection of snapshots) {
          const snapshot = await repository.get(namespaceId, projection.workflowId);
          if (!snapshot) continue;
          const createdAt = snapshot.instance?.createdAt ?? (/* @__PURE__ */ new Date(0)).toISOString();
          records.push({
            key: `${namespaceId}/${projection.workflowId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              namespace_id: namespaceId,
              workflow_id: projection.workflowId,
              revision: Number.isSafeInteger(snapshot.instance?.revision) ? snapshot.instance.revision : 1,
              status: "active",
              instance_json: snapshot.instance,
              projection_json: snapshot.projection,
              creation_command_hash: snapshot.instance?.creationCommandHash ?? null,
              created_at: createdAt,
              updated_at: createdAt
            },
            jsonColumns: ["instance_json", "projection_json"],
            aggregate: { instance: snapshot.instance, projection: snapshot.projection }
          });
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}`,
        aggregate: {
          instance: parseJsonColumn(row.instance_json),
          projection: parseJsonColumn(row.projection_json)
        }
      };
    }
  };
}
function workflowEvidencePlan(options) {
  const repository = new FilesystemWorkflowEvidenceRepository(new FilesystemEvidenceReader(options.dataRoot));
  return {
    context: "workflow-evidence",
    table: "workflow_evidence",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "workflow_id", "evidence_id"],
    selectColumns: ["namespace_id", "workflow_id", "evidence_id", "payload"],
    filterWorkstream: true,
    async load() {
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "workflows"))) {
        for (const storageId of await listDirectoryNames(join8(options.dataRoot, "workflows", namespaceId))) {
          const list = await repository.list(namespaceId, storageId);
          for (const record2 of list) {
            records.push({
              key: `${namespaceId}/${record2.workflowId}/${record2.evidenceId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: record2.workflowId,
                evidence_id: record2.evidenceId,
                evidence_type: record2.kind ?? "unknown",
                source: evidenceSourceOf(record2),
                producer: evidenceProducerOf(record2),
                payload: record2,
                created_at: record2.observedAt ?? (/* @__PURE__ */ new Date(0)).toISOString()
              },
              jsonColumns: ["payload"],
              aggregate: record2
            });
          }
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.evidence_id}`,
        aggregate: parseJsonColumn(row.payload)
      };
    }
  };
}
function workflowHumanInteractionPlan(options) {
  const repository = new FilesystemWorkflowHumanInteractionRepository(
    new FilesystemInteractionReader(options.dataRoot)
  );
  return {
    context: "workflow-human-interaction",
    table: "human_interactions",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "workflow_id", "interaction_id"],
    selectColumns: ["namespace_id", "workflow_id", "interaction_id", "payload"],
    filterWorkstream: true,
    async load() {
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "workflows"))) {
        for (const storageId of await listDirectoryNames(join8(options.dataRoot, "workflows", namespaceId))) {
          const list = await repository.list(namespaceId, storageId);
          for (const record2 of list) {
            records.push({
              key: `${namespaceId}/${record2.workflowId}/${record2.interactionId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: record2.workflowId,
                interaction_id: record2.interactionId,
                interaction_type: record2.interactionType ?? record2.kind ?? "unknown",
                status: interactionDbStatus(record2.status),
                revision: Number.isSafeInteger(record2.revision) && record2.revision >= 1 ? record2.revision : 1,
                payload: record2
              },
              jsonColumns: ["payload"],
              aggregate: record2
            });
          }
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.interaction_id}`,
        aggregate: parseJsonColumn(row.payload)
      };
    }
  };
}
function agentStepAttemptPlan(options) {
  const repository = new FilesystemAgentStepAttemptRepository(new AgentStepAttemptStore(options.dataRoot));
  return {
    context: "agent-step-attempt",
    table: "agent_step_attempts",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "workflow_id", "step_id", "attempt_id"],
    selectColumns: ["namespace_id", "workflow_id", "step_id", "attempt_id", "payload"],
    filterWorkstream: true,
    async load() {
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "workflows"))) {
        for (const storageId of await listDirectoryNames(join8(options.dataRoot, "workflows", namespaceId))) {
          const events = await repository.list(namespaceId, storageId);
          if (events.length === 0) continue;
          const latest = /* @__PURE__ */ new Map();
          for (const attempt of events) {
            const previous = latest.get(attempt.attemptId);
            latest.set(attempt.attemptId, { attempt, revision: (previous?.revision ?? 0) + 1 });
          }
          for (const { attempt, revision } of latest.values()) {
            records.push({
              key: `${namespaceId}/${attempt.workflowId}/${storageId}/${attempt.attemptId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: attempt.workflowId,
                step_id: storageId,
                attempt_id: attempt.attemptId,
                agent_id: attempt.agentName ?? "unknown",
                status: ATTEMPT_DB_STATUS2[attempt.status] ?? "running",
                revision,
                idempotency_key: null,
                payload: attempt
              },
              jsonColumns: ["payload"],
              aggregate: attempt
            });
          }
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.step_id}/${row.attempt_id}`,
        aggregate: parseJsonColumn(row.payload)
      };
    }
  };
}
function agentStepResultPlan(options) {
  const repository = new FilesystemAgentStepResultRepository(new AgentStepResultStore(options.dataRoot));
  return {
    context: "agent-step-result",
    table: "agent_step_results",
    primaryKey: [
      "organization_id",
      "workstream_id",
      "namespace_id",
      "workflow_id",
      "step_id",
      "attempt_id",
      "result_id"
    ],
    selectColumns: ["namespace_id", "workflow_id", "step_id", "result_id", "payload"],
    filterWorkstream: true,
    async load() {
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "workflows"))) {
        for (const storageId of await listDirectoryNames(join8(options.dataRoot, "workflows", namespaceId))) {
          const events = await repository.list(namespaceId, storageId);
          for (const event of events) {
            if (event.type !== "result-submitted") continue;
            records.push({
              key: `${namespaceId}/${event.workflowId}/${storageId}/${event.resultId}`,
              columns: {
                organization_id: options.organizationId,
                workstream_id: options.workstreamId,
                namespace_id: namespaceId,
                workflow_id: event.workflowId,
                step_id: storageId,
                attempt_id: event.attemptId,
                result_id: event.resultId,
                result_status: RESULT_DB_STATUS2[event.status] ?? "success",
                semantic_signature: event.resultHash ?? null,
                payload: event,
                created_at: event.submittedAt ?? (/* @__PURE__ */ new Date(0)).toISOString()
              },
              jsonColumns: ["payload"],
              aggregate: event
            });
          }
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.workflow_id}/${row.step_id}/${row.result_id}`,
        aggregate: parseJsonColumn(row.payload)
      };
    }
  };
}
function oracleExecutionPlan(options) {
  const registry2 = new OracleDefinitionRegistry(options.oraclesRoot);
  return {
    context: "oracle-execution",
    table: "oracle_executions",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "workflow_id", "execution_id"],
    selectColumns: ["execution_id", "payload"],
    filterWorkstream: true,
    async load() {
      try {
        await registry2.initialize();
      } catch (error2) {
        if (error2?.code === "ENOENT") return [];
        throw error2;
      }
      const repository = new FilesystemOracleExecutionRepository(registry2);
      const list = await repository.list();
      return list.map((definition) => ({
        key: `${definition.id}@${definition.version}`,
        columns: {
          organization_id: options.organizationId,
          workstream_id: options.workstreamId,
          namespace_id: "oracle-registry",
          workflow_id: definition.id,
          execution_id: `${definition.id}@${definition.version}`,
          oracle_id: definition.id,
          status: "succeeded",
          revision: 1,
          evidence_id: null,
          artifact_id: null,
          payload: definition
        },
        jsonColumns: ["payload"],
        aggregate: definition
      }));
    },
    fromRow(row) {
      return { key: String(row.execution_id), aggregate: parseJsonColumn(row.payload) };
    }
  };
}
function workEnvironmentPlan(options) {
  const store = new WorkUnitEnvironmentStore(options.dataRoot);
  return {
    context: "work-environment",
    table: "work_environments",
    primaryKey: ["organization_id", "workstream_id", "environment_id"],
    selectColumns: ["environment_id", "payload"],
    filterWorkstream: true,
    async load() {
      await store.initialize();
      const repository = new FilesystemWorkEnvironmentRepository(store);
      const records = [];
      for (const namespaceId of await listDirectoryNames(join8(options.dataRoot, "environments"))) {
        const snapshots = await repository.list(namespaceId);
        for (const snapshot of snapshots) {
          const environment = snapshot.environment;
          records.push({
            key: `${environment.namespaceId}/${environment.environmentId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              environment_id: environment.environmentId,
              env_type: "work-unit-environment",
              status: ENVIRONMENT_DB_STATUS[environment.lifecycleState] ?? "busy",
              revision: snapshot.revision,
              payload: environment
            },
            jsonColumns: ["payload"],
            aggregate: environment
          });
        }
      }
      return records;
    },
    fromRow(row) {
      const environment = parseJsonColumn(row.payload);
      return { key: `${environment.namespaceId}/${row.environment_id}`, aggregate: environment };
    }
  };
}
function deliveryPlan(options) {
  const store = new DeliveryStore(options.dataRoot);
  const repository = new FilesystemDeliveryRepository(store);
  return {
    context: "delivery",
    table: "deliveries",
    primaryKey: ["organization_id", "workstream_id", "namespace_id", "delivery_id"],
    selectColumns: ["namespace_id", "delivery_id", "payload"],
    filterWorkstream: true,
    async load() {
      const records = [];
      const deliveriesRoot = join8(options.dataRoot, "deliveries");
      for (const namespaceId of await listDirectoryNames(deliveriesRoot)) {
        for (const directory of await listDirectoryNames(join8(deliveriesRoot, namespaceId))) {
          const raw = await readJsonIfExists(join8(deliveriesRoot, namespaceId, directory, "delivery.json"));
          if (!raw?.deliveryId) continue;
          const snapshot = await repository.read(namespaceId, raw.deliveryId);
          if (!snapshot) continue;
          records.push({
            key: `${namespaceId}/${snapshot.deliveryId}`,
            columns: {
              organization_id: options.organizationId,
              workstream_id: options.workstreamId,
              namespace_id: namespaceId,
              delivery_id: snapshot.deliveryId,
              revision: Number.isSafeInteger(snapshot.revision) ? snapshot.revision : 1,
              stage: snapshot.stage ?? "unknown",
              payload: snapshot
            },
            jsonColumns: ["payload"],
            aggregate: snapshot
          });
        }
      }
      return records;
    },
    fromRow(row) {
      return {
        key: `${row.namespace_id}/${row.delivery_id}`,
        aggregate: parseJsonColumn(row.payload)
      };
    }
  };
}
function buildPlans(options) {
  return [
    workflowDefinitionPlan(options),
    workflowInstancePlan(options),
    workflowEvidencePlan(options),
    workflowHumanInteractionPlan(options),
    agentStepAttemptPlan(options),
    agentStepResultPlan(options),
    oracleExecutionPlan(options),
    workEnvironmentPlan(options),
    deliveryPlan(options)
  ];
}
function resolveOptions(options) {
  const dataRoot = options.dataRoot;
  if (typeof dataRoot !== "string" || dataRoot.length === 0) throw new Error("ONE_SHOT_IMPORT_INVALID_DATA_ROOT");
  return {
    dataRoot,
    organizationId: options.organizationId ?? DEFAULT_ORGANIZATION_ID,
    workstreamId: options.workstreamId ?? DEFAULT_WORKSTREAM_ID,
    definitionsRoot: options.definitionsRoot ?? join8(dataRoot, "definitions"),
    oraclesRoot: options.oraclesRoot ?? join8(dataRoot, "oracles")
  };
}
async function writeRecord(tx, plan, record2) {
  const columns = Object.keys(record2.columns);
  const placeholders = columns.map(
    (column, index) => record2.jsonColumns.includes(column) ? `$${index + 1}::jsonb` : `$${index + 1}`
  );
  const params = columns.map(
    (column) => record2.jsonColumns.includes(column) ? JSON.stringify(record2.columns[column]) : record2.columns[column]
  );
  const updates = columns.filter((column) => !plan.primaryKey.includes(column));
  const conflict = updates.length ? `ON CONFLICT (${plan.primaryKey.join(", ")}) DO UPDATE SET ${updates.map((column) => `${column} = EXCLUDED.${column}`).join(", ")}` : "ON CONFLICT DO NOTHING";
  await tx.query(
    `INSERT INTO ${plan.table} (${columns.join(", ")}) VALUES (${placeholders.join(", ")}) ${conflict}`,
    params
  );
}
async function readSql(client, plan, options) {
  const where = plan.filterWorkstream ? "WHERE organization_id = $1 AND workstream_id = $2" : "WHERE organization_id = $1";
  const params = plan.filterWorkstream ? [options.organizationId, options.workstreamId] : [options.organizationId];
  const { rows } = await client.query(
    `SELECT ${plan.selectColumns.join(", ")} FROM ${plan.table} ${where}`,
    params
  );
  return rows.map((row) => plan.fromRow(row));
}
async function runOneShotImport(options) {
  const resolved = resolveOptions(options);
  for (const plan of buildPlans(resolved)) {
    const records = await plan.load(resolved);
    if (records.length === 0) continue;
    await withTransaction(options.sqlClient, async (tx) => {
      for (const record2 of records) await writeRecord(tx, plan, record2);
    });
  }
  return verifyImport(options);
}
async function verifyImport(options) {
  const resolved = resolveOptions(options);
  const contexts = {};
  let totalFilesystemAggregates = 0;
  let totalSqlAggregates = 0;
  let ok = true;
  for (const plan of buildPlans(resolved)) {
    const filesystem = await plan.load(resolved);
    const sql = await readSql(options.sqlClient, plan, resolved);
    const filesystemByKey = new Map(filesystem.map((record2) => [record2.key, record2.aggregate]));
    const sqlByKey = new Map(sql.map((record2) => [record2.key, record2.aggregate]));
    const discrepancies = [];
    for (const [key, aggregate] of filesystemByKey) {
      const filesystemHash = computeCanonicalHash(aggregate);
      if (!sqlByKey.has(key)) {
        discrepancies.push({ key, reason: "MISSING_IN_SQL", filesystemHash });
        continue;
      }
      const sqlHash = computeCanonicalHash(sqlByKey.get(key));
      if (sqlHash !== filesystemHash) discrepancies.push({ key, reason: "HASH_MISMATCH", filesystemHash, sqlHash });
    }
    for (const [key, aggregate] of sqlByKey) {
      if (!filesystemByKey.has(key))
        discrepancies.push({ key, reason: "MISSING_IN_FILESYSTEM", sqlHash: computeCanonicalHash(aggregate) });
    }
    if (filesystem.length !== sql.length && discrepancies.length === 0)
      discrepancies.push({
        key: plan.context,
        reason: "COUNT_MISMATCH",
        filesystemHash: `count:${filesystem.length}`,
        sqlHash: `count:${sql.length}`
      });
    const contextOk = filesystem.length === sql.length && discrepancies.length === 0;
    contexts[plan.context] = {
      context: plan.context,
      filesystemCount: filesystem.length,
      sqlCount: sql.length,
      ok: contextOk,
      discrepancies
    };
    totalFilesystemAggregates += filesystem.length;
    totalSqlAggregates += sql.length;
    if (!contextOk) ok = false;
  }
  return { ok, contexts, totalFilesystemAggregates, totalSqlAggregates };
}
function hashVerificationReport(report) {
  return createHash16("sha256").update(JSON.stringify(report)).digest("hex");
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
import { createHash as createHash17, randomUUID as randomUUID9 } from "node:crypto";
import { mkdir as mkdir4, open as open4, readFile as readFile6, rename as rename4, rm as rm4, stat } from "node:fs/promises";
import { isAbsolute as isAbsolute4, join as join9, relative as relative2, resolve as resolve2 } from "node:path";
var MAX_INLINE_ARTIFACT_BYTES = 256 * 1024;
var STRUCTURED_RESULT_FINALIZATION_BRIEF = "Do no new analysis or work. Perform no reads, writes, delegation, queryUser, or oracle calls. Using only the work already completed in this case, call FACTORY__submit_step_result exactly once. Your normal assistant message is non-authoritative.";
var diagnostic = (value, fallback) => String(value ?? fallback).replace(/[\r\n\t]+/g, " ").replace(/\s+/g, " ").slice(0, 1e3);
var safeSegment = (value) => typeof value === "string" && /^[A-Za-z0-9._-]+$/.test(value) && value !== "." && value !== "..";
var inside = (root, target) => {
  const rel = relative2(root, target);
  return rel === "" || !rel.startsWith("..") && !isAbsolute4(rel);
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
var artifactEvidenceIdempotencyKey = (attemptId, artifactPath) => `${attemptId}:artifact:${createHash17("sha256").update(String(artifactPath).split("\\").join("/")).digest("hex")}`;
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
    if (isAbsolute4(artifact2.path)) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    const absolute = resolve2(repoRoot, artifact2.path);
    if (relative2(repoRoot, absolute).startsWith("..")) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    try {
      if (!(await stat(absolute)).isFile()) return { ok: false, code: "ARTIFACT_NOT_FILE" };
      const content = await readFile6(absolute);
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
  await mkdir4(directory, { recursive: true });
  const artifactPath = relative2(root, finalPath).split("\\").join("/");
  const verified = (persisted) => persisted.equals(content) ? { ok: true, artifacts: [{ kind: expectedKind, path: artifactPath, hash: sha256(persisted) }] } : { ok: false, code: "ARTIFACT_SEMANTIC_COLLISION" };
  try {
    return verified(await readFile6(finalPath));
  } catch (error2) {
    if (error2?.code !== "ENOENT")
      return { ok: false, code: "ARTIFACT_MATERIALIZATION_FAILED" };
  }
  const temporary = join9(directory, `.${stepId}.${randomUUID9()}.tmp`);
  let handle = null;
  try {
    handle = await open4(temporary, "wx", 384);
    await handle.writeFile(content);
    await handle.sync();
    await handle.close();
    handle = null;
    try {
      await rename4(temporary, finalPath);
    } catch (error2) {
      if (error2?.code !== "EEXIST") throw error2;
      return verified(await readFile6(finalPath));
    }
    const persisted = await readFile6(finalPath);
    if (!persisted.equals(content)) return { ok: false, code: "ARTIFACT_WRITE_MISMATCH" };
    return verified(persisted);
  } catch {
    return { ok: false, code: "ARTIFACT_MATERIALIZATION_FAILED" };
  } finally {
    if (handle) await handle.close().catch(() => {
    });
    await rm4(temporary, { force: true }).catch(() => {
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
  const attemptId = randomUUID9();
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

// ../src/application/oracle/oracle-command.ts
import { existsSync, readFileSync } from "node:fs";
import { dirname as dirname5, join as join10 } from "node:path";
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
      join10(repoRoot, "apps", host, "project.json"),
      join10(repoRoot, "frontend", "apps", host, "project.json"),
      join10(repoRoot, host, "project.json")
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
    const absoluteFile = join10(repoRoot, file);
    let dir = dirname5(absoluteFile);
    while (dir.length >= repoRoot.length) {
      const candidate = join10(dir, "project.json");
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
      const parent = dirname5(dir);
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
import { createHash as createHash18 } from "node:crypto";
import { readFileSync as readFileSync2 } from "node:fs";
import { realpath as realpath2 } from "node:fs/promises";
import { isAbsolute as isAbsolute5, join as join11 } from "node:path";
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
  const errorCode2 = result.error?.code;
  const timedOut = result.signal === "SIGTERM" || errorCode2 === "ETIMEDOUT";
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
    return createHash18("sha256").update(readFileSync2(join11(cwd, relPath))).digest("hex");
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
  if (typeof repoRoot !== "string" || !isAbsolute5(repoRoot))
    throw Object.assign(new Error("INVALID_ORACLE_ROOT"), { code: "INVALID_ORACLE_ROOT" });
  return realpath2(repoRoot);
}
function oracleRootIdentity(repoRoot) {
  return `sha256:${createHash18("sha256").update(repoRoot).digest("hex")}`;
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
  return { raw, hash: `sha256:${createHash18("sha256").update(raw).digest("hex")}` };
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

// ../src/application/environment/work-unit-environment-service.ts
import { randomUUID as randomUUID10 } from "node:crypto";
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
    idGenerator = () => randomUUID10(),
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
var UUID4 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE8 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var ALLOWED2 = /* @__PURE__ */ new Set(["workflowId", "workUnitId", "integrationBranch", "branch"]);
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
    if (!UUID4.test(namespaceId ?? "") || !UUID4.test(caseId ?? "") || !SAFE8.test(createdBy ?? ""))
      return { ok: false, status: 400, error: { code: "INVALID_TRUST_CONTEXT" } };
    const requestBody = body ?? {};
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(requestBody).some((key) => !ALLOWED2.has(key)) || !SAFE8.test(requestBody.workflowId ?? "") || !SAFE8.test(requestBody.workUnitId ?? ""))
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
    if (!UUID4.test(namespaceId ?? "") || !SAFE8.test(workflowId ?? ""))
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

// ../src/adapters/persistence/delivery-evidence-store.ts
import { createHash as createHash19, randomUUID as randomUUID11 } from "node:crypto";
import { appendFile as appendFile4, mkdir as mkdir5, open as open5, readFile as readFile7 } from "node:fs/promises";
import { dirname as dirname6, join as join12 } from "node:path";
var SAFE9 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var UUID5 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var HASH4 = /^sha256:[0-9a-f]{64}$/;
var SHA5 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
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
var canonical5 = (value) => Array.isArray(value) ? value.map(canonical5) : value && typeof value === "object" ? Object.fromEntries(
  Object.keys(value).sort().map((key) => [key, canonical5(value[key])])
) : value;
var digest3 = (value) => createHash19("sha256").update(JSON.stringify(canonical5(value))).digest("hex");
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
  ].every((value) => SAFE9.test(value ?? "")) || !HASH4.test(candidate.environmentHash ?? "") || !UUID5.test(candidate.caseId ?? "") || !SHA5.test(candidate.headCommit ?? ""))
    return { ok: false, error: { code: "INVALID_DELIVERY_EVIDENCE" } };
  if (!candidate.facts || typeof candidate.facts !== "object" || Array.isArray(candidate.facts) || Object.keys(candidate.facts).length > 32 || JSON.stringify(candidate.facts).length > 4096)
    return { ok: false, error: { code: "INVALID_DELIVERY_EVIDENCE" } };
  return { ok: true, value: canonical5(candidate) };
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
    if (!UUID5.test(namespaceId ?? ""))
      throw Object.assign(new Error("INVALID_NAMESPACE_ID"), { code: "INVALID_NAMESPACE_ID" });
    if (!SAFE9.test(deliveryId ?? ""))
      throw Object.assign(new Error("INVALID_DELIVERY_ID"), { code: "INVALID_DELIVERY_ID" });
    return join12(
      this.dataRoot,
      "deliveries",
      namespaceId,
      createHash19("sha256").update(`${namespaceId}:${deliveryId}`).digest("hex"),
      "evidence.jsonl"
    );
  }
  async list(namespaceId, deliveryId) {
    try {
      return (await readFile7(this.path(namespaceId, deliveryId), "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
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
      }), semanticHash2 = digest3({ ...value, idempotencyKey: void 0 });
      const prior = existing.find((item) => item.idempotency.scopeHash === scopeHash);
      if (prior)
        return prior.idempotency.semanticHash === semanticHash2 ? { ok: true, created: false, evidence: prior } : { ok: false, error: { code: "IDEMPOTENCY_KEY_COLLISION" } };
      const evidence = {
        evidenceId: randomUUID11(),
        namespaceId,
        ...value,
        source: { ...source },
        observedAt: (/* @__PURE__ */ new Date()).toISOString(),
        idempotency: { scopeHash, semanticHash: semanticHash2 }
      };
      await append3(this.path(namespaceId, value.deliveryId), evidence);
      return { ok: true, created: true, evidence };
    });
  }
};

// ../src/adapters/delivery/delivery-target-registry.ts
var SAFE10 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
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
      if (!raw || Object.keys(raw).some((k) => !FIELDS4.includes(k)) || !SAFE10.test(raw.targetId ?? "") || !["development", "staging", "production"].includes(raw.environmentKind) || !SAFE10.test(raw.adapterId ?? "") || !SAFE10.test(raw.adapterTargetRef ?? "") || typeof raw.supportsRollback !== "boolean" || raw.verificationSuiteId !== void 0 && !SAFE10.test(raw.verificationSuiteId) || raw.verificationSuiteHash !== void 0 && !DIGEST2.test(raw.verificationSuiteHash))
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
    if (!SAFE10.test(targetId ?? "")) return { ok: false, error: { code: "DELIVERY_TARGET_NOT_FOUND" } };
    const target = this.#targets.get(targetId);
    return target ? { ok: true, target } : { ok: false, error: { code: "DELIVERY_TARGET_NOT_FOUND" } };
  }
};
var unavailableDeliveryTargetRegistry = Object.freeze({ lookup: unavailable });

// ../src/adapters/delivery/delivery-git-control-plane.ts
import { execFile } from "node:child_process";
import { createHash as createHash20 } from "node:crypto";
import { realpath as realpath3 } from "node:fs/promises";
import { isAbsolute as isAbsolute6 } from "node:path";
import { promisify } from "node:util";
var execute = promisify(execFile);
var SHA6 = /^(?:[0-9a-f]{40}|[0-9a-f]{64})$/i;
var SAFE_REMOTE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var SAFE_BRANCH = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,255}$/;
var fail6 = (code, details = {}) => {
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
    if (!serviceIdentity?.name || !serviceIdentity?.email) fail6("SERVICE_IDENTITY_NOT_CONFIGURED");
    if (configuredRemote !== null && configuredRemote !== void 0 && !SAFE_REMOTE.test(configuredRemote))
      fail6("INVALID_REMOTE_CONFIGURATION");
    if (![...allowedPaths, ...protectedPaths].every(safePath)) fail6("INVALID_PATH_POLICY");
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
    const canonical6 = await realpath3(binding.worktreePath).catch(() => fail6("CANONICAL_WORKTREE_REQUIRED"));
    if (canonical6 !== binding.worktreePath) fail6("CANONICAL_WORKTREE_REQUIRED");
    const top = await this._run(["rev-parse", "--show-toplevel"], canonical6);
    if (top.exitCode || await realpath3(top.stdout.trim()).catch(() => null) !== canonical6)
      fail6("CANONICAL_WORKTREE_REQUIRED");
    if (!SAFE_BRANCH.test(binding.branch ?? "")) fail6("INVALID_BRANCH_NAME");
    const branch = await this._run(["branch", "--show-current"], canonical6), head = await this._run(["rev-parse", "HEAD"], canonical6);
    if (branch.exitCode || branch.stdout.trim() !== binding.branch || head.exitCode || !SHA6.test(head.stdout.trim()))
      fail6("WORKTREE_BINDING_UNCERTAIN");
    if (head.stdout.trim() !== binding.expectedHead)
      fail6("STALE_HEAD", { expected: binding.expectedHead, actual: head.stdout.trim() });
    const status = await this._run(["status", "--porcelain=v1", "-z", "--untracked-files=all"], canonical6);
    if (status.exitCode) fail6("GIT_INSPECTION_FAILED");
    const files = parseStatusZ(status.stdout);
    const protectedHit = files.find(
      (item) => this.protectedPaths.some(
        (prefix) => matchesPrefix(item.path, prefix) || item.originalPath && matchesPrefix(item.originalPath, prefix)
      )
    );
    if (protectedHit) fail6("PROTECTED_FILE_CHANGED", { path: protectedHit.path });
    const outOfScope = files.find(
      (item) => !this.allowedPaths.some((prefix) => matchesPrefix(item.path, prefix)) || item.originalPath && !this.allowedPaths.some((prefix) => matchesPrefix(item.originalPath, prefix))
    );
    if (outOfScope) fail6("SCOPE_VIOLATION", { path: outOfScope.path });
    const allPaths = [
      ...new Set(files.flatMap((item) => item.originalPath ? [item.path, item.originalPath] : [item.path]))
    ].sort();
    const trackedDiff = await this._run(
      ["diff", "--binary", "--no-ext-diff", binding.baseCommit, "--", ...allPaths],
      canonical6
    );
    if (trackedDiff.exitCode) fail6("GIT_INSPECTION_FAILED");
    const untracked = files.filter((item) => item.code === "??").map((item) => item.path).sort();
    const diffContent = `${trackedDiff.stdout}
${untracked.map((path) => `untracked ${path}`).join("\n")}`;
    return {
      worktreePath: canonical6,
      branch: binding.branch,
      headCommit: head.stdout.trim(),
      files,
      diffHash: `sha256:${createHash20("sha256").update(diffContent).digest("hex")}`
    };
  }
  compareClaims(inspection, claims) {
    const candidate = claims;
    if (!candidate) fail6("INVALID_CLAIMS");
    if (Object.keys(candidate).some((key) => !["paths", "diffHash"].includes(key)) || !Array.isArray(candidate.paths))
      fail6("INVALID_CLAIMS");
    const trusted = candidate;
    const actual = [...new Set(inspection.files.map((item) => item.path))].sort(), claimed = [...new Set(trusted.paths)].sort();
    if (JSON.stringify(actual) !== JSON.stringify(claimed) || trusted.diffHash !== inspection.diffHash)
      fail6("CLAIMS_MISMATCH");
    return { ok: true };
  }
  async checkpoint(binding, { message, claims }) {
    const inspection = await this.inspect(binding);
    this.compareClaims(inspection, claims);
    if (inspection.files.length === 0) return { changed: false, commit: inspection.headCommit, inspection };
    const paths = inspection.files.map((item) => item.path);
    const add = await this._run(["add", "--", ...paths], inspection.worktreePath);
    if (add.exitCode) fail6("GIT_STAGE_FAILED");
    const staged = await this._run(["diff", "--cached", "--quiet", "--exit-code"], inspection.worktreePath);
    if (staged.exitCode === 0) return { changed: false, commit: inspection.headCommit, inspection };
    if (staged.exitCode !== 1) fail6("GIT_STAGE_INDETERMINATE");
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
    if (commit.exitCode) fail6("GIT_COMMIT_FAILED");
    const head = await this._run(["rev-parse", "HEAD"], inspection.worktreePath), identity = await this._run(["show", "-s", "--format=%cn%n%ce", "HEAD"], inspection.worktreePath);
    if (head.exitCode || !SHA6.test(head.stdout.trim()) || identity.stdout.trim() !== `${this.identity.name}
${this.identity.email}`)
      fail6("GIT_COMMIT_INDETERMINATE");
    return { changed: true, commit: head.stdout.trim(), previousHead: inspection.headCommit, inspection };
  }
  async push(binding) {
    if (!this.remote) return { ok: false, blocked: true, error: { code: "REMOTE_NOT_CONFIGURED" } };
    const inspection = await this.inspect(binding), remoteHeadBefore = await this._run(
      ["ls-remote", "--heads", this.remote, `refs/heads/${binding.branch}`],
      inspection.worktreePath
    );
    if (remoteHeadBefore.exitCode) fail6("REMOTE_INSPECTION_FAILED");
    const previous = remoteHeadBefore.stdout.trim().split(/\s+/)[0] || null;
    if (previous === inspection.headCommit) return { ok: true, changed: false, headCommit: inspection.headCommit };
    const push = await this._run(
      ["push", "--porcelain", this.remote, `refs/heads/${binding.branch}:refs/heads/${binding.branch}`],
      inspection.worktreePath
    );
    if (push.exitCode) fail6("GIT_PUSH_FAILED");
    const remoteHeadAfter = await this._run(
      ["ls-remote", "--heads", this.remote, `refs/heads/${binding.branch}`],
      inspection.worktreePath
    ), actual = remoteHeadAfter.stdout.trim().split(/\s+/)[0];
    if (remoteHeadAfter.exitCode || actual !== inspection.headCommit) fail6("GIT_PUSH_INDETERMINATE");
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
var SAFE11 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
var DELIVERY_ADAPTER_OUTCOMES = Object.freeze(["running", "succeeded", "failed", "indeterminate"]);
function normalizeDeliveryAdapterOutcome(value) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.keys(value).some((k) => !["state", "correlationRef", "resultRef", "errorCode", "observedAt"].includes(k)) || !DELIVERY_ADAPTER_OUTCOMES.includes(value.state))
    return { ok: false, error: { code: "INVALID_DELIVERY_ADAPTER_OUTCOME" } };
  const candidate = value;
  for (const key of ["correlationRef", "resultRef", "errorCode"])
    if (candidate[key] !== void 0 && !SAFE11.test(candidate[key]))
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
var UUID6 = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
var SAFE12 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
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
    if (!identity || !UUID6.test(identity.namespaceId ?? "") || !UUID6.test(identity.caseId ?? "") || !SAFE12.test(workflowId ?? ""))
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
var SAFE13 = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
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
    if (!exact2(body, fields) || !SAFE13.test(body.idempotencyKey ?? "") || !SAFE13.test(body.targetId ?? "") || !REASON.test(body.reasonCode ?? "") || body.reason !== void 0 && (typeof body.reason !== "string" || body.reason.length < 1 || body.reason.length > 512))
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
    const semanticHash2 = canonicalDeliveryHash({
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
        semanticHash: semanticHash2
      },
      execution: execution(identity, workflowId)
    });
    return result.ok ? { ok: true, status: result.changed ? 201 : 200, data: result.request } : response(result.error);
  }
  async approveRollback(identity, workflowId, requestIdValue, body) {
    if (!exact2(body, ["expectedRevision", "idempotencyKey"]) || !SAFE13.test(requestIdValue ?? "") || !SAFE13.test(body.idempotencyKey ?? ""))
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
    if (!exact2(body, ["expectedRevision", "idempotencyKey"]) || !SAFE13.test(requestIdValue ?? ""))
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
    if (!exact2(body, ["expectedRevision", "idempotencyKey", "rollbackRef", "targetId"]) || !SAFE13.test(requestIdValue ?? ""))
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
import { isAbsolute as isAbsolute7, join as join13, relative as relative3 } from "node:path";
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
  return join13(repoRoot, "forge", "factory-runs");
}

// ../src/domain/forge-bmad/forge-human-decision.ts
import { createHash as createHash21 } from "node:crypto";
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
  return `sha256:${createHash21("sha256").update(canonicalG1({ policyVersion, evidence })).digest("hex")}`;
}

// ../src/domain/forge-bmad/forge-spec.ts
import { createHash as createHash22 } from "node:crypto";
var FORGE_SPEC_SCHEMA_VERSION = 1;
var G2_POLICY_VERSION = "forge-g2-deterministic-v1";
var ORACLE_CATALOG = /* @__PURE__ */ new Set(["front.build", "front.tests", "back.build"]);
function fail7(code) {
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
      if (!match2) fail7("G2_FRONTMATTER_INVALID");
      const [, key, value] = match2;
      if (Object.hasOwn(out, key)) fail7("G2_FRONTMATTER_INVALID");
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
    fail7("G2_FRONTMATTER_INVALID");
  }
  return out;
}
function validatePattern(pattern) {
  if (typeof pattern !== "string" || !pattern || pattern.includes("\\") || pattern.startsWith("/") || pattern.includes("..") || pattern.includes("//"))
    fail7("G2_SCOPE_PATTERN_INVALID");
  const parts = pattern.split("/");
  if (parts.some((part) => !part || part !== "*" && part !== "**" && !/^[A-Za-z0-9._@-]+$/.test(part)))
    fail7("G2_SCOPE_PATTERN_INVALID");
  if (parts.includes("**") && parts.at(-1) !== "**") fail7("G2_SCOPE_PATTERN_INVALID");
}
function validateForgeSpecSchema(data, workItem) {
  if (data.schemaVersion !== FORGE_SPEC_SCHEMA_VERSION) fail7("G2_SPEC_SCHEMA_UNSUPPORTED");
  if (!data.workItem || data.workItem.id !== workItem.id || data.workItem.kind !== workItem.kind)
    fail7("G2_WORK_ITEM_MISMATCH");
  if (!data.scope || typeof data.scope !== "object") fail7("G2_SCOPE_INVALID");
  for (const key of ["allow", "create", "deny"]) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail7("G2_SCOPE_INVALID");
    data.scope[key].forEach(validatePattern);
  }
  if (!Array.isArray(data.oracles) || data.oracles.some((oracle) => typeof oracle !== "string" || !ORACLE_CATALOG.has(oracle)))
    fail7("G2_ORACLE_UNKNOWN");
  if (Object.keys(data).some((key) => !["schemaVersion", "workItem", "scope", "oracles"].includes(key)))
    fail7("G2_FRONTMATTER_INVALID");
}
function computeForgeSpecHash(content) {
  return `sha256:${createHash22("sha256").update(content).digest("hex")}`;
}
var FORGE_SPEC_FRONTMATTER_PATTERN = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/;

// ../src/domain/forge-bmad/forge-story-spec.ts
import { createHash as createHash23 } from "node:crypto";
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
function fail8(code, detail) {
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
      if (!match2) fail8("G2_FRONTMATTER_INVALID");
      const [, key, value] = match2;
      if (Object.hasOwn(out, key)) fail8("G2_FRONTMATTER_INVALID");
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
    fail8("G2_FRONTMATTER_INVALID");
  }
  return out;
}
function validateStorySpec(data) {
  if (data.schemaVersion !== FORGE_STORY_SPEC_SCHEMA_VERSION) fail8("G2_US_SPEC_SCHEMA_UNSUPPORTED");
  for (const key of Object.keys(data)) {
    if (!STORY_SPEC_ALLOWED_KEYS.has(key)) fail8("G2_FRONTMATTER_INVALID", `unexpected key: ${key}`);
  }
  if (!data.workItem || typeof data.workItem !== "object") fail8("G2_US_WORK_ITEM_KIND_INVALID");
  if (data.workItem.kind !== "Story") fail8("G2_US_WORK_ITEM_KIND_INVALID");
  if (typeof data.workItem.id !== "string" || !data.workItem.id) fail8("G2_FRONTMATTER_INVALID");
  if (typeof data.workItem.parentId !== "string" || !data.workItem.parentId) fail8("G2_US_PARENT_ID_MISSING");
  if (!data.scope || typeof data.scope !== "object") fail8("G2_SCOPE_INVALID");
  for (const key of ["allow", "create", "deny"]) {
    if (!Array.isArray(data.scope[key]) || data.scope[key].length === 0) fail8("G2_SCOPE_INVALID");
  }
  if (data.oracles !== void 0 && !Array.isArray(data.oracles)) fail8("G2_FRONTMATTER_INVALID");
  for (const key of ["acceptanceCriteria", "impacts"]) {
    if (data[key] !== void 0) {
      if (!Array.isArray(data[key])) fail8("G2_FRONTMATTER_INVALID");
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
  return `sha256:${createHash23("sha256").update(content).digest("hex")}`;
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
import { createHash as createHash24 } from "node:crypto";
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
var SAFE_ID10 = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,127})$/;
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
  if (safe2 && !SAFE_ID10.test(value)) return failure2(WORKFLOW_PROJECTION_ERROR_CODES.INVALID_ID, path);
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
import { basename, dirname as dirname7, isAbsolute as isAbsolute8, join as join14, resolve as resolve3 } from "node:path";
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
  const candidate = join14(parent, basename(requested));
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
import { isAbsolute as isAbsolute9, join as join15 } from "node:path";
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
  const yamlPath = join15(repoRoot, "forge", "state", "forge-runs", `${ticketId}.yaml`);
  const raw = readYamlFile(yamlPath);
  if (!raw) return null;
  return normalizeForgeRunYaml(raw, ticketId);
}
function readForgeRunYamlStrict(repoRoot, ticketId) {
  const yamlPath = join15(repoRoot, "forge", "state", "forge-runs", `${ticketId}.yaml`);
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
  const fullPath = isAbsolute9(storePath) ? storePath : join15(repoRoot, storePath);
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
  const yamlPath = join15(
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
function fail9(code) {
  const error2 = new Error(code);
  error2.code = code;
  throw error2;
}
function loadForgeSpec({
  specPath,
  roots,
  workItem
}) {
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail9("G2_SPEC_PATH_INVALID");
  let path;
  try {
    path = realpathSync3(resolve4(specPath));
    if (!statSync2(path).isFile()) fail9("G2_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail9("G2_SPEC_PATH_INVALID");
  }
  if (!inside2(path, roots.repoRoot) && !(roots.forgeRoot && inside2(path, roots.forgeRoot)))
    fail9("G2_SPEC_OUTSIDE_ROOT");
  const content = readFileSync4(path, "utf8");
  const match2 = content.match(FORGE_SPEC_FRONTMATTER_PATTERN);
  if (!match2) fail9("G2_FRONTMATTER_MISSING");
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
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail9("G2_US_SPEC_PATH_INVALID");
  let realPath;
  try {
    realPath = realpathSync3(resolve4(specPath));
    if (!statSync2(realPath).isFile()) fail9("G2_US_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail9("G2_US_SPEC_PATH_INVALID");
  }
  if (!inside2(realPath, roots.repoRoot) && !(roots.forgeRoot && inside2(realPath, roots.forgeRoot)))
    fail9("G2_US_SPEC_OUTSIDE_ROOT");
  const rawContent = readFileSync4(realPath, "utf8");
  const match2 = rawContent.match(FORGE_SPEC_FRONTMATTER_PATTERN);
  if (!match2) fail9("G2_FRONTMATTER_MISSING");
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
  if (typeof specPath !== "string" || !isAbsolute10(specPath)) fail9("G2_US_SPEC_PATH_INVALID");
  let realPath;
  try {
    realPath = realpathSync3(resolve4(specPath));
    if (!statSync2(realPath).isFile()) fail9("G2_US_SPEC_PATH_INVALID");
  } catch (error2) {
    if (error2.code?.startsWith("G2_")) throw error2;
    fail9("G2_US_SPEC_PATH_INVALID");
  }
  const content = readFileSync4(realPath, "utf8");
  return computeStorySpecHash(content);
}

// ../src/adapters/forge/forge-ledger-store.ts
import { appendFileSync as appendFileSync2, readdirSync, readFileSync as readFileSync5 } from "node:fs";
import { join as join16 } from "node:path";
import { randomUUID as randomUUID12 } from "node:crypto";
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
  runId = `epic_${randomUUID12()}`,
  now = () => (/* @__PURE__ */ new Date()).toISOString()
}) {
  assertWorkItem(epic, "epic");
  if (!Array.isArray(stories) || stories.length === 0)
    throw new Error("stories must contain at least one explicit Story work item");
  for (const story of stories) {
    assertWorkItem(story, "story");
    if (story.kind !== "Story") throw new Error('every child work item must have kind "Story"');
  }
  const filePath = join16(ensureForgeRunStore(roots), `${runId}.jsonl`);
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
    const storyRunId = `story_${randomUUID12()}`;
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
      const projection = projectForgeRun(parseForgeLedger(join16(runStoreRoot, file)));
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
import { randomUUID as randomUUID13 } from "node:crypto";
import { join as join17 } from "node:path";
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
  const filePath = join17(ensureForgeRunStore(roots), `${runId}.jsonl`);
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
    decisionId: `decision_${randomUUID13()}`,
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
import { join as join18 } from "node:path";
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
  const filePath = join18(ensureForgeRunStore(roots), `${runId}.jsonl`);
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
  const filePath = join18(ensureForgeRunStore(roots), `${epicRunId}.jsonl`);
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
import { createHash as createHash25, randomUUID as randomUUID14 } from "node:crypto";
import { join as join20, relative as relative5, resolve as resolve5 } from "node:path";

// ../lib/plan.mjs
import { existsSync as existsSync4 } from "node:fs";
import { join as join19, isAbsolute as isAbsolute11 } from "node:path";
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
  const missingFiles = files.filter((f) => !existsSync4(join19(repoRoot, f)));
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
  return `sha256:${createHash25("sha256").update(content).digest("hex")}`;
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
  const temporary = resolve5(dir, `.${executionId}.${randomUUID14()}.tmp`);
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
  const filePath = join20(store, `${epicRunId}.jsonl`);
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
  const executionId = `exec_${randomUUID14()}`;
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
import { createHash as createHash26, randomUUID as randomUUID15 } from "node:crypto";
import { join as join21, resolve as resolve6 } from "node:path";
var STORY_EDIT_SCHEMA_VERSION = 1;
var STORY_EDIT_POLICY_VERSION = "forge-story-edit-v1";
var fail10 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var hash4 = (value) => `sha256:${createHash26("sha256").update(value).digest("hex")}`;
var safeArtifact = (store, descriptor) => {
  if (!descriptor?.path || !descriptor?.sha256)
    fail10("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID", "Analysis artifact descriptor requires path and sha256.");
  const root = resolve6(store);
  const path = resolve6(root, descriptor.path);
  if (!path.startsWith(`${root}/`))
    fail10("STORY_EDIT_ANALYSIS_ARTIFACT_PATH_INVALID", "Analysis artifact path escapes the run store.");
  if (!existsSync5(path)) fail10("STORY_EDIT_ANALYSIS_ARTIFACT_INVALID", "Analysis artifact does not exist.");
  const text2 = readFileSync6(path, "utf8");
  if (hash4(text2) !== descriptor.sha256)
    fail10("STORY_EDIT_ANALYSIS_ARTIFACT_HASH_MISMATCH", "Analysis artifact content does not match its SHA-256.");
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
    fail10("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact must contain exactly one JSON plan.");
  let raw;
  try {
    raw = JSON.parse(blocks[0]);
  } catch {
    fail10("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact JSON plan is invalid.");
  }
  if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((k) => !["files", "doneWhen", "steps"].includes(k)))
    fail10("STORY_EDIT_ANALYSIS_PLAN_INVALID", "Analysis artifact plan schema is invalid.");
  const parsed = parsePlan(`\`\`\`json
${blocks[0]}
\`\`\``);
  if (!parsed.ok) fail10("STORY_EDIT_ANALYSIS_PLAN_INVALID", parsed.error);
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
  if (!namespaceId || !agentName) fail10("STORY_EDIT_INPUT_INVALID", "namespaceId and agentName are required.");
  if (supplement !== void 0 && (typeof supplement !== "string" || supplement.length > 4e3))
    fail10("STORY_EDIT_SUPPLEMENT_INVALID", "supplement must be a string of at most 4000 characters.");
  const store = ensureForgeRunStore(roots);
  const filePath = join21(store, `${epicRunId}.jsonl`);
  if (!existsSync5(filePath)) fail10("STORY_EDIT_RUN_NOT_FOUND", `Epic run ${epicRunId} has no ledger.`);
  const events = parseForgeLedger(filePath);
  const epic = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  if (!epic) fail10("STORY_EDIT_RUN_NOT_FOUND", `Epic run ${epicRunId} is absent from its ledger.`);
  const story = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!story) fail10("STORY_EDIT_STORY_NOT_FOUND", `Story run ${storyRunId} is absent from Epic run ${epicRunId}.`);
  if (events.some(
    (e) => e.event === "story_edit_started" && e.storyRunId === storyRunId && !events.some((f) => f.event === "story_edit_finished" && f.editId === e.editId)
  ))
    fail10("STORY_EDIT_ALREADY_RUNNING", "A Story edit is already active.");
  const g1Event = events.find((e) => e.event === "human_decision_recorded" && e.runId === epicRunId && e.gate === "G1");
  if (g1Event?.decision?.outcome !== "approved") fail10("STORY_EDIT_G1_NOT_APPROVED");
  const g22 = events.filter((e) => e.event === "g2_evaluated" && e.runId === epicRunId && e.status === "passed").at(-1);
  if (!g22 || g22.spec?.sha256 !== expectedSpecHash) fail10("STORY_EDIT_G2_NOT_PASSED");
  if (storySpecHash !== void 0) {
    const g2us = events.find(
      (e) => e.event === "g2_us_evaluated" && e.storyRunId === storyRunId && e.status === "passed" && e.storySpec?.sha256 === storySpecHash
    );
    if (!g2us) fail10("STORY_EDIT_G2_US_NOT_PASSED");
  }
  const analysis = events.find(
    (e) => e.event === "agent_execution_finished" && e.executionId === analysisExecutionId && e.storyRunId === storyRunId && e.status === "finished"
  );
  const validation = events.find(
    (e) => e.event === "story_analysis_plan_validated" && e.executionId === analysisExecutionId && e.status === "valid"
  );
  if (!analysis || !validation) fail10("STORY_EDIT_ANALYSIS_NOT_VALID");
  const text2 = safeArtifact(store, analysis.artifact);
  if (validation.artifact?.sha256 !== analysis.artifact?.sha256 || validation.artifact?.path !== analysis.artifact?.path)
    fail10("STORY_EDIT_ANALYSIS_PLAN_STALE", "Analysis validation does not reference the finished artifact.");
  const plan = planFromArtifact(text2);
  const missing = checkPlanFiles(plan.files, roots.repoRoot).missingFiles;
  if (missing.length) fail10("STORY_EDIT_ANALYSIS_PLAN_STALE", `Analysis plan files are missing: ${missing.join(", ")}.`);
  const spec = loadForgeSpec({
    specPath: g22.spec.path,
    roots,
    workItem: epic.workItem
  });
  if (spec.sha256 !== g22.spec.sha256) fail10("STORY_EDIT_SPEC_HASH_STALE");
  const agent = await runtime.preflightAgent(namespaceId, agentName);
  if (!agent.ok) fail10("STORY_EDIT_AGENT_PREFLIGHT_FAILED");
  const writable = await runtime.preflightWritableWorkspace(namespaceId, agent.agent, roots.repoRoot);
  if (!writable.ok) fail10("STORY_EDIT_WRITABLE_PREFLIGHT_FAILED", writable.reason);
  const editId = `edit_${randomUUID15()}`;
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
import { createHash as createHash28, randomUUID as randomUUID16 } from "node:crypto";
import { join as join24 } from "node:path";

// ../lib/domains.mjs
import { join as join22, dirname as dirname8, resolve as resolve7 } from "node:path";
import { fileURLToPath as fileURLToPath2 } from "node:url";
var __dirname = dirname8(fileURLToPath2(import.meta.url));
var REPO_ROOT = process.env.FACTORY_ROOT ? resolve7(process.env.FACTORY_ROOT) : join22(__dirname, "..", "..");
var domains = {
  back: {
    oracles: [
      {
        name: "build",
        command: process.env.FACTORY_COMMAND_BACK ?? "./gradlew :agentos-service:build --rerun-tasks --console=plain",
        cwd: process.env.FACTORY_CWD_BACK ?? join22(REPO_ROOT, "agentos")
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
import { createHash as createHash27 } from "node:crypto";
import { dirname as dirname9, isAbsolute as isAbsolute12, join as join23, relative as relative6, resolve as resolve8 } from "node:path";
var FRONT_ORACLE_MAP_SCHEMA_VERSION = 1;
var INSPECT_TIMEOUT_MS = 1e4;
var INSPECT_MAX_BUFFER = 1024 * 1024;
var fail11 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var hash5 = (value) => `sha256:${createHash27("sha256").update(JSON.stringify(value)).digest("hex")}`;
var validName = (name) => typeof name === "string" && /^[A-Za-z0-9._-]+$/.test(name);
var readProject = (path, label) => {
  try {
    const config = JSON.parse(readFileSync7(path, "utf8"));
    if (!validName(config.name)) fail11("ORACLE_INFRASTRUCTURE", `${label} has an absent or invalid Nx project name.`);
    return config;
  } catch (error2) {
    if (error2.code === "ORACLE_INFRASTRUCTURE") throw error2;
    fail11("ORACLE_INFRASTRUCTURE", `Cannot read ${label}.`);
  }
};
var hostProject = (root, name) => {
  for (const path of [
    join23(root, "apps", name, "project.json"),
    join23(root, "frontend", "apps", name, "project.json"),
    join23(root, name, "project.json")
  ])
    if (existsSync6(path)) return readProject(path, `Build host project.json for ${name}`);
  return null;
};
function resolveOwnerProjectConfigs(files, repoRoot) {
  const root = resolve8(repoRoot);
  const byName = /* @__PURE__ */ new Map();
  for (const file of files) {
    if (typeof file !== "string" || !file || isAbsolute12(file))
      fail11("ORACLE_INFRASTRUCTURE", `Invalid StoryEdit file path: ${String(file)}.`);
    const absolute = resolve8(root, file);
    if (relative6(root, absolute).startsWith(".."))
      fail11("ORACLE_INFRASTRUCTURE", `StoryEdit file escapes repository root: ${file}.`);
    let dir = dirname9(absolute);
    let found = false;
    while (dir === root || dir.startsWith(`${root}/`)) {
      const projectPath = join23(dir, "project.json");
      if (existsSync6(projectPath)) {
        const config = readProject(projectPath, `Owner project.json for ${file}`);
        const previous = byName.get(config.name);
        if (previous && previous.projectPath !== projectPath)
          fail11("ORACLE_INFRASTRUCTURE", `Nx owner ${config.name} resolves to multiple project.json files.`);
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
  if (!validName(name)) fail11("ORACLE_INFRASTRUCTURE", `Invalid Nx project name for inspection: ${String(name)}.`);
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
    fail11("ORACLE_INFRASTRUCTURE", `Cannot inspect effective Nx configuration for ${name}.`);
  }
  let config;
  try {
    config = JSON.parse(output);
  } catch {
    fail11("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is not valid JSON.`);
  }
  if (config?.name !== name || !config.targets || typeof config.targets !== "object" || Array.isArray(config.targets))
    fail11("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is invalid or mismatched.`);
  return config;
}
var inspectEffectiveProject = (name, repoRoot, projectInspector) => {
  let config;
  try {
    config = projectInspector(name, repoRoot);
  } catch (error2) {
    if (error2?.code === "ORACLE_INFRASTRUCTURE") throw error2;
    fail11("ORACLE_INFRASTRUCTURE", `Cannot inspect effective Nx configuration for ${name}.`);
  }
  if (!config || config.name !== name || !config.targets || typeof config.targets !== "object" || Array.isArray(config.targets))
    fail11("ORACLE_INFRASTRUCTURE", `Effective Nx configuration for ${name} is invalid or mismatched.`);
  return config;
};
function parseFrontBuildHostMap(raw) {
  if (typeof raw !== "string" || !raw) fail11("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP is required.");
  let map;
  try {
    map = JSON.parse(raw);
  } catch {
    fail11("ORACLE_INFRASTRUCTURE", "FACTORY_FRONT_BUILD_HOST_MAP must be valid JSON.");
  }
  if (!map || typeof map !== "object" || Array.isArray(map))
    fail11("ORACLE_INFRASTRUCTURE", "Host map must be an object.");
  for (const [owner, hosts] of Object.entries(map)) {
    if (owner !== "*" && !validName(owner) || !Array.isArray(hosts) || hosts.length === 0 || hosts.some((host) => !validName(host)))
      fail11("ORACLE_INFRASTRUCTURE", "Host map contains an invalid owner or host.");
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
  if (!owners.length) fail11("ORACLE_INFRASTRUCTURE", "No Nx owner project found for StoryEdit files.");
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
      if (!mapped) fail11("ORACLE_INFRASTRUCTURE", `No build host mapping for owner ${owner.name}.`);
      for (const host of mapped) {
        if (!hostProject(repoRoot, host)) fail11("ORACLE_INFRASTRUCTURE", `Build host ${host} does not exist.`);
        if (!inspect(host).targets.build && !inspect(host).targets["build-angular"])
          fail11("ORACLE_INFRASTRUCTURE", `Build host ${host} has no build target.`);
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
    commandHash: hash5({ build, tests })
  };
}

// ../src/application/forge-bmad/forge-story-oracles.ts
var STORY_ORACLE_POLICY_VERSION = "forge-story-oracles-v1";
var fail12 = (code, message = code) => {
  const error2 = new Error(message);
  error2.code = code;
  throw error2;
};
var isAllowedStoryOracleRequestBody = (body) => !!body && typeof body === "object" && !Array.isArray(body) && Object.keys(body).every((key) => ["editId", "expectedSpecHash", "attempt"].includes(key));
var hash6 = (value) => `sha256:${createHash28("sha256").update(value).digest("hex")}`;
var catalog = {
  "front.build": { domain: "front", name: "build" },
  "front.tests": { domain: "front", name: "tests" },
  "back.build": { domain: "back", name: "build" }
};
function resolve9(ids) {
  const result = [];
  for (const id2 of ids) {
    const entry = catalog[id2];
    if (!entry) fail12("STORY_ORACLE_CATALOG_INVALID", `Unknown oracle catalog id: ${id2}`);
    const oracle = domains[entry.domain]?.oracles.find((item) => item.name === entry.name);
    if (!oracle) fail12("STORY_ORACLE_CATALOG_INVALID", `Unavailable oracle catalog id: ${id2}`);
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
    fail12("STORY_ORACLE_ATTEMPT_INVALID", "attempt must be a positive integer.");
  const store = ensureForgeRunStore(roots);
  const path = join24(store, `${epicRunId}.jsonl`);
  if (!existsSync7(path)) fail12("STORY_ORACLE_RUN_NOT_FOUND", `Epic run ${epicRunId} has no ledger.`);
  const events = parseForgeLedger(path);
  const start = events.find((e) => e.event === "run_started" && e.runId === epicRunId);
  const story = events.find(
    (e) => e.event === "story_run_created" && e.runId === storyRunId && e.parentRunId === epicRunId
  );
  if (!start || !story) fail12("STORY_ORACLE_STORY_NOT_FOUND");
  if (events.some(
    (e) => e.event === "story_oracles_started" && e.editId === editId && !events.some((f) => f.event === "story_g3_evaluated" && f.campaignId === e.campaignId)
  ))
    fail12("STORY_ORACLE_ALREADY_RUNNING", "A Story oracle campaign is already active.");
  if (events.some((e) => e.event === "story_g3_evaluated" && e.editId === editId && e.attempt === attempt))
    fail12("STORY_ORACLE_ATTEMPT_COLLISION", "A terminal campaign already exists for this editId and attempt.");
  const g12 = events.find((e) => e.event === "human_decision_recorded" && e.runId === epicRunId && e.gate === "G1");
  if (g12?.decision?.outcome !== "approved") fail12("STORY_ORACLE_G1_NOT_APPROVED");
  const g22 = events.filter((e) => e.event === "g2_evaluated" && e.runId === epicRunId && e.status === "passed").at(-1);
  if (!g22 || g22.spec?.sha256 !== expectedSpecHash) fail12("STORY_ORACLE_G2_NOT_PASSED");
  const edit = events.find(
    (e) => e.event === "story_edit_finished" && e.editId === editId && e.storyRunId === storyRunId
  );
  if (!edit || edit.status !== "finished" || edit.outcome !== "finished" || edit.diffValidation?.status !== "valid")
    fail12("STORY_ORACLE_EDIT_NOT_VALID");
  let spec;
  try {
    spec = loadForgeSpec({ specPath: g22.spec.path, roots, workItem: start.workItem });
  } catch (error2) {
    if (error2.code === "G2_ORACLE_UNKNOWN")
      fail12(
        "STORY_ORACLE_CATALOG_INVALID",
        "The persisted G2 spec references an oracle outside the StoryOracle catalog."
      );
    throw error2;
  }
  if (spec.sha256 !== g22.spec.sha256) fail12("STORY_ORACLE_SPEC_HASH_STALE");
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
    const campaignId2 = `oracle_${randomUUID16()}`;
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
  const campaignId = `oracle_${randomUUID16()}`;
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
      commandHash: hash6(command)
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

// ../src/adapters/artifact/artifact-hash.ts
import { createHash as createHash29, randomUUID as randomUUID17 } from "node:crypto";
var ARTIFACT_HASH_PREFIX = "sha256";
function computeArtifactHash(data) {
  return `${ARTIFACT_HASH_PREFIX}:${createHash29("sha256").update(data).digest("hex")}`;
}
function createArtifactId() {
  return randomUUID17();
}

// ../src/adapters/artifact/memory-artifact-store.ts
var MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1e3;
function toArtifactBytes(data) {
  return data instanceof Uint8Array ? data : new Uint8Array(data);
}
function artifactTimestamp(value) {
  return value instanceof Date ? value.getTime() : Date.parse(value);
}
function computeRetentionUntil(createdAt, retentionDays) {
  if (retentionDays === void 0) return void 0;
  return new Date(createdAt.getTime() + retentionDays * MILLISECONDS_PER_DAY).toISOString();
}
function isRetentionActive(metadata, now) {
  if (metadata.retentionUntil === void 0) return false;
  return artifactTimestamp(metadata.retentionUntil) > now.getTime();
}
function computeRetentionStatus(metadata, now) {
  return isRetentionActive(metadata, now) ? "active" : "expired";
}
function refreshArtifactMetadata(metadata, now) {
  return { ...metadata, retentionStatus: computeRetentionStatus(metadata, now) };
}
function isArtifactDestroyable(metadata, now) {
  if (metadata.availabilityStatus === "purged") return false;
  if (metadata.legalHold) return false;
  return !isRetentionActive(metadata, now);
}
function buildArtifactMetadata(params) {
  const retentionUntil = computeRetentionUntil(params.now, params.retentionDays);
  const metadata = {
    id: params.id,
    owner: params.owner,
    hash: computeArtifactHash(params.data),
    size: params.data.byteLength,
    contentType: params.contentType,
    availabilityStatus: "available",
    retentionStatus: retentionUntil !== void 0 ? "active" : "expired",
    legalHold: false,
    createdAt: params.now.toISOString(),
    ...params.retentionDays !== void 0 ? { retentionDays: params.retentionDays } : {},
    ...retentionUntil !== void 0 ? { retentionUntil } : {}
  };
  return refreshArtifactMetadata(metadata, params.now);
}
async function* streamArtifactBytes(data, chunkSize) {
  const size = Math.max(1, chunkSize);
  for (let offset = 0; offset < data.byteLength; offset += size) {
    yield data.subarray(offset, Math.min(offset + size, data.byteLength));
  }
}
var MemoryArtifactStore = class {
  #entries = /* @__PURE__ */ new Map();
  #chunkSize;
  #now;
  constructor(options = {}) {
    this.#chunkSize = options.chunkSize ?? 64 * 1024;
    this.#now = options.now ?? (() => /* @__PURE__ */ new Date());
  }
  async putArtifact(params) {
    const now = this.#now();
    const data = toArtifactBytes(params.data);
    const metadata = buildArtifactMetadata({
      id: createArtifactId(),
      owner: params.owner,
      contentType: params.contentType,
      data,
      now,
      ...params.retentionDays !== void 0 ? { retentionDays: params.retentionDays } : {}
    });
    this.#entries.set(metadata.id, { metadata, data: Uint8Array.from(data) });
    return metadata;
  }
  async getArtifactMetadata(artifactId) {
    const entry = this.#entries.get(artifactId);
    if (!entry) return null;
    return refreshArtifactMetadata(entry.metadata, this.#now());
  }
  async openArtifact(artifactId) {
    const entry = this.#entries.get(artifactId);
    if (!entry) return null;
    if (entry.metadata.availabilityStatus === "purged") return null;
    return {
      stream: streamArtifactBytes(entry.data, this.#chunkSize),
      metadata: refreshArtifactMetadata(entry.metadata, this.#now())
    };
  }
  async deleteArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "deleted");
  }
  async purgeArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "retention-expired");
  }
  async setLegalHold(artifactId, legalHold, reason) {
    const entry = this.#entries.get(artifactId);
    if (!entry) return null;
    const now = this.#now();
    const { legalHoldReason: _previousReason, legalHoldSetAt: _previousSetAt, ...rest } = entry.metadata;
    const updated = legalHold ? {
      ...rest,
      legalHold: true,
      ...reason !== void 0 ? { legalHoldReason: reason } : {},
      legalHoldSetAt: now.toISOString()
    } : { ...rest, legalHold: false };
    const refreshed = refreshArtifactMetadata(updated, now);
    entry.metadata = refreshed;
    return refreshed;
  }
  #destroy(artifactId, reason) {
    const entry = this.#entries.get(artifactId);
    if (!entry) return Promise.resolve(false);
    const now = this.#now();
    if (!isArtifactDestroyable(entry.metadata, now)) return Promise.resolve(false);
    entry.metadata = refreshArtifactMetadata(
      {
        ...entry.metadata,
        availabilityStatus: "purged",
        purgedAt: now.toISOString(),
        purgeReason: reason
      },
      now
    );
    entry.data = new Uint8Array(0);
    return Promise.resolve(true);
  }
};
function createMemoryArtifactStore(options) {
  return new MemoryArtifactStore(options);
}

// ../src/adapters/artifact/s3-object-client.ts
import { createHash as createHash30, createHmac } from "node:crypto";
var SIGNING_ALGORITHM = "AWS4-HMAC-SHA256";
var SERVICE = "s3";
var ARTIFACT_SIGNED_URL_TTL_ENV = "ARTIFACT_SIGNED_URL_TTL";
var DEFAULT_SIGNED_URL_TTL_SECONDS = 900;
function sha256Hex(data) {
  return createHash30("sha256").update(data).digest("hex");
}
function hmac(key, data) {
  return createHmac("sha256", key).update(data, "utf8").digest();
}
function formatAmzDate(date) {
  const amzDate = date.toISOString().replace(/[:-]|\.\d{3}/g, "");
  return { amzDate, dateStamp: amzDate.slice(0, 8) };
}
function encodeS3Component(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`);
}
function encodeS3KeyPath(key) {
  return key.split("/").map(encodeS3Component).join("/");
}
function resolveSignedUrlTtl(explicit) {
  if (explicit !== void 0 && Number.isFinite(explicit) && explicit > 0) return Math.floor(explicit);
  const parsed = Number.parseInt(process.env[ARTIFACT_SIGNED_URL_TTL_ENV] ?? "", 10);
  if (Number.isFinite(parsed) && parsed > 0) return parsed;
  return DEFAULT_SIGNED_URL_TTL_SECONDS;
}
function decodeXmlEntities(value) {
  return value.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}
async function* iterateWebStream(body) {
  const reader = body.getReader();
  try {
    for (; ; ) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) yield value;
    }
  } finally {
    reader.releaseLock();
  }
}
async function* emptyStream() {
}
var S3ObjectClient = class {
  #config;
  #base;
  #host;
  #fetch;
  constructor(config) {
    if (!config.endpoint) throw new Error("S3ObjectClient requires an endpoint");
    if (!config.bucket) throw new Error("S3ObjectClient requires a bucket");
    this.#config = config;
    this.#base = config.endpoint.replace(/\/+$/, "");
    this.#host = new URL(this.#base).host;
    this.#fetch = config.fetchImpl ?? fetch;
  }
  /** Stores an object at `key`. */
  async putObject(key, body, contentType) {
    const response2 = await this.#send("PUT", key, {
      body,
      ...contentType !== void 0 ? { contentType } : {}
    });
    if (!response2.ok) throw await this.#failure("PUT", key, response2);
    await response2.arrayBuffer();
  }
  /** Server-side copies `sourceKey` to `destinationKey`. */
  async copyObject(sourceKey, destinationKey) {
    const response2 = await this.#send("PUT", destinationKey, {
      copySource: `/${this.#config.bucket}/${encodeS3KeyPath(sourceKey)}`
    });
    if (!response2.ok) throw await this.#failure("COPY", destinationKey, response2);
    await response2.arrayBuffer();
  }
  /** Reads an object, or returns `null` when it does not exist. */
  async getObject(key) {
    const response2 = await this.#send("GET", key);
    if (response2.status === 404) return null;
    if (!response2.ok) throw await this.#failure("GET", key, response2);
    const body = response2.body;
    return { stream: body ? iterateWebStream(body) : emptyStream() };
  }
  /** Deletes an object. Returns `true` when a deletion happened. */
  async deleteObject(key) {
    const response2 = await this.#send("DELETE", key);
    if (response2.status === 404) return false;
    if (!response2.ok && response2.status !== 204) throw await this.#failure("DELETE", key, response2);
    await response2.arrayBuffer();
    return true;
  }
  /** Returns whether an object exists. */
  async headObject(key) {
    const response2 = await this.#send("HEAD", key);
    if (response2.status === 404) return false;
    if (!response2.ok) throw await this.#failure("HEAD", key, response2);
    return true;
  }
  /**
   * Computes a SigV4 pre-signed GET URL for `key` using query-parameter
   * authentication (no network round-trip).
   *
   * The TTL is resolved from `options.expiresInSeconds`, then the
   * {@link ARTIFACT_SIGNED_URL_TTL_ENV} environment variable, then
   * {@link DEFAULT_SIGNED_URL_TTL_SECONDS}. The optional `now` makes the
   * produced signature deterministic for tests.
   */
  getSignedUrl(key, options) {
    const ttl = resolveSignedUrlTtl(options?.expiresInSeconds);
    const { amzDate, dateStamp } = formatAmzDate(options?.now ?? /* @__PURE__ */ new Date());
    const canonicalUri = `/${encodeS3Component(this.#config.bucket)}${key ? `/${encodeS3KeyPath(key)}` : ""}`;
    const scope = `${dateStamp}/${this.#config.region}/${SERVICE}/aws4_request`;
    const query = {
      "X-Amz-Algorithm": SIGNING_ALGORITHM,
      "X-Amz-Credential": `${this.#config.accessKeyId}/${scope}`,
      "X-Amz-Date": amzDate,
      "X-Amz-Expires": String(ttl),
      "X-Amz-SignedHeaders": "host"
    };
    if (this.#config.sessionToken !== void 0) query["X-Amz-Security-Token"] = this.#config.sessionToken;
    const canonicalQuery = this.#canonicalQuery(query);
    const canonicalHeaders = `host:${this.#host}
`;
    const canonicalRequest = ["GET", canonicalUri, canonicalQuery, canonicalHeaders, "host", "UNSIGNED-PAYLOAD"].join(
      "\n"
    );
    const stringToSign = [SIGNING_ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)].join("\n");
    const signingKey = hmac(
      hmac(hmac(hmac(`AWS4${this.#config.secretAccessKey}`, dateStamp), this.#config.region), SERVICE),
      "aws4_request"
    );
    const signature = createHmac("sha256", signingKey).update(stringToSign, "utf8").digest("hex");
    return `${this.#base}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`;
  }
  /** Lists every object key under `prefix`, following continuation tokens. */
  async listObjectKeys(prefix) {
    const keys = [];
    let continuationToken;
    for (; ; ) {
      const query = { "list-type": "2", prefix };
      if (continuationToken !== void 0) query["continuation-token"] = continuationToken;
      const response2 = await this.#send("GET", "", { query });
      if (!response2.ok) throw await this.#failure("LIST", prefix, response2);
      const xml = await response2.text();
      for (const match2 of xml.matchAll(/<Key>([\s\S]*?)<\/Key>/g)) {
        const key = match2[1];
        if (key !== void 0) keys.push(decodeXmlEntities(key));
      }
      if (!/<IsTruncated>true<\/IsTruncated>/.test(xml)) break;
      const token = xml.match(/<NextContinuationToken>([\s\S]*?)<\/NextContinuationToken>/)?.[1];
      if (token === void 0) break;
      continuationToken = decodeXmlEntities(token);
    }
    return keys;
  }
  async #failure(operation, key, response2) {
    let detail = "";
    try {
      detail = (await response2.text()).slice(0, 512);
    } catch {
      detail = "";
    }
    return new Error(
      `S3 ${operation} ${key || "<bucket>"} failed with status ${response2.status}${detail ? `: ${detail}` : ""}`
    );
  }
  #canonicalQuery(query) {
    if (!query) return "";
    return Object.keys(query).sort().map((name) => `${encodeS3Component(name)}=${encodeS3Component(query[name] ?? "")}`).join("&");
  }
  async #send(method, key, options = {}) {
    const canonicalQuery = this.#canonicalQuery(options.query);
    const canonicalUri = `/${encodeS3Component(this.#config.bucket)}${key ? `/${encodeS3KeyPath(key)}` : ""}`;
    const url = `${this.#base}${canonicalUri}${canonicalQuery ? `?${canonicalQuery}` : ""}`;
    const payload = options.body ?? new Uint8Array();
    const payloadHash = sha256Hex(payload);
    const headers = {
      host: this.#host,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": ""
    };
    const { amzDate, dateStamp } = formatAmzDate(/* @__PURE__ */ new Date());
    headers["x-amz-date"] = amzDate;
    if (this.#config.sessionToken !== void 0) headers["x-amz-security-token"] = this.#config.sessionToken;
    if (options.contentType !== void 0) headers["content-type"] = options.contentType;
    if (options.copySource !== void 0) headers["x-amz-copy-source"] = options.copySource;
    const signedHeaderNames = Object.keys(headers).filter((name) => name === "host" || name.startsWith("x-amz-")).sort();
    const canonicalHeaders = signedHeaderNames.map((name) => `${name}:${(headers[name] ?? "").trim()}
`).join("");
    const signedHeaders = signedHeaderNames.join(";");
    const canonicalRequest = [method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash].join(
      "\n"
    );
    const scope = `${dateStamp}/${this.#config.region}/${SERVICE}/aws4_request`;
    const stringToSign = [SIGNING_ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)].join("\n");
    const signingKey = hmac(
      hmac(hmac(hmac(`AWS4${this.#config.secretAccessKey}`, dateStamp), this.#config.region), SERVICE),
      "aws4_request"
    );
    const signature = createHmac("sha256", signingKey).update(stringToSign, "utf8").digest("hex");
    const requestHeaders = {
      authorization: `${SIGNING_ALGORITHM} Credential=${this.#config.accessKeyId}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": amzDate
    };
    if (this.#config.sessionToken !== void 0) requestHeaders["x-amz-security-token"] = this.#config.sessionToken;
    if (options.contentType !== void 0) requestHeaders["content-type"] = options.contentType;
    if (options.copySource !== void 0) requestHeaders["x-amz-copy-source"] = options.copySource;
    const init = { method, headers: requestHeaders };
    if (method !== "GET" && method !== "HEAD") init.body = Buffer.from(payload);
    return this.#fetch(url, init);
  }
};
function createS3ObjectClient(config) {
  return new S3ObjectClient(config);
}

// ../src/adapters/artifact/s3-artifact-store.ts
var DEFAULT_UPLOAD_PREFIX = "uploads";
var DEFAULT_OBJECT_PREFIX = "objects";
var DEFAULT_METADATA_PREFIX = "metadata";
async function readAllBytes(stream) {
  const chunks = [];
  let total = 0;
  for await (const chunk of stream) {
    chunks.push(chunk);
    total += chunk.byteLength;
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}
function encodeJson(value) {
  return Buffer.from(JSON.stringify(value), "utf8");
}
var S3ArtifactStore = class {
  #client;
  #uploadPrefix;
  #objectPrefix;
  #metadataPrefix;
  #now;
  constructor(config) {
    this.#client = config.client ?? new S3ObjectClient(config);
    this.#uploadPrefix = config.uploadPrefix ?? DEFAULT_UPLOAD_PREFIX;
    this.#objectPrefix = config.objectPrefix ?? DEFAULT_OBJECT_PREFIX;
    this.#metadataPrefix = config.metadataPrefix ?? DEFAULT_METADATA_PREFIX;
    this.#now = config.now ?? (() => /* @__PURE__ */ new Date());
  }
  async putArtifact(params) {
    const now = this.#now();
    const data = toArtifactBytes(params.data);
    const id2 = createArtifactId();
    const hash7 = computeArtifactHash(data);
    const metadata = buildArtifactMetadata({
      id: id2,
      owner: params.owner,
      contentType: params.contentType,
      data,
      now,
      ...params.retentionDays !== void 0 ? { retentionDays: params.retentionDays } : {}
    });
    await this.#client.putObject(this.#stagingKey(id2), Uint8Array.from(data), "application/octet-stream");
    await this.#client.copyObject(this.#stagingKey(id2), this.#contentKey(hash7));
    await this.#client.putObject(this.#metadataKey(id2), encodeJson(metadata), "application/json");
    await this.#bestEffortDelete(this.#stagingKey(id2));
    return metadata;
  }
  async getArtifactMetadata(artifactId) {
    const response2 = await this.#client.getObject(this.#metadataKey(artifactId));
    if (!response2) return null;
    const bytes = await readAllBytes(response2.stream);
    const metadata = JSON.parse(Buffer.from(bytes).toString("utf8"));
    return refreshArtifactMetadata(metadata, this.#now());
  }
  async openArtifact(artifactId) {
    const metadata = await this.getArtifactMetadata(artifactId);
    if (!metadata) return null;
    if (metadata.availabilityStatus === "purged") return null;
    const object = await this.#client.getObject(this.#contentKey(metadata.hash));
    if (!object) return null;
    return { stream: object.stream, metadata };
  }
  async deleteArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "deleted");
  }
  async purgeArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "retention-expired");
  }
  async setLegalHold(artifactId, legalHold, reason) {
    const metadata = await this.getArtifactMetadata(artifactId);
    if (!metadata) return null;
    const now = this.#now();
    const { legalHoldReason: _previousReason, legalHoldSetAt: _previousSetAt, ...rest } = metadata;
    const updated = legalHold ? {
      ...rest,
      legalHold: true,
      ...reason !== void 0 ? { legalHoldReason: reason } : {},
      legalHoldSetAt: now.toISOString()
    } : { ...rest, legalHold: false };
    const refreshed = refreshArtifactMetadata(updated, now);
    await this.#client.putObject(this.#metadataKey(artifactId), encodeJson(refreshed), "application/json");
    return refreshed;
  }
  /**
   * Deletes staging objects left behind by interrupted uploads. Returns the
   * keys that were reclaimed.
   */
  async collectOrphanedUploads() {
    const keys = await this.#client.listObjectKeys(`${this.#uploadPrefix}/`);
    const reclaimed = [];
    for (const key of keys) {
      if (await this.#bestEffortDelete(key)) reclaimed.push(key);
    }
    return reclaimed;
  }
  async #destroy(artifactId, reason) {
    const metadata = await this.getArtifactMetadata(artifactId);
    if (!metadata) return false;
    const now = this.#now();
    if (!isArtifactDestroyable(metadata, now)) return false;
    const purged = refreshArtifactMetadata(
      {
        ...metadata,
        availabilityStatus: "purged",
        purgedAt: now.toISOString(),
        purgeReason: reason
      },
      now
    );
    await this.#client.putObject(this.#metadataKey(artifactId), encodeJson(purged), "application/json");
    await this.#bestEffortDelete(this.#contentKey(metadata.hash));
    return true;
  }
  async #bestEffortDelete(key) {
    try {
      return await this.#client.deleteObject(key);
    } catch {
      return false;
    }
  }
  #stagingKey(id2) {
    return `${this.#uploadPrefix}/${id2}.part`;
  }
  #contentKey(hash7) {
    const digest4 = hash7.startsWith(`${ARTIFACT_HASH_PREFIX}:`) ? hash7.slice(ARTIFACT_HASH_PREFIX.length + 1) : hash7;
    return `${this.#objectPrefix}/${digest4}`;
  }
  #metadataKey(id2) {
    return `${this.#metadataPrefix}/${id2}.json`;
  }
};
function createS3ArtifactStore(config) {
  return new S3ArtifactStore(config);
}

// ../src/adapters/persistence/sql/sql-artifact-metadata-repository.ts
var DEFAULT_NAMESPACE_ID = "default";
var DEFAULT_WORKFLOW_ID = "default";
var ARTIFACT_COLUMNS = [
  "organization_id",
  "workstream_id",
  "namespace_id",
  "workflow_id",
  "artifact_id",
  "availability_status",
  "retention_status",
  "legal_hold",
  "retention_until",
  "purged_at",
  "purge_reason",
  "legal_hold_reason",
  "legal_hold_set_at",
  "content_hash",
  "size",
  "content_type",
  "storage_key",
  "payload",
  "created_at",
  "updated_at"
].join(", ");
var ARTIFACT_INSERT_COLUMNS = ARTIFACT_COLUMNS;
function toIsoTimestamp(value) {
  if (value === null || value === void 0) return void 0;
  return value instanceof Date ? value.toISOString() : String(value);
}
function isoFromInput(value) {
  if (value === void 0) return void 0;
  return value instanceof Date ? value.toISOString() : String(value);
}
function toAvailabilityStatus(value) {
  if (value === "purged") return "purged";
  if (value === "available") return "available";
  return "archived";
}
function toRetentionStatus(value) {
  return value === "expired" ? "expired" : "active";
}
function parseArtifactPayload(value) {
  try {
    const parsed = parseJsonColumn(value);
    return parsed ?? {};
  } catch {
    return {};
  }
}
function parseOwnerScope(owner) {
  if (!owner) return {};
  const separator = owner.indexOf("/");
  if (separator > 0 && separator < owner.length - 1) {
    return { namespaceId: owner.slice(0, separator), workflowId: owner.slice(separator + 1) };
  }
  return { namespaceId: owner };
}
var SqlArtifactMetadataRepository = class {
  #client;
  #options;
  constructor(client, options = {}) {
    this.#client = client;
    this.#options = options;
  }
  /** Resolves an effective scope from an optional per-call override. */
  #resolveScope(scope) {
    return {
      organizationId: scope?.organizationId ?? this.#options.organizationId ?? DEFAULT_ORGANIZATION_ID,
      workstreamId: scope?.workstreamId ?? this.#options.workstreamId ?? DEFAULT_WORKSTREAM_ID,
      namespaceId: scope?.namespaceId ?? this.#options.namespaceId ?? DEFAULT_NAMESPACE_ID,
      workflowId: scope?.workflowId ?? this.#options.workflowId ?? DEFAULT_WORKFLOW_ID
    };
  }
  /** Resolves the scope used when persisting, defaulting from the owner. */
  #resolveSaveScope(owner, scope) {
    const resolved = this.#resolveScope(scope);
    if (scope?.namespaceId === void 0 && this.#options.namespaceId === void 0) {
      const parsed = parseOwnerScope(owner);
      if (parsed.namespaceId !== void 0) resolved.namespaceId = parsed.namespaceId;
    }
    if (scope?.workflowId === void 0 && this.#options.workflowId === void 0) {
      const parsed = parseOwnerScope(owner);
      if (parsed.workflowId !== void 0) resolved.workflowId = parsed.workflowId;
    }
    return resolved;
  }
  /** Builds the optional hierarchy narrowing of a lookup query. */
  #scopeFilter(sql, params, scope) {
    let clause = sql;
    const filters = [...params];
    if (scope?.namespaceId !== void 0) {
      filters.push(scope.namespaceId);
      clause += ` AND namespace_id = $${filters.length}`;
    }
    if (scope?.workflowId !== void 0) {
      filters.push(scope.workflowId);
      clause += ` AND workflow_id = $${filters.length}`;
    }
    return { sql: clause, params: filters };
  }
  /** Maps a durable row into the domain {@link ArtifactMetadata}. */
  #toMetadata(row) {
    const payload = parseArtifactPayload(row.payload);
    const createdAt = toIsoTimestamp(row.created_at) ?? (/* @__PURE__ */ new Date()).toISOString();
    const retentionUntil = toIsoTimestamp(row.retention_until);
    const purgedAt = toIsoTimestamp(row.purged_at);
    const legalHoldSetAt = toIsoTimestamp(row.legal_hold_set_at);
    return {
      id: row.artifact_id,
      owner: payload.owner ?? row.namespace_id,
      hash: row.content_hash,
      size: Number(row.size),
      contentType: row.content_type,
      availabilityStatus: toAvailabilityStatus(row.availability_status),
      retentionStatus: toRetentionStatus(row.retention_status),
      legalHold: row.legal_hold === true,
      createdAt,
      ...payload.retentionDays !== void 0 ? { retentionDays: payload.retentionDays } : {},
      ...retentionUntil !== void 0 ? { retentionUntil } : {},
      ...purgedAt !== void 0 ? { purgedAt } : {},
      ...row.purge_reason != null ? { purgeReason: row.purge_reason } : {},
      ...row.legal_hold_reason != null ? { legalHoldReason: row.legal_hold_reason } : {},
      ...legalHoldSetAt !== void 0 ? { legalHoldSetAt } : {}
    };
  }
  async #selectRow(artifactId, scope) {
    const resolved = this.#resolveScope(scope);
    const filter = this.#scopeFilter(
      `SELECT ${ARTIFACT_COLUMNS} FROM artifacts
       WHERE organization_id = $1 AND workstream_id = $2 AND artifact_id = $3`,
      [resolved.organizationId, resolved.workstreamId, artifactId],
      scope
    );
    const { rows } = await this.#client.query(filter.sql, filter.params);
    return rows[0] ?? null;
  }
  /**
   * Inserts (or upserts) the authoritative metadata row for an artifact,
   * returning the persisted metadata.
   */
  async saveMetadata(metadata, storageKey, scope) {
    const resolved = this.#resolveSaveScope(metadata.owner, scope);
    const createdAt = isoFromInput(metadata.createdAt) ?? (/* @__PURE__ */ new Date()).toISOString();
    const payload = JSON.stringify({
      owner: metadata.owner,
      ...metadata.retentionDays !== void 0 ? { retentionDays: metadata.retentionDays } : {}
    });
    const retentionUntil = isoFromInput(metadata.retentionUntil) ?? null;
    const purgedAt = isoFromInput(metadata.purgedAt) ?? null;
    const legalHoldSetAt = isoFromInput(metadata.legalHoldSetAt) ?? null;
    const values = [
      resolved.organizationId,
      resolved.workstreamId,
      resolved.namespaceId,
      resolved.workflowId,
      metadata.id,
      metadata.availabilityStatus,
      metadata.retentionStatus,
      metadata.legalHold,
      retentionUntil,
      purgedAt,
      metadata.purgeReason ?? null,
      metadata.legalHoldReason ?? null,
      legalHoldSetAt,
      metadata.hash,
      metadata.size,
      metadata.contentType,
      storageKey,
      payload,
      createdAt,
      createdAt
    ];
    await withTransaction(this.#client, async (tx) => {
      await tx.query(
        `INSERT INTO artifacts (${ARTIFACT_INSERT_COLUMNS}) VALUES (${values.map((_, index) => `$${index + 1}`).join(", ")})
         ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, artifact_id)
         DO UPDATE SET
           availability_status = EXCLUDED.availability_status,
           retention_status = EXCLUDED.retention_status,
           legal_hold = EXCLUDED.legal_hold,
           retention_until = EXCLUDED.retention_until,
           purged_at = EXCLUDED.purged_at,
           purge_reason = EXCLUDED.purge_reason,
           legal_hold_reason = EXCLUDED.legal_hold_reason,
           legal_hold_set_at = EXCLUDED.legal_hold_set_at,
           content_hash = EXCLUDED.content_hash,
           size = EXCLUDED.size,
           content_type = EXCLUDED.content_type,
           storage_key = EXCLUDED.storage_key,
           payload = EXCLUDED.payload,
           updated_at = EXCLUDED.updated_at`,
        values
      );
    });
    return metadata;
  }
  /** Returns the artifact metadata, or `null` when no row exists. */
  async getMetadata(artifactId, scope) {
    const row = await this.#selectRow(artifactId, scope);
    return row ? this.#toMetadata(row) : null;
  }
  /** Returns the metadata together with its object-storage key. */
  async getMetadataAndStorageKey(artifactId, scope) {
    const row = await this.#selectRow(artifactId, scope);
    if (!row) return null;
    return { metadata: this.#toMetadata(row), storageKey: row.storage_key };
  }
  /**
   * Sets or releases the legal hold of an artifact, returning the refreshed
   * metadata (or `null` when the artifact is unknown).
   */
  async updateLegalHold(artifactId, legalHold, reason, now = /* @__PURE__ */ new Date(), scope) {
    const resolved = this.#resolveScope(scope);
    const nowIso = now.toISOString();
    const result = await this.#client.query(
      `UPDATE artifacts
         SET legal_hold = $1, legal_hold_reason = $2, legal_hold_set_at = $3, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND artifact_id = $7`,
      [
        legalHold,
        legalHold ? reason ?? null : null,
        legalHold ? nowIso : null,
        nowIso,
        resolved.organizationId,
        resolved.workstreamId,
        artifactId
      ]
    );
    if (!result.rowCount) return null;
    const row = await this.#selectRow(artifactId, scope);
    return row ? this.#toMetadata(row) : null;
  }
  /**
   * Purges an artifact: flips `availability_status` to `'purged'` and records
   * the reason and timestamp. The SQL guard (`legal_hold = FALSE` and
   * `availability_status <> 'purged'`) enforces the golden rule: a held
   * artifact can never be purged. Returns `true` when a row was updated.
   */
  async purgeArtifact(artifactId, reason, now = /* @__PURE__ */ new Date(), scope) {
    const resolved = this.#resolveScope(scope);
    const nowIso = now.toISOString();
    const result = await this.#client.query(
      `UPDATE artifacts
         SET availability_status = 'purged', purged_at = $1, purge_reason = $2, updated_at = $3
       WHERE organization_id = $4 AND workstream_id = $5 AND artifact_id = $6
         AND legal_hold = $7 AND availability_status <> 'purged'`,
      [nowIso, reason, nowIso, resolved.organizationId, resolved.workstreamId, artifactId, false]
    );
    return (result.rowCount ?? 0) > 0;
  }
};
function createSqlArtifactMetadataRepository(client, options = {}) {
  return new SqlArtifactMetadataRepository(client, options);
}

// ../src/adapters/artifact/postgres-artifact-store.ts
var DEFAULT_UPLOAD_PREFIX2 = "uploads";
var DEFAULT_OBJECT_PREFIX2 = "objects";
var DEFAULT_ARTIFACT_RETENTION_DAYS = 90;
var ARTIFACT_RETENTION_DAYS_ENV = "ARTIFACT_RETENTION_DAYS";
var PostgresArtifactStore = class {
  #client;
  #repository;
  #uploadPrefix;
  #objectPrefix;
  #retentionDays;
  #env;
  #now;
  constructor(config) {
    this.#client = config.client;
    if (config.repository) {
      this.#repository = config.repository;
    } else if (config.sqlClient) {
      const options = {
        ...config.organizationId !== void 0 ? { organizationId: config.organizationId } : {},
        ...config.workstreamId !== void 0 ? { workstreamId: config.workstreamId } : {},
        ...config.namespaceId !== void 0 ? { namespaceId: config.namespaceId } : {},
        ...config.workflowId !== void 0 ? { workflowId: config.workflowId } : {}
      };
      this.#repository = createSqlArtifactMetadataRepository(config.sqlClient, options);
    } else {
      throw new Error("PostgresArtifactStore requires a `repository` or a `sqlClient`");
    }
    this.#uploadPrefix = config.uploadPrefix ?? DEFAULT_UPLOAD_PREFIX2;
    this.#objectPrefix = config.objectPrefix ?? DEFAULT_OBJECT_PREFIX2;
    this.#retentionDays = config.retentionDays;
    this.#env = config.env ?? process.env;
    this.#now = config.now ?? (() => /* @__PURE__ */ new Date());
  }
  async putArtifact(params) {
    const now = this.#now();
    const data = toArtifactBytes(params.data);
    const id2 = createArtifactId();
    const hash7 = computeArtifactHash(data);
    const retentionDays = params.retentionDays ?? this.#defaultRetentionDays();
    const metadata = buildArtifactMetadata({
      id: id2,
      owner: params.owner,
      contentType: params.contentType,
      data,
      retentionDays,
      now
    });
    const stagingKey = this.#stagingKey(id2);
    const contentKey = this.#contentKey(hash7);
    await this.#client.putObject(stagingKey, Uint8Array.from(data), "application/octet-stream");
    await this.#client.copyObject(stagingKey, contentKey);
    if (!await this.#objectExists(contentKey)) {
      throw new Error("ARTIFACT_OBJECT_VERIFICATION_FAILED");
    }
    await this.#repository.saveMetadata(metadata, contentKey);
    await this.#bestEffortDelete(stagingKey);
    return metadata;
  }
  async getArtifactMetadata(artifactId) {
    const metadata = await this.#repository.getMetadata(artifactId);
    if (!metadata) return null;
    return refreshArtifactMetadata(metadata, this.#now());
  }
  async openArtifact(artifactId) {
    const record2 = await this.#repository.getMetadataAndStorageKey(artifactId);
    if (!record2) return null;
    const metadata = refreshArtifactMetadata(record2.metadata, this.#now());
    if (metadata.availabilityStatus === "purged") return null;
    const object = await this.#client.getObject(record2.storageKey);
    if (!object) return null;
    return { stream: object.stream, metadata };
  }
  async deleteArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "deleted");
  }
  /**
   * Returns a pre-signed URL granting temporary read access to an artifact's
   * payload, or `null` when the artifact is unknown, no longer readable
   * (`availabilityStatus !== 'available'`) or when the underlying blob client
   * cannot presign.
   *
   * PostgreSQL metadata stays authoritative: the storage key and availability
   * are read from the repository row, never inferred from the caller.
   */
  async getSignedUrl(artifactId, options) {
    if (typeof this.#client.getSignedUrl !== "function") return null;
    const record2 = await this.#repository.getMetadataAndStorageKey(artifactId);
    if (!record2) return null;
    const metadata = refreshArtifactMetadata(record2.metadata, options?.now ?? this.#now());
    if (metadata.availabilityStatus !== "available") return null;
    return this.#client.getSignedUrl(record2.storageKey, options);
  }
  async purgeArtifact(artifactId, reason) {
    return this.#destroy(artifactId, reason ?? "retention-expired");
  }
  async setLegalHold(artifactId, legalHold, reason) {
    const now = this.#now();
    return this.#repository.updateLegalHold(artifactId, legalHold, reason, now);
  }
  /**
   * Deletes staging objects left behind by interrupted uploads. Returns the
   * reclaimed keys. Mirrors {@link S3ArtifactStore.collectOrphanedUploads}.
   */
  async collectOrphanedUploads() {
    if (typeof this.#client.listObjectKeys !== "function") return [];
    const keys = await this.#client.listObjectKeys(`${this.#uploadPrefix}/`);
    const reclaimed = [];
    for (const key of keys) {
      if (await this.#bestEffortDelete(key)) reclaimed.push(key);
    }
    return reclaimed;
  }
  async #destroy(artifactId, reason) {
    const record2 = await this.#repository.getMetadataAndStorageKey(artifactId);
    if (!record2) return false;
    const now = this.#now();
    const metadata = refreshArtifactMetadata(record2.metadata, now);
    if (!isArtifactDestroyable(metadata, now)) return false;
    const purged = await this.#repository.purgeArtifact(artifactId, reason, now);
    if (!purged) return false;
    await this.#bestEffortDelete(record2.storageKey);
    return true;
  }
  #defaultRetentionDays() {
    if (this.#retentionDays !== void 0) return this.#retentionDays;
    const parsed = Number.parseInt(this.#env[ARTIFACT_RETENTION_DAYS_ENV] ?? "", 10);
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_ARTIFACT_RETENTION_DAYS;
  }
  async #objectExists(key) {
    if (typeof this.#client.headObject === "function") return this.#client.headObject(key);
    const object = await this.#client.getObject(key);
    return object !== null;
  }
  async #bestEffortDelete(key) {
    try {
      return await this.#client.deleteObject(key);
    } catch {
      return false;
    }
  }
  #stagingKey(id2) {
    return `${this.#uploadPrefix}/${id2}.part`;
  }
  #contentKey(hash7) {
    const digest4 = hash7.startsWith(`${ARTIFACT_HASH_PREFIX}:`) ? hash7.slice(ARTIFACT_HASH_PREFIX.length + 1) : hash7;
    return `${this.#objectPrefix}/${digest4}`;
  }
};
function createPostgresArtifactStore(config) {
  return new PostgresArtifactStore(config);
}

// ../src/application/artifact/artifact-admin-use-cases.ts
var ARTIFACT_ADMIN_UPLOAD_PREFIX = "uploads";
var ARTIFACT_ADMIN_OBJECT_PREFIX = "objects";
var ArtifactAdminError = class extends Error {
  /** Stable machine-readable error code. */
  code;
  /** Transport-agnostic HTTP status suggestion. */
  statusCode;
  constructor(code, message, statusCode) {
    super(message);
    this.name = "ArtifactAdminError";
    this.code = code;
    this.statusCode = statusCode;
  }
};
function isOrphanUploadCollector(store) {
  return !!store && typeof store === "object" && typeof store.collectOrphanedUploads === "function";
}
async function purgeArtifactAdmin(store, artifactId, reason) {
  const metadata = await store.getArtifactMetadata(artifactId);
  if (!metadata) {
    return { success: false, artifactId, reason, status: "NOT_FOUND" };
  }
  if (metadata.legalHold) {
    return { success: false, artifactId, reason, status: "LEGAL_HOLD_ACTIVE" };
  }
  if (metadata.retentionStatus === "active") {
    return { success: false, artifactId, reason, status: "RETENTION_ACTIVE" };
  }
  const purged = await store.purgeArtifact(artifactId, reason);
  if (!purged) {
    const latest = await store.getArtifactMetadata(artifactId);
    if (!latest) return { success: false, artifactId, reason, status: "NOT_FOUND" };
    if (latest.legalHold) return { success: false, artifactId, reason, status: "LEGAL_HOLD_ACTIVE" };
    return { success: false, artifactId, reason, status: "RETENTION_ACTIVE" };
  }
  const refreshed = await store.getArtifactMetadata(artifactId);
  const metadataAfterPurge = refreshed ?? { ...metadata, availabilityStatus: "purged" };
  return { success: true, artifactId, reason, status: "purged", metadata: metadataAfterPurge };
}
async function setLegalHoldAdmin(store, artifactId, legalHold, reason) {
  const updated = await store.setLegalHold(artifactId, legalHold, reason);
  if (!updated) {
    throw new ArtifactAdminError("ARTIFACT_NOT_FOUND", `Artifact ${artifactId} not found`, 404);
  }
  return updated;
}
async function reclaimOrphanedUploads(store, blobClient, uploadPrefix) {
  if (isOrphanUploadCollector(store)) {
    return store.collectOrphanedUploads();
  }
  if (typeof blobClient.listObjectKeys !== "function") return [];
  const keys = await blobClient.listObjectKeys(`${uploadPrefix}/`);
  const reclaimed = [];
  for (const key of keys) {
    const deleted = await blobClient.deleteObject(key);
    if (deleted) reclaimed.push(key);
  }
  return reclaimed;
}
async function listObjectKeys(blobClient, prefix) {
  if (typeof blobClient.listObjectKeys !== "function") return [];
  return blobClient.listObjectKeys(prefix);
}
async function collectAndAuditGarbage(store, blobClient, options = {}) {
  const uploadPrefix = options.uploadPrefix ?? ARTIFACT_ADMIN_UPLOAD_PREFIX;
  const objectPrefix = options.objectPrefix ?? ARTIFACT_ADMIN_OBJECT_PREFIX;
  const now = options.now ?? (() => /* @__PURE__ */ new Date());
  const reclaimedStagingKeys = await reclaimOrphanedUploads(store, blobClient, uploadPrefix);
  const scannedBlobKeys = await listObjectKeys(blobClient, `${objectPrefix}/`);
  const metadataRows = options.listMetadata ? await options.listMetadata() : [];
  const blobKeySet = new Set(scannedBlobKeys);
  const referencedKeys = new Set(metadataRows.map((row) => row.storageKey));
  const anomalies = [];
  for (const storageKey of scannedBlobKeys) {
    if (!referencedKeys.has(storageKey)) {
      anomalies.push({
        type: "blob_without_pg_row",
        storageKey,
        details: `Blob ${storageKey} has no authoritative metadata row`
      });
    }
  }
  for (const row of metadataRows) {
    if (row.availabilityStatus === "purged") {
      anomalies.push({
        type: "pg_purged_or_missing_blob",
        artifactId: row.artifactId,
        storageKey: row.storageKey,
        details: `Metadata row ${row.artifactId} is marked purged`
      });
    } else if (!blobKeySet.has(row.storageKey)) {
      anomalies.push({
        type: "pg_purged_or_missing_blob",
        artifactId: row.artifactId,
        storageKey: row.storageKey,
        details: `Blob ${row.storageKey} for artifact ${row.artifactId} is missing from object storage`
      });
    }
  }
  return {
    reclaimedStagingKeys,
    anomalies,
    scannedBlobKeys,
    scannedMetadataRows: metadataRows.length,
    timestamp: now().toISOString()
  };
}

// ../src/domain/worker-runtime/worker-runtime.ts
var FENCING_ERROR_CODES = /* @__PURE__ */ new Set([
  LEASE_ERROR_CODES.LEASE_FENCED,
  LEASE_ERROR_CODES.LEASE_EXPIRED,
  LEASE_ERROR_CODES.LEASE_NOT_FOUND
]);
var DEFAULT_PROTOCOL_VERSION = "1";
var defaultTimers = {
  setInterval: (handler, timeout) => setInterval(handler, timeout),
  clearInterval: (handle) => {
    clearInterval(handle);
  },
  setTimeout: (handler, timeout) => setTimeout(handler, timeout),
  clearTimeout: (handle) => {
    clearTimeout(handle);
  }
};
var noopLogger = {
  info: () => {
  },
  warn: () => {
  },
  error: () => {
  },
  debug: () => {
  }
};
function errorMessage(error2) {
  return error2 instanceof Error ? error2.message : String(error2);
}
function errorCode(error2) {
  const code = error2?.code;
  return typeof code === "string" ? code : null;
}
var WorkerRuntime = class {
  #config;
  #leaseRepo;
  #workUnitRepo;
  #workerRepo;
  #executor;
  #clock;
  #timers;
  #logger;
  #idGenerator;
  #concurrency;
  #instanceId;
  #status = "stopped";
  #worker = null;
  #workerHeartbeatHandle = null;
  #active = /* @__PURE__ */ new Map();
  #sleepers = /* @__PURE__ */ new Set();
  #slotWaiters = /* @__PURE__ */ new Set();
  #workerQueue = Promise.resolve();
  #loopRunning = false;
  constructor(config, deps) {
    this.#config = config;
    this.#leaseRepo = deps.leaseRepo;
    this.#workUnitRepo = deps.workUnitRepo;
    this.#workerRepo = deps.workerRepo;
    this.#executor = deps.executor;
    this.#clock = deps.clock ?? (() => /* @__PURE__ */ new Date());
    this.#timers = deps.timers ?? defaultTimers;
    this.#logger = deps.logger ?? noopLogger;
    this.#idGenerator = deps.idGenerator ?? (() => crypto.randomUUID());
    this.#concurrency = Math.max(1, Math.trunc(config.concurrency ?? 1));
    this.#instanceId = this.#idGenerator();
  }
  /** Current lifecycle state. */
  get status() {
    return this.#status;
  }
  /** Number of executions currently in flight. */
  get activeCount() {
    return this.#active.size;
  }
  /** Identifier of this runtime instance (generated from the injected source). */
  get instanceId() {
    return this.#instanceId;
  }
  /** The worker row as last observed; `null` before {@link start}. */
  get worker() {
    return this.#worker;
  }
  /**
   * Registers the worker, flips it to `idle`, starts its liveness heartbeat and
   * enters the claim loop. Idempotent: a second call while running is a no-op.
   */
  async start() {
    if (this.#status !== "stopped") return;
    this.#status = "starting";
    try {
      await this.#ensureWorker();
    } catch (error2) {
      this.#status = "stopped";
      this.#logger.error("worker registration failed", {
        workerId: this.#config.workerId,
        error: errorMessage(error2)
      });
      throw error2;
    }
    this.#status = "running";
    this.#startWorkerHeartbeat();
    this.#startLoop();
    this.#logger.info("worker runtime started", {
      workerId: this.#config.workerId,
      concurrency: this.#concurrency
    });
  }
  /**
   * Gracefully drains the runtime: stops claiming new work, lets in-flight
   * executions finish (or aborts them past `drainTimeoutMs`), then marks the
   * worker `offline`.
   */
  async stop(options = {}) {
    if (this.#status === "stopped") return;
    this.#status = "draining";
    this.#wakeSleepers();
    this.#notifySlot();
    if (options.drainTimeoutMs !== void 0) {
      await this.#drainWithTimeout(options.drainTimeoutMs);
    } else {
      await this.#waitForIdle();
    }
    this.#status = "stopped";
    this.#stopWorkerHeartbeat();
    await this.#transitionWorker("offline");
    this.#wakeSleepers();
    this.#notifySlot();
    this.#logger.info("worker runtime stopped", { workerId: this.#config.workerId });
  }
  // -------------------------------------------------------------------------
  // Worker registration & liveness
  // -------------------------------------------------------------------------
  async #ensureWorker() {
    const nowIso = this.#clock().toISOString();
    let worker = await this.#workerRepo.get(this.#config.workerId);
    if (worker === null) {
      worker = await this.#workerRepo.create({
        workerId: this.#config.workerId,
        workerType: this.#config.workerType,
        status: "idle",
        lastHeartbeatAt: nowIso,
        protocolVersion: this.#config.protocolVersion ?? DEFAULT_PROTOCOL_VERSION,
        capabilities: [...this.#config.capabilities ?? []],
        payload: { runtimeInstanceId: this.#instanceId }
      });
    } else if (worker.status !== "idle") {
      worker = await this.#workerRepo.transition(worker.workerId, "idle", worker.revision);
    }
    this.#worker = worker;
  }
  #startWorkerHeartbeat() {
    if (this.#workerHeartbeatHandle !== null) return;
    this.#workerHeartbeatHandle = this.#timers.setInterval(() => {
      void this.#beatWorker();
    }, this.#config.heartbeatIntervalMs);
  }
  #stopWorkerHeartbeat() {
    if (this.#workerHeartbeatHandle === null) return;
    this.#timers.clearInterval(this.#workerHeartbeatHandle);
    this.#workerHeartbeatHandle = null;
  }
  async #beatWorker() {
    try {
      await this.#serializeWorker(async () => {
        if (this.#worker === null) return;
        this.#worker = await this.#workerRepo.heartbeat(this.#worker.workerId, this.#clock().toISOString());
      });
    } catch (error2) {
      this.#logger.warn("worker heartbeat failed", {
        workerId: this.#config.workerId,
        error: errorMessage(error2)
      });
    }
  }
  /** Serializes worker mutations so revision compare-and-swaps never race. */
  #serializeWorker(task) {
    const run = this.#workerQueue.then(task, task);
    this.#workerQueue = run.then(
      () => void 0,
      () => void 0
    );
    return run;
  }
  async #transitionWorker(next) {
    try {
      await this.#serializeWorker(async () => {
        const current = await this.#workerRepo.get(this.#config.workerId);
        if (current === null) return;
        this.#worker = current;
        if (current.status === next) return;
        if (!canTransitionWorker(current.status, next)) return;
        this.#worker = await this.#workerRepo.transition(current.workerId, next, current.revision);
      });
    } catch (error2) {
      this.#logger.warn("worker state transition failed", {
        workerId: this.#config.workerId,
        next,
        error: errorMessage(error2)
      });
    }
  }
  // -------------------------------------------------------------------------
  // Claim loop
  // -------------------------------------------------------------------------
  #startLoop() {
    if (this.#loopRunning) return;
    this.#loopRunning = true;
    void this.#runLoop().finally(() => {
      this.#loopRunning = false;
    });
  }
  async #runLoop() {
    while (this.#status === "running") {
      if (this.#active.size >= this.#concurrency) {
        await this.#waitSlot();
        continue;
      }
      let claimed = false;
      try {
        claimed = await this.#claimOnce();
      } catch (error2) {
        this.#logger.error("worker claim failed", {
          workerId: this.#config.workerId,
          error: errorMessage(error2)
        });
      }
      if (claimed) continue;
      if (this.#status !== "running") break;
      await this.#sleep(this.#config.pollBackoffMs);
    }
  }
  /** Attempts a single claim; returns `true` when a job was spawned. */
  async #claimOnce() {
    const acquired = await this.#leaseRepo.acquire({
      organizationId: this.#config.organizationId,
      workstreamId: this.#config.workstreamId,
      workerId: this.#config.workerId,
      environmentId: this.#config.environmentId ?? null,
      ttlMs: this.#config.leaseTtlMs,
      now: this.#clock()
    });
    if (acquired === null) return false;
    const workUnit = await this.#workUnitRepo.get(acquired.workUnitId);
    if (workUnit === null) {
      this.#logger.error("claimed work unit not found", { workUnitId: acquired.workUnitId });
      await this.#safeRelease(acquired.lease, "failed");
      return false;
    }
    this.#spawnJob(acquired, workUnit);
    return true;
  }
  #spawnJob(acquired, workUnit) {
    const job = {
      workUnitId: acquired.workUnitId,
      leaseId: acquired.lease.leaseId,
      fencingToken: acquired.lease.fencingToken,
      controller: new AbortController(),
      fenced: false,
      stopRequested: false,
      heartbeatHandle: null
    };
    this.#active.set(job.leaseId, job);
    if (this.#active.size === 1) void this.#transitionWorker("busy");
    void this.#processJob(job, acquired.lease, workUnit);
  }
  // -------------------------------------------------------------------------
  // Execution & lease heartbeat
  // -------------------------------------------------------------------------
  async #processJob(job, lease, workUnit) {
    this.#startLeaseHeartbeat(job);
    try {
      let result = null;
      let failure4 = null;
      try {
        result = await this.#executor.execute(workUnit, job.controller.signal);
      } catch (error2) {
        failure4 = error2;
      }
      this.#stopLeaseHeartbeat(job);
      if (job.fenced) {
        this.#logger.warn("lease fenced: discarding execution outcome", {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          fencingToken: job.fencingToken
        });
        return;
      }
      if (job.stopRequested) {
        await this.#safeRelease(lease, "created");
        return;
      }
      if (failure4 !== null) {
        this.#logger.error("work unit execution failed", {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          error: errorMessage(failure4)
        });
        await this.#safeRelease(lease, "failed");
        return;
      }
      if (result !== null && result.status === "completed") {
        if (result.payloadUpdate !== void 0) {
          await this.#applyPayloadUpdate(workUnit, result.payloadUpdate);
          if (job.fenced) return;
        }
        await this.#safeRelease(lease, "completed");
        return;
      }
      this.#logger.warn("work unit reported a failure result", {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        code: result?.error?.code ?? null
      });
      await this.#safeRelease(lease, "failed");
    } catch (error2) {
      this.#logger.error("worker job processing failed", {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        error: errorMessage(error2)
      });
    } finally {
      this.#stopLeaseHeartbeat(job);
      this.#active.delete(job.leaseId);
      if (this.#active.size === 0 && this.#status === "running") {
        void this.#transitionWorker("idle");
      }
      this.#notifySlot();
    }
  }
  #startLeaseHeartbeat(job) {
    if (job.heartbeatHandle !== null) return;
    job.heartbeatHandle = this.#timers.setInterval(() => {
      void this.#renewLease(job);
    }, this.#config.heartbeatIntervalMs);
  }
  #stopLeaseHeartbeat(job) {
    if (job.heartbeatHandle === null) return;
    this.#timers.clearInterval(job.heartbeatHandle);
    job.heartbeatHandle = null;
  }
  async #renewLease(job) {
    if (job.fenced) return;
    try {
      await this.#leaseRepo.renew({
        organizationId: this.#config.organizationId,
        workstreamId: this.#config.workstreamId,
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        fencingToken: job.fencingToken,
        ttlMs: this.#config.leaseTtlMs,
        now: this.#clock()
      });
    } catch (error2) {
      const code = errorCode(error2);
      if (code !== null && FENCING_ERROR_CODES.has(code)) {
        job.fenced = true;
        this.#stopLeaseHeartbeat(job);
        job.controller.abort(error2 instanceof Error ? error2 : new Error(String(error2)));
        this.#logger.warn("lease heartbeat fenced: aborting execution", {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          code
        });
        return;
      }
      this.#logger.warn("lease heartbeat failed", {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        error: errorMessage(error2)
      });
    }
  }
  async #applyPayloadUpdate(workUnit, payloadUpdate) {
    try {
      await this.#workUnitRepo.update(
        workUnit.workUnitId,
        { payload: { ...workUnit.payload, ...payloadUpdate } },
        workUnit.revision
      );
    } catch (error2) {
      this.#logger.warn("work unit payload update failed", {
        workUnitId: workUnit.workUnitId,
        error: errorMessage(error2)
      });
    }
  }
  /**
   * Releases a lease and swallows protocol errors: a fenced release is the
   * adapter's decision to reject, and it must not crash the loop.
   */
  async #safeRelease(lease, resultStatus) {
    try {
      await this.#leaseRepo.release({
        organizationId: this.#config.organizationId,
        workstreamId: this.#config.workstreamId,
        workUnitId: lease.workUnitId,
        leaseId: lease.leaseId,
        fencingToken: lease.fencingToken,
        resultStatus,
        now: this.#clock()
      });
    } catch (error2) {
      this.#logger.error("lease release failed", {
        workUnitId: lease.workUnitId,
        leaseId: lease.leaseId,
        resultStatus,
        error: errorMessage(error2)
      });
    }
  }
  // -------------------------------------------------------------------------
  // Drain helpers
  // -------------------------------------------------------------------------
  async #waitForIdle() {
    while (this.#active.size > 0) {
      await this.#waitSlot();
    }
  }
  async #drainWithTimeout(timeoutMs) {
    if (this.#active.size === 0) return;
    const drained = await Promise.race([this.#waitForIdle().then(() => true), this.#sleep(timeoutMs).then(() => false)]);
    if (drained) return;
    this.#logger.warn("drain timeout reached: aborting in-flight executions", {
      workerId: this.#config.workerId,
      activeCount: this.#active.size
    });
    for (const job of [...this.#active.values()]) {
      job.stopRequested = true;
      this.#stopLeaseHeartbeat(job);
      job.controller.abort(new Error("DRAIN_TIMEOUT"));
    }
    await this.#waitForIdle();
  }
  #waitSlot() {
    return new Promise((resolve10) => {
      this.#slotWaiters.add(resolve10);
    });
  }
  #notifySlot() {
    const waiters = [...this.#slotWaiters];
    this.#slotWaiters.clear();
    for (const waiter of waiters) waiter();
  }
  #sleep(ms) {
    if (ms <= 0) return Promise.resolve();
    return new Promise((resolve10) => {
      let handle = null;
      let settled = false;
      const wake = () => {
        if (settled) return;
        settled = true;
        this.#sleepers.delete(wake);
        if (handle !== null) this.#timers.clearTimeout(handle);
        resolve10();
      };
      handle = this.#timers.setTimeout(wake, ms);
      this.#sleepers.add(wake);
    });
  }
  #wakeSleepers() {
    for (const wake of [...this.#sleepers]) wake();
  }
};

// ../src/entrypoints/worker-runtime.ts
var DEFAULT_DEMO_DELAY_MS = 50;
var DEFAULT_LEASE_TTL_MS = 3e4;
var DEFAULT_HEARTBEAT_INTERVAL_MS = 1e4;
var DEFAULT_POLL_BACKOFF_MS = 2e3;
function createConsoleWorkerRuntimeLogger(prefix = "[worker-runtime]") {
  const write = (level, message, details) => {
    const line = `${prefix} ${level}: ${message}`;
    if (details === void 0) console[level === "debug" ? "log" : level](line);
    else console[level === "debug" ? "log" : level](line, details);
  };
  return {
    info: (message, details) => write("info", message, details),
    warn: (message, details) => write("warn", message, details),
    error: (message, details) => write("error", message, details),
    debug: (message, details) => write("debug", message, details)
  };
}
function positiveInt(value, fallback) {
  if (value === void 0) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
function sleepWithSignal(ms, signal) {
  return new Promise((resolve10, reject) => {
    if (signal.aborted) {
      reject(signal.reason ?? new Error("ABORTED"));
      return;
    }
    const onAbort = () => {
      clearTimeout(handle);
      reject(signal.reason ?? new Error("ABORTED"));
    };
    const handle = setTimeout(() => {
      signal.removeEventListener("abort", onAbort);
      resolve10();
    }, ms);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}
function createDemoWorkExecutor(options = {}) {
  const delayMs = options.delayMs ?? DEFAULT_DEMO_DELAY_MS;
  const logger = options.logger ?? createConsoleWorkerRuntimeLogger("[worker-runtime:demo-executor]");
  return {
    async execute(workUnit, signal) {
      logger.info("demo executor: start (no real ADW dispatch)", {
        workUnitId: workUnit.workUnitId,
        unitType: workUnit.unitType,
        status: workUnit.status,
        payload: workUnit.payload
      });
      await sleepWithSignal(delayMs, signal);
      logger.info("demo executor: completed", { workUnitId: workUnit.workUnitId });
      return {
        status: "completed",
        payloadUpdate: { executedAt: (/* @__PURE__ */ new Date()).toISOString(), executor: "demo-echo" }
      };
    }
  };
}
async function createLocalWorkerRuntime(options = {}) {
  const logger = options.logger ?? createConsoleWorkerRuntimeLogger();
  const dbConfig = { ...resolveSqlDatabaseConfig(), ...options.dbConfig };
  const client = options.client ?? await createPgPoolClient(dbConfig);
  const organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID;
  const workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID;
  const leaseRepo = createSqlLeaseRepository(client, { organizationId, workstreamId });
  const workUnitRepo = createSqlWorkUnitRepository(client, { organizationId, workstreamId });
  const workerRepo = createSqlWorkerRepository(client, { organizationId });
  const config = {
    organizationId,
    workstreamId,
    workerId: options.workerId ?? process.env.WORKER_ID ?? "local-worker-1",
    workerType: options.workerType ?? "local-demo-worker",
    leaseTtlMs: options.leaseTtlMs ?? positiveInt(process.env.LEASE_TTL_MS, DEFAULT_LEASE_TTL_MS),
    heartbeatIntervalMs: options.heartbeatIntervalMs ?? DEFAULT_HEARTBEAT_INTERVAL_MS,
    pollBackoffMs: options.pollBackoffMs ?? DEFAULT_POLL_BACKOFF_MS,
    concurrency: options.concurrency ?? 1
  };
  if (options.environmentId !== void 0) config.environmentId = options.environmentId;
  if (options.capabilities !== void 0) config.capabilities = [...options.capabilities];
  if (options.protocolVersion !== void 0) config.protocolVersion = options.protocolVersion;
  const executor = options.executor ?? createDemoWorkExecutor({ logger });
  const runtime = new WorkerRuntime(config, {
    leaseRepo,
    workUnitRepo,
    workerRepo,
    executor,
    logger
  });
  return {
    runtime,
    client,
    executor,
    config,
    start: () => runtime.start(),
    stop: (stopOptions) => runtime.stop(stopOptions)
  };
}
async function runLocalWorker(options = {}) {
  const handle = await createLocalWorkerRuntime(options);
  await handle.start();
  return handle;
}
export {
  AGENT_EXECUTION_REFERENCE_SCHEMA_VERSION,
  AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS,
  AGENT_STEP_ATTEMPT_STATUSES,
  AGENT_STEP_ATTEMPT_TERMINAL_STATUSES,
  AGENT_STEP_ATTEMPT_TRANSITIONS,
  AGENT_STEP_RESULT_LIMITS,
  AGENT_STEP_RESULT_STATUSES,
  ARTIFACT_ADMIN_OBJECT_PREFIX,
  ARTIFACT_ADMIN_UPLOAD_PREFIX,
  ARTIFACT_HASH_PREFIX,
  ARTIFACT_RETENTION_DAYS_ENV,
  AgentStepAttemptStore,
  AgentStepResultStore,
  ArtifactAdminError,
  COMMENTS_CHAR_BUDGET,
  DEFAULT_ARTIFACT_RETENTION_DAYS,
  DEFAULT_NAMESPACE_ID,
  DEFAULT_ORGANIZATION_ID,
  DEFAULT_PROCESS_LOCK_FILE,
  DEFAULT_RUN_STORE_POLICY,
  DEFAULT_WORKFLOW_ID,
  DEFAULT_WORKSTREAM_ID,
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
  FilesystemDeliveryRepository,
  FilesystemOracleExecutionRepository,
  FilesystemWorkEnvironmentRepository,
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
  MemoryArtifactStore,
  ORACLE_CATALOG,
  OracleDefinitionRegistry,
  OracleDefinitionRegistryCore,
  PostgresArtifactStore,
  REPO_RUN_STORE_POLICY,
  S3ArtifactStore,
  S3ObjectClient,
  SAFE_FORGE_TICKET_ID,
  STORAGE_FORMAT_VERSION,
  STORAGE_KERNEL_ERROR_CODES,
  STORY_ANALYSIS_PLAN_SCHEMA_VERSION,
  STORY_ANALYSIS_POLICY_VERSION,
  STORY_EDIT_POLICY_VERSION,
  STORY_EDIT_SCHEMA_VERSION,
  STORY_ORACLE_POLICY_VERSION,
  SqlAgentStepAttemptRepository,
  SqlAgentStepResultRepository,
  SqlArtifactMetadataRepository,
  SqlDeliveryRepository,
  SqlLeaseRepository,
  SqlOracleExecutionRepository,
  SqlWorkEnvironmentRepository,
  SqlWorkUnitRepository,
  SqlWorkUnitRepositoryError,
  SqlWorkerRepository,
  SqlWorkerRepositoryError,
  SqlWorkflowDefinitionRepository,
  SqlWorkflowEvidenceRepository,
  SqlWorkflowHumanInteractionRepository,
  SqlWorkflowInstanceRepository,
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
  WorkerRuntime,
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
  buildArtifactMetadata,
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
  collectAndAuditGarbage,
  computeArtifactHash,
  computeCanonicalHash,
  computeForgeSpecHash,
  computeG1EvidenceSetHash,
  computeRetentionStatus,
  computeRetentionUntil,
  computeStorySpecHash,
  countTaskOutcomes,
  createAgentOsHttpCaseTerminator,
  createAgentOsHttpClient,
  createAgentOsRuntimeAdapter,
  createArtifactId,
  createCase,
  createConsoleWorkerRuntimeLogger,
  createDemoWorkExecutor,
  createEpicRun,
  createFilesystemAgentStepAttemptRepository,
  createFilesystemAgentStepResultRepository,
  createFilesystemDeliveryRepository,
  createFilesystemOracleDefinitionSource,
  createFilesystemOracleExecutionRepository,
  createFilesystemWorkEnvironmentRepository,
  createFilesystemWorkflowDefinitionRepository,
  createFilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowHumanInteractionRepository,
  createFilesystemWorkflowInstanceRepository,
  createKeyedLock,
  createLocalWorkerRuntime,
  createMemoryArtifactStore,
  createPgPoolClient,
  createPostgresArtifactStore,
  createRun,
  createS3ArtifactStore,
  createS3ObjectClient,
  createShutdownController,
  createSqlAgentStepAttemptRepository,
  createSqlAgentStepResultRepository,
  createSqlArtifactMetadataRepository,
  createSqlDeliveryRepository,
  createSqlLeaseRepository,
  createSqlOracleExecutionRepository,
  createSqlWorkEnvironmentRepository,
  createSqlWorkUnitRepository,
  createSqlWorkerRepository,
  createSqlWorkflowDefinitionRepository,
  createSqlWorkflowEvidenceRepository,
  createSqlWorkflowHumanInteractionRepository,
  createSqlWorkflowInstanceRepository,
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
  hashVerificationReport,
  hashWorkflowDefinition,
  humanInteractionSemanticHash,
  inspectNxProject,
  installSigtermHandler,
  isAgentStepAttemptStatus,
  isAgentStepAttemptTerminal,
  isAllowedStoryOracleRequestBody,
  isArtifactDestroyable,
  isInfrastructureIdentity,
  isNotFoundError,
  isRetentionActive,
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
  parseJsonColumn,
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
  purgeArtifactAdmin,
  readForgeRunYaml,
  readForgeRunYamlStrict,
  readFormatVersion,
  readJsonLines,
  readSprintStatus,
  readStoryFrontmatter,
  readStorySpec,
  recordHumanDecision,
  refreshArtifactMetadata,
  registerActiveCase,
  resolveBuildHosts,
  resolveDeliveryVerificationRequest,
  resolveForgeRoots,
  resolveFrontOraclePlan,
  resolveOwnerProjectConfigs,
  resolveOwnerProjects,
  resolveSqlDatabaseConfig,
  runAgentTurn,
  runBaselineOracle,
  runCommand,
  runLocalWorker,
  runOneShotImport,
  safeEqual,
  sanitizeForgeSyncAttribution,
  setActiveCaseId,
  setLegalHoldAdmin,
  sha256,
  snapshotDiff,
  startPhase,
  storageErrorCode,
  stripAnsi,
  syncDirectory,
  syncForgeWorkflowProjection,
  toArtifactBytes,
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
  verifyImport,
  withFormatVersion,
  withProcessLock,
  workflowStartCommandHash,
  wrapStorageError,
  writeStoryAnalysisArtifact
};
