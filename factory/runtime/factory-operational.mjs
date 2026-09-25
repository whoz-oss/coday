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
  const record = input;
  if (Object.keys(record).some((field) => !DEFINITION_FIELDS.has(field)))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "$", { reason: "unknown_field" });
  if (record.schemaVersion !== WORKFLOW_DEFINITION_SCHEMA_VERSION)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_SCHEMA_VERSION, "schemaVersion");
  const type = text(record.workflowType, "workflowType", { safe: true });
  if (!type.ok) return type;
  const version = record.version;
  if (typeof version !== "string" || !SEMVER.test(version))
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "version");
  const title = text(record.title, "title");
  if (!title.ok) return title;
  let trustedExecution;
  if (record.trustedExecution !== void 0) {
    const rawTrusted = record.trustedExecution;
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
  if (!Array.isArray(record.steps) || record.steps.length === 0 || record.steps.length > 500)
    return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, "steps");
  const ids = /* @__PURE__ */ new Set();
  const steps = [];
  const rawSteps = record.steps;
  for (let index = 0; index < rawSteps.length; index++) {
    const raw = rawSteps[index];
    const base = `steps[${index}]`;
    if (!raw || typeof raw !== "object" || Array.isArray(raw) || Object.keys(raw).some((field) => !STEP_FIELDS.has(field)))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.INVALID_VALUE, base);
    const step = raw;
    const id = text(step.id, `${base}.id`, { safe: true });
    if (!id.ok) return id;
    if (ids.has(id.value))
      return failure(WORKFLOW_DEFINITION_ERROR_CODES.DUPLICATE_STEP_ID, `${base}.id`, { stepId: id.value });
    ids.add(id.value);
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
      id: id.value,
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
  function cyclic(id) {
    if (visiting.has(id)) return true;
    if (visited.has(id)) return false;
    visiting.add(id);
    for (const dependency of graph.get(id) ?? []) if (cyclic(dependency)) return true;
    visiting.delete(id);
    visited.add(id);
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
    const record = value;
    return Object.fromEntries(
      Object.keys(record).sort().map((key) => [key, canonicalizeValue(record[key])])
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
    const record = value;
    return Object.fromEntries(
      Object.keys(record).sort().map((key) => [key, canonicalize(record[key])])
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
    steps: steps.map(({ id, status }) => ({ id, status })),
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
  const record = input;
  if (Object.keys(record).some((key) => !FIELDS.has(key))) return invalidTransitionRequest();
  if (record.requestId !== void 0) return { ok: false, error: { code: "UNTRUSTED_REQUEST_ID" } };
  const workflowId = record.workflowId;
  const stepId = record.stepId;
  const expectedRevision = record.expectedRevision;
  const requestedStatus = record.requestedStatus;
  const evidenceIds = record.evidenceIds;
  const idempotencyKey = record.idempotencyKey;
  if (workflowId !== expectedWorkflowId || !SAFE_ID2.test(String(workflowId ?? "")) || !SAFE_ID2.test(String(stepId ?? "")))
    return invalidTransitionRequest();
  if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1 || !isWorkflowStatus(requestedStatus))
    return invalidTransitionRequest();
  if (!Array.isArray(evidenceIds) || evidenceIds.length > 100 || new Set(evidenceIds).size !== evidenceIds.length || evidenceIds.some((id) => typeof id !== "string" || !SAFE_ID2.test(id)))
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
function transitionScopeHash(namespaceId, request, execution) {
  return createHash3("sha256").update(
    JSON.stringify({
      namespaceId,
      workflowId: request.workflowId,
      stepId: request.stepId,
      source: {
        kind: execution.kind,
        runtimeId: execution.runtimeId,
        agentId: execution.agentId,
        actorId: execution.actorId,
        caseId: execution.caseId,
        threadId: execution.threadId
      },
      idempotencyKey: request.idempotencyKey
    })
  ).digest("hex");
}
function evaluateHumanCheckpointOpen({
  request,
  snapshot,
  definition,
  execution
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
    (id) => instance.steps.find((step) => step.id === id)?.status !== "completed"
  );
  if (missing.length)
    return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", { missingEvidence: missing });
  if (request.requestedStatus !== "waiting_human" || request.evidenceIds.length !== 0)
    return deny("ACTOR_NOT_AUTHORIZED", "human_gate_opener_can_only_open_checkpoint");
  const factoryHumanGate = execution.kind === "factory-human-gate" && execution.runtimeId === "factory-dashboard" && execution.agentId === "factory-runner" && execution.actorId === void 0;
  const controller = instance.controllerExecution ?? snapshot.controllerExecution;
  const originalController = controller && controller.kind === execution.kind && controller.runtimeId === execution.runtimeId && controller.agentId === execution.agentId && controller.caseId === execution.caseId && controller.threadId === execution.threadId;
  if (!factoryHumanGate && !originalController) return deny("ACTOR_NOT_AUTHORIZED", "execution_cannot_open_human_gate");
  return { allowed: true };
}
function evaluateHumanResolutionTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution
}) {
  if (execution.kind === "factory-human-gate" || execution.kind !== "factory-human" || execution.runtimeId !== "factory-dashboard" || typeof execution.actorId !== "string" || execution.actorId.length === 0)
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
      (item) => request.evidenceIds.includes(item.evidenceId) && item.kind === "human-decision" && item.outcome === "pass" && item.source?.kind === "factory-human" && item.source?.actorId === execution.actorId
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
      execution: { ...execution, kind: "factory-human-resolution" }
    });
    return !evaluated.allowed && evaluated.code === "ACTOR_NOT_AUTHORIZED" && evaluated.reason === "runtime_cannot_transition_step_responsibility" ? { allowed: true } : evaluated;
  }
  const completion = evaluateWorkflowTransition({
    request: { ...request, requestedStatus: "failed" },
    snapshot,
    definition,
    evidence,
    execution
  });
  if (!completion.allowed) return completion;
  const selected = request.evidenceIds.map((id) => evidence.find((item) => item.evidenceId === id)).filter((item) => Boolean(item));
  return selected.some(
    (item) => item.kind === "human-decision" && item.outcome === "fail" && item.source?.kind === "factory-human" && item.source?.actorId === execution.actorId
  ) ? { allowed: true } : deny("FAIL_EVIDENCE_REQUIRED", "matching_human_decision_fail_required", {
    missingEvidence: ["human-decision:fail"]
  });
}
function evaluateWorkflowTransition({
  request,
  snapshot,
  definition,
  evidence,
  execution
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
      (id) => instance.steps.find((step) => step.id === id)?.status !== "completed"
    );
    if (missing.length)
      return deny("DEPENDENCIES_NOT_SATISFIED", "dependencies_not_completed", { missingEvidence: missing });
  }
  const factoryOracle = declared.responsibility.kind === "code" && execution.kind === "factory-oracle" && execution.runtimeId === "factory-dashboard";
  const factoryHuman = declared.responsibility.kind === "human" && execution.kind === "factory-human" && execution.runtimeId === "factory-dashboard" && typeof execution.actorId === "string" && execution.actorId.length > 0;
  const factoryRetry = declared.responsibility.kind === "agent" && current.status === "blocked" && request.requestedStatus === "ready" && execution.kind === "factory-control-plane" && execution.runtimeId === "factory-dashboard" && execution.agentId === "factory-runner" && typeof execution.actorId === "string" && execution.actorId.length > 0;
  if (declared.responsibility.kind !== "agent" && !factoryOracle && !factoryHuman)
    return deny("ACTOR_NOT_AUTHORIZED", "runtime_cannot_transition_step_responsibility");
  if (declared.responsibility.kind === "agent" && !factoryRetry && declared.responsibility.name && declared.responsibility.name !== execution.agentId)
    return deny("ACTOR_NOT_AUTHORIZED", "agent_responsibility_mismatch");
  if (factoryHuman && current.status !== "waiting_human") return deny("INTERACTION_STALE", "human_step_is_not_waiting");
  const selected = [];
  for (const id of request.evidenceIds) {
    const item = evidence.find((candidate) => candidate.evidenceId === id);
    if (!item) return deny("EVIDENCE_NOT_FOUND", "evidence_not_found", { missingEvidence: [id] });
    if (item.namespaceId !== execution.namespaceId || item.workflowId !== request.workflowId || item.stepId !== request.stepId)
      return deny("EVIDENCE_SCOPE_MISMATCH", "evidence_scope_mismatch");
    selected.push(item);
  }
  if (request.requestedStatus === "blocked" && declared.responsibility.kind === "agent") {
    const negative = selected.find(
      (item) => item.kind === "agent-result" && ["fail", "indeterminate"].includes(item.outcome ?? "") && item.source?.kind === execution.kind && item.source?.runtimeId === execution.runtimeId && item.source?.agentId === execution.agentId && item.source?.caseId === execution.caseId && item.source?.threadId === execution.threadId
    );
    if (!negative)
      return deny("NEGATIVE_EVIDENCE_REQUIRED", "matching_agent_result_negative_required", {
        missingEvidence: ["agent-result:fail-or-indeterminate"]
      });
  }
  if (request.requestedStatus === "ready" && current.status === "blocked") {
    const controller = instance.controllerExecution ?? snapshot.controllerExecution;
    if (execution.kind !== "factory-control-plane" || execution.runtimeId !== "factory-dashboard" || execution.agentId !== "factory-runner" || typeof execution.actorId !== "string" || execution.actorId.length === 0 || !controller || controller.caseId !== execution.caseId)
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
        (item) => item.kind === "human-decision" && item.outcome === "pass" && item.source?.kind === "factory-human" && item.source?.actorId === execution.actorId
      );
      if (!decision)
        return deny("PASS_EVIDENCE_REQUIRED", "matching_human_decision_required", {
          missingEvidence: ["human-decision:pass"]
        });
    } else if (factoryOracle) {
      const pass = selected.find(
        (item) => item.kind === "oracle-result" && item.outcome === "pass" && item.source?.kind === "factory-oracle" && item.facts?.oracleId === declared.responsibility.name
      );
      if (!pass)
        return deny("PASS_EVIDENCE_REQUIRED", "matching_oracle_result_pass_required", {
          missingEvidence: ["oracle-result:pass"]
        });
    } else {
      if (selected.some((item) => item.kind === "agent-result" && ["fail", "indeterminate"].includes(item.outcome ?? "")))
        return deny("EVIDENCE_NEGATIVE", "agent_result_not_pass");
      const pass = selected.find(
        (item) => item.kind === "agent-result" && item.outcome === "pass" && item.source?.kind === execution.kind && item.source?.runtimeId === execution.runtimeId && item.source?.agentId === execution.agentId && item.source?.caseId === execution.caseId && item.source?.threadId === execution.threadId
      );
      if (!pass)
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
      if (previous.get(step.id) === "pending" && step.dependsOn.every((id) => previous.get(id) === "completed"))
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
  const record = input;
  if (Object.keys(record).some((field) => !INPUT_FIELDS.has(field))) return invalid("$", "unknown_field");
  if (record.workflowId !== expectedWorkflowId || !SAFE_ID3.test(String(record.workflowId ?? "")))
    return invalid("workflowId");
  if (!SAFE_ID3.test(String(record.stepId ?? ""))) return invalid("stepId");
  if (!isEvidenceKind(record.kind)) return invalid("kind");
  if (record.idempotencyKey !== void 0 && !boundedText(record.idempotencyKey, WORKFLOW_EVIDENCE_LIMITS.idempotencyKey))
    return invalid("idempotencyKey");
  if (record.kind === "artifact") {
    if (record.outcome !== void 0 || record.facts !== void 0) return invalid("$", "artifact_fields");
    if (!boundedText(record.artifactRef, WORKFLOW_EVIDENCE_LIMITS.artifactRef)) return invalid("artifactRef");
    if (!HASH.test(String(record.artifactHash ?? ""))) return invalid("artifactHash");
    return {
      ok: true,
      value: {
        workflowId: record.workflowId,
        stepId: record.stepId,
        kind: "artifact",
        artifactRef: record.artifactRef,
        artifactHash: record.artifactHash,
        ...record.idempotencyKey ? { idempotencyKey: record.idempotencyKey } : {}
      }
    };
  }
  if (["oracle-result", "human-decision"].includes(record.kind) && record.outcome === void 0)
    return invalid("outcome");
  if (record.artifactRef !== void 0 || record.artifactHash !== void 0) return invalid("$", "agent_result_fields");
  if (record.outcome !== void 0 && !isEvidenceOutcome(record.outcome)) return invalid("outcome");
  if (!record.facts || typeof record.facts !== "object" || Array.isArray(record.facts)) return invalid("facts");
  const entries = Object.entries(record.facts);
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
      workflowId: record.workflowId,
      stepId: record.stepId,
      kind: record.kind,
      ...record.outcome ? { outcome: record.outcome } : {},
      facts: { ...record.facts },
      ...record.idempotencyKey ? { idempotencyKey: record.idempotencyKey } : {}
    }
  };
}
function createWorkflowEvidence(validated, namespaceId, source, observedAt = (/* @__PURE__ */ new Date()).toISOString(), evidenceId = randomUUID2()) {
  const { idempotencyKey, ...rest } = validated;
  void idempotencyKey;
  const record = {
    evidenceId,
    namespaceId,
    ...rest,
    source: Object.freeze({ ...source }),
    observedAt
  };
  return Object.freeze(record);
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
    const record = value;
    return Object.fromEntries(
      Object.keys(record).sort().map((key) => [key, canonicalHumanInteractionInput(record[key])])
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
  const record = input;
  const rawActions = record.actions;
  const actionsValid = Array.isArray(rawActions) && rawActions.length === 2 && new Set(rawActions.map((action) => action?.id)).size === 2 && rawActions.every((action) => {
    if (!action || typeof action !== "object" || Array.isArray(action)) return false;
    const candidate = action;
    return SAFE_ID4.test(typeof candidate.id === "string" ? candidate.id : "") && typeof candidate.label === "string" && !!candidate.label && candidate.label.length <= 128 && WORKFLOW_STATUSES.includes(candidate.requestedStatus);
  });
  if (!SAFE_ID4.test(String(record.workflowId ?? "")) || !SAFE_ID4.test(String(record.stepId ?? "")) || !KINDS2.has(record.kind) || !Number.isSafeInteger(record.expectedRevision) || record.expectedRevision < 1 || typeof record.prompt !== "string" || !record.prompt || record.prompt.length > 2e3 || !actionsValid || typeof record.idempotencyKey !== "string" || !record.idempotencyKey || record.idempotencyKey.length > 128 || /[\r\n]/.test(record.idempotencyKey) || record.interactionId !== void 0 && !SAFE_ID4.test(String(record.interactionId)))
    return null;
  return {
    workflowId: record.workflowId,
    stepId: record.stepId,
    expectedRevision: record.expectedRevision,
    kind: record.kind,
    prompt: record.prompt,
    actions: rawActions.map((action) => ({
      id: action.id,
      label: action.label,
      requestedStatus: action.requestedStatus
    })),
    idempotencyKey: record.idempotencyKey,
    ...record.interactionId ? { interactionId: record.interactionId } : {},
    ...record.interactionType ? { interactionType: record.interactionType } : {},
    ...record.reasonCode ? { reasonCode: record.reasonCode } : {}
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
  const record = attempt ?? {};
  if (!attempt || typeof attempt !== "object" || !SAFE_ID5.test(String(record.attemptId ?? "")) || !SAFE_ID5.test(String(record.workflowId ?? "")) || !SAFE_ID5.test(String(record.stepId ?? "")) || !SAFE_ID5.test(String(record.namespaceId ?? "")) || typeof record.runtimeId !== "string" || !record.runtimeId || typeof record.agentName !== "string" || !record.agentName || !BRIEF_HASH.test(String(record.briefHash ?? "")) || !Number.isSafeInteger(record.workflowRevisionAtStart) || record.workflowRevisionAtStart < 1 || !Number.isSafeInteger(record.attemptNumber) || record.attemptNumber < 1 || !isAgentStepAttemptStatus(record.status) || !isValidAgentStepAttemptInstant(record.startedAt))
    throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record.caseId !== null && typeof record.caseId !== "string") throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  const terminal = isAgentStepAttemptTerminal(record.status);
  if (terminal !== isValidAgentStepAttemptInstant(record.finishedAt) || !terminal && record.finishedAt !== null)
    throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record.status === "starting" && record.caseId !== null) throw new Error("INVALID_AGENT_STEP_ATTEMPT");
  if (record.status !== "starting" && !record.caseId) throw new Error("INVALID_AGENT_STEP_ATTEMPT");
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
    const record = value;
    return Object.fromEntries(
      Object.keys(record).sort().map((key) => [key, canonicalizeAgentStepResult(record[key])])
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
  const record = value;
  if (!["PASS", "FAIL"].includes(record.status) || typeof record.summary !== "string" || record.summary.length === 0 || record.summary.length > AGENT_STEP_RESULT_LIMITS.summary)
    return false;
  const claims = record.claims;
  if (!claims || typeof claims !== "object" || Array.isArray(claims) || Object.keys(claims).some((key) => key !== "modifiedFiles") || !Array.isArray(claims.modifiedFiles) || claims.modifiedFiles.length > AGENT_STEP_RESULT_LIMITS.modifiedFiles || claims.modifiedFiles.some(
    (file) => typeof file !== "string" || file.length === 0 || file.length > AGENT_STEP_RESULT_LIMITS.modifiedFileLength
  ))
    return false;
  const artifacts = record.artifacts;
  if (artifacts !== void 0 && (!Array.isArray(artifacts) || artifacts.length > AGENT_STEP_RESULT_LIMITS.artifacts || artifacts.some(
    (artifact) => !artifact || typeof artifact !== "object" || Array.isArray(artifact) || Object.keys(artifact).some((key) => !ARTIFACT_FIELDS.has(key)) || typeof artifact.kind !== "string" || artifact.kind.length === 0 || artifact.kind.length > AGENT_STEP_RESULT_LIMITS.artifactKind || artifact.encoding !== "markdown" || typeof artifact.content !== "string" || artifact.content.length === 0 || Buffer.byteLength(artifact.content, "utf8") > AGENT_STEP_RESULT_LIMITS.artifactContentBytes
  )))
    return false;
  const findings = record.findings;
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
  const record = input;
  if (record.schemaVersion !== "1")
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SCHEMA_VERSION, "schemaVersion");
  for (const field of ["environmentId", "workUnitId", "createdBy"]) {
    const result = safe(record[field], field);
    if (!result.ok) return result;
  }
  const workflow = safe(record.workflowId, "workflowId", true);
  if (!workflow.ok) return workflow;
  for (const field of ["businessRef", "businessType"]) {
    const result = safe(record[field], field, true);
    if (!result.ok) return result;
  }
  const namespace = validateNamespaceId(record.namespaceId);
  if (!namespace.ok) return namespace;
  if (record.parentCaseId !== void 0 && !UUID.test(record.parentCaseId))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_UUID, "parentCaseId");
  for (const field of ["repoRoot", "worktreePath"]) {
    const result = validateCanonicalAbsolutePath(record[field], field);
    if (!result.ok) return result;
  }
  if (record.repoRoot === record.worktreePath)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_PATH, "worktreePath");
  for (const field of ["integrationBranch", "branch"]) {
    const result = validateGitRef(record[field], field);
    if (!result.ok) return result;
  }
  if (record.baseCommit !== null && !SHA.test(record.baseCommit ?? ""))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, "baseCommit");
  if (record.baseCommit === null && record.lifecycleState !== "provisioning")
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_SHA, "baseCommit");
  const createdAt = validateIsoInstant(record.createdAt, "createdAt");
  if (!createdAt.ok) return createdAt;
  if (!STATES.has(record.lifecycleState))
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "lifecycleState");
  if (record.lifecycleState === "active" && !record.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "parentCaseId");
  if (record.lifecycleState === "provisioning" && record.parentCaseId)
    return fail(WORK_UNIT_ENVIRONMENT_ERROR_CODES.INVALID_STATE, "parentCaseId");
  const environment = {
    schemaVersion: "1",
    environmentId: record.environmentId,
    workUnitId: record.workUnitId,
    ...record.workflowId ? { workflowId: record.workflowId } : {},
    namespaceId: record.namespaceId,
    ...record.parentCaseId ? { parentCaseId: record.parentCaseId } : {},
    ...record.businessRef ? { businessRef: record.businessRef } : {},
    ...record.businessType ? { businessType: record.businessType } : {},
    repoRoot: record.repoRoot,
    integrationBranch: record.integrationBranch,
    branch: record.branch,
    worktreePath: record.worktreePath,
    baseCommit: record.baseCommit,
    createdAt: record.createdAt,
    createdBy: record.createdBy,
    lifecycleState: record.lifecycleState
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
    const record = value;
    return Object.fromEntries(
      Object.keys(record).sort().map((key) => [key, canonicalize2(record[key])])
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
      const record = {
        type: "capability-issued",
        capabilityId: randomUUID3(),
        tokenHash: sha256(token),
        ...identity,
        issuedAt: now.toISOString(),
        expiresAt: new Date(now.getTime() + this.ttlMs).toISOString(),
        submissionBudget: 1
      };
      const entry = { namespaceId, storageId, event: record, result: null };
      await appendDurableJson(this.path(namespaceId, storageId), record, { ensureDirectory: true });
      this.capabilityIndex.set(record.tokenHash, entry);
      this.attemptIndex.set(key, entry);
      return { token, expiresAt: record.expiresAt };
    });
  }
  async resolve(token) {
    if (typeof token !== "string" || token.length < 32 || token.length > 256) return null;
    await this.initialize();
    const digest2 = sha256(token);
    const located = this.capabilityIndex.get(digest2);
    return located && safeEqual(located.event.tokenHash, digest2) ? located : null;
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
      const response = await fetchImpl(
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
      if (!response.ok) {
        throw new Error(`AgentOS Factory binding rejected with HTTP ${response.status}`);
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
  const realpath3 = deps.realpath ?? realpathSync;
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
        actual = realpath3(declaredRoot);
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
        canonicalRoot = realpath3(rootPath);
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
    allEvents.filter((e) => e.type === "AnswerEvent").map((e) => e.questionId).filter((id) => typeof id === "string")
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
  return new Promise((resolve3) => setTimeout(resolve3, ms));
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
    const match = fence[0];
    if (!match) return null;
    const outside = (text2.slice(0, match.index) + text2.slice(match.index + match[0].length)).trim();
    if (/[{}]/.test(outside)) return null;
    return match[1]?.trim() ?? null;
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
  const record = value;
  const claims = record.claims;
  if (!["PASS", "FAIL"].includes(record.status) || typeof record.summary !== "string" || !claims || !Array.isArray(claims.modifiedFiles))
    return { ok: false, code: "RESULT_SCHEMA_INVALID" };
  if (record.artifacts !== void 0 && !Array.isArray(record.artifacts))
    return { ok: false, code: "RESULT_SCHEMA_INVALID" };
  return { ok: true, value: record };
}
async function verifyArtifacts(result, repoRoot, expectedKind) {
  const artifacts = result.artifacts ?? [];
  if (expectedKind && !artifacts.some((a) => a.kind === expectedKind))
    return { ok: false, code: "EXPECTED_ARTIFACT_MISSING" };
  const verified = [];
  for (const artifact of artifacts) {
    if (isAbsolute2(artifact.path)) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    const absolute = resolve2(repoRoot, artifact.path);
    if (relative(repoRoot, absolute).startsWith("..")) return { ok: false, code: "ARTIFACT_OUT_OF_SCOPE" };
    try {
      if (!(await stat(absolute)).isFile()) return { ok: false, code: "ARTIFACT_NOT_FILE" };
      const content = await readFile2(absolute);
      verified.push({ ...artifact, hash: sha256(content) });
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
  const artifact = artifacts[0];
  if (!artifact || typeof artifact !== "object" || Array.isArray(artifact))
    return { ok: false, code: "INLINE_ARTIFACT_SCHEMA_INVALID" };
  if ("path" in artifact) return { ok: false, code: "ARTIFACT_PATH_FORBIDDEN" };
  if (Object.keys(artifact).some((key) => !["kind", "encoding", "content"].includes(key)))
    return { ok: false, code: "INLINE_ARTIFACT_SCHEMA_INVALID" };
  if (artifact.kind !== expectedKind) return { ok: false, code: "ARTIFACT_KIND_MISMATCH" };
  if (artifact.encoding !== "markdown") return { ok: false, code: "ARTIFACT_ENCODING_INVALID" };
  if (typeof artifact.content !== "string" || artifact.content.length === 0)
    return { ok: false, code: "ARTIFACT_CONTENT_EMPTY" };
  const content = Buffer.from(artifact.content, "utf8");
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
  const execution = { kind: "agentos", runtimeId, agentId: agentName, caseId: caseRecord.id, threadId: null };
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
  const running = await projectionStore.transition(namespaceId, runningRequest.value, definition, [], execution);
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
  const evidenceSource = { ...execution };
  const artifactEvidence = [];
  if (!failureCode && input.stepId === "technical-review") {
    if (!Array.isArray(result.findings) || result.findings.length > 0)
      failureCode = "REVIEW_SCHEMA_INVALID";
  }
  for (const artifact of artifacts) {
    const validated = validateWorkflowEvidenceInput(
      {
        workflowId,
        stepId: input.stepId,
        kind: "artifact",
        artifactRef: artifact.path,
        artifactHash: artifact.hash,
        idempotencyKey: artifactEvidenceIdempotencyKey(attemptId, artifact.path)
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
    const blocked = validateWorkflowTransitionRequest(
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
    if (!blocked.ok)
      return {
        ok: false,
        code: failureCode,
        reconciliationCode: "INVALID_NEGATIVE_TRANSITION",
        attempt: finished,
        evidence: resultEvidence
      };
    const transition2 = await projectionStore.transition(
      namespaceId,
      blocked.value,
      definition,
      [resultEvidence],
      execution
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
    execution
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
  get(id) {
    return this.items.get(id) ?? null;
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
  const record = hostMap;
  const fallbackHosts = Array.isArray(record["*"]) ? record["*"] : [];
  const seen = /* @__PURE__ */ new Set();
  const hosts = [];
  for (const owner of ownerProjects) {
    const mapped = Array.isArray(record[owner]) ? record[owner] : fallbackHosts;
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
  return new Promise((resolve3) => {
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
      resolve3({ ...base, ...classifyOracleExecution(definition, base) });
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
    const id = normalizeDiagnosticLine(line);
    if (id !== null) identities.push(id);
  }
  const seen = /* @__PURE__ */ new Set();
  const uniqueIdentities = [];
  for (const id of identities) {
    if (!seen.has(id)) {
      seen.add(id);
      uniqueIdentities.push(id);
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
  const productIdentities = postEditIdentities.filter((id) => !isInfrastructureIdentity(id));
  if (infraIdentities.length > 0 && productIdentities.length === 0) {
    return {
      classification: "ORACLE_INFRASTRUCTURE",
      reason: `Only TypeScript infrastructure/config error codes found: ${infraIdentities.map((id) => id.split(":")[1]).join(", ")}. Not a product regression.`,
      baselineIdentities,
      postEditIdentities,
      newDiagnostics: infraIdentities,
      preExistingDiagnostics: [],
      newDiagnosticLines: postEditRawLines,
      baselinePassed,
      postEditPassed: false
    };
  }
  const newProductDiagnostics = productIdentities.filter((id) => !baselineSet.has(id));
  const preExistingProductDiagnostics = productIdentities.filter((id) => baselineSet.has(id));
  const newInfraDiagnostics = infraIdentities.filter((id) => !baselineSet.has(id));
  const newDiagnostics = [...newProductDiagnostics, ...newInfraDiagnostics];
  const preExistingDiagnostics = [
    ...preExistingProductDiagnostics,
    ...infraIdentities.filter((id) => baselineSet.has(id))
  ];
  const newDiagnosticLines = postEditRawLines.filter((line) => {
    const id = normalizeDiagnosticLine(line);
    return id !== null && newDiagnostics.includes(id);
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
    const allNewInScope = newProductDiagnostics.every((id) => {
      if (id.startsWith("TS:")) {
        const parts = id.split(":");
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
    const canonical = await realpath2(path);
    if (path !== this.root && !contained(this.root, canonical))
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
  paths(ns, id) {
    this._namespace(ns);
    if (!this.root || typeof id !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(id))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT);
    const directory = join9(this.root, ns, digest(`${ns}:${id}`));
    if (!contained(this.root, directory))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.INVALID_ENVIRONMENT);
    return {
      directory,
      snapshot: join9(directory, "environment.json"),
      events: join9(directory, "events.jsonl"),
      pending: join9(directory, "pending.json")
    };
  }
  _locked(ns, id, fn) {
    this._namespace(ns);
    const k = `${ns}\0${id}`;
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
  async _recover(p, ns, id) {
    const q = await this._json(p.pending);
    if (!q) return;
    const valid = q && Number.isSafeInteger(q.revision) && q.revision > 0 && q.environment?.namespaceId === ns && q.environment?.environmentId === id && validateWorkUnitEnvironment(q.environment).ok && q.environmentHash === snapshotHash(q.environment);
    if (!valid)
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "pending" });
    let facts;
    try {
      facts = (await readFile4(p.events, "utf8")).split("\n").filter(Boolean).map((line) => JSON.parse(line));
    } catch (e) {
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, { artifact: "journal" }, e);
    }
    if (!facts.some(
      (f) => f.revision === q.revision && f.environmentId === id && f.environmentHash === q.environmentHash
    ))
      throw new WorkUnitEnvironmentStoreError(ENVIRONMENT_STORE_ERROR_CODES.CORRUPT_STORAGE, {
        artifact: "recovery_binding"
      });
    await atomic(p.snapshot, q);
    await rm3(p.pending);
    await syncDir(p.directory);
  }
  async read(ns, id) {
    this._namespace(ns);
    const p = this.paths(ns, id);
    await this._guard(p);
    await this._recover(p, ns, id);
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
  async transition(ns, id, next, { expectedRevision, errorCode } = {}) {
    this._namespace(ns);
    return this._locked(ns, id, async () => {
      const c = await this.read(ns, id);
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
var sameIdentity = (environment, input, id) => environment.environmentId === id && environment.workflowId === (input.workflowId ?? environment.workflowId) && environment.workUnitId === input.workUnitId && environment.namespaceId === input.namespaceId && environment.repoRoot === input.repoRoot && environment.integrationBranch === input.integrationBranch && environment.branch === input.branch && environment.worktreePath === input.worktreePath && environment.createdBy === input.createdBy;
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
  _locked(ns, id, fn) {
    const k = `${ns}\0${id}`;
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
    const id = input.environmentId ?? this.idGenerator();
    return this._locked(input.namespaceId, id, () => this._provision(input, id));
  }
  async _provision(input, id) {
    let current = await this.store.read(input.namespaceId, id);
    if (current) {
      const e = current.environment;
      if (!sameIdentity(e, input, id)) return { ok: false, error: { code: "ENVIRONMENT_IDENTITY_CONFLICT" } };
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
            environmentId: id,
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
      await this.fault("after-git-add", { namespaceId: input.namespaceId, environmentId: id });
      current = await this.store.read(input.namespaceId, id);
      if (current.environment.baseCommit !== facts.baseCommit)
        return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" } };
      return { ok: true, changed: false, snapshot: current, headCommit: facts.headCommit };
    } catch (error2) {
      current = await this.store.read(input.namespaceId, id);
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
          id,
          { ...current.environment, lifecycleState: "error" },
          { expectedRevision: current.revision, errorCode: machine(error2) }
        );
      }
      throw error2;
    }
  }
  async bindParentCase(ns, id, caseId) {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id);
      if (c?.environment.lifecycleState === "active" && c.environment.parentCaseId === caseId)
        return { ok: true, changed: false, snapshot: c };
      if (!c || c.environment.lifecycleState !== "provisioning" || !c.environment.baseCommit)
        return { ok: false, error: { code: "INVALID_BIND" } };
      const existing = await this.store.list(ns, { states: ["active"] });
      if (existing.some(
        (snapshot) => snapshot.environment.environmentId !== id && snapshot.environment.parentCaseId === caseId
      ))
        return { ok: false, error: { code: "WRITER_ALREADY_ACTIVE" } };
      if (existing.some(
        (snapshot) => snapshot.environment.environmentId !== id && snapshot.environment.worktreePath === c.environment.worktreePath
      ))
        return { ok: false, error: { code: "WORKTREE_ALREADY_ACTIVE" } };
      return this.store.transition(
        ns,
        id,
        { ...c.environment, parentCaseId: caseId, lifecycleState: "active" },
        { expectedRevision: c.revision }
      );
    });
  }
  async inspect(ns, id) {
    return this._locked(ns, id, async () => {
      const snapshot = await this.store.read(ns, id);
      if (!snapshot) return { ok: false, error: { code: "NOT_FOUND" } };
      if (snapshot.environment.lifecycleState === "removed")
        return { ok: true, snapshot, reconciliation: { status: "absent" } };
      const reconciliation = await this.git.reconcile(snapshot.environment);
      if (reconciliation.status !== "owned")
        return { ok: false, error: { code: "OWNERSHIP_UNCERTAIN" }, snapshot, reconciliation };
      return { ok: true, snapshot, reconciliation };
    });
  }
  async setState(ns, id, state) {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id);
      return c ? this.store.transition(ns, id, { ...c.environment, lifecycleState: state }, { expectedRevision: c.revision }) : { ok: false, error: { code: "NOT_FOUND" } };
    });
  }
  async remove(ns, id) {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id);
      if (!c) return { ok: false, error: { code: "NOT_FOUND" } };
      if (c.environment.lifecycleState === "removed") return { ok: true, changed: false, snapshot: c };
      if (!["completed", "abandoned", "error"].includes(c.environment.lifecycleState))
        return { ok: false, error: { code: "INVALID_REMOVE" } };
      await this.git.removeWorktree(c.environment);
      return this.store.transition(
        ns,
        id,
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
  constructor({
    store,
    git,
    policy,
    workflowStore,
    clock,
    idGenerator,
    fault
  }) {
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
    const ref = workflow?.instance?.environmentRef;
    if (!ref) return { ok: false, status: 404, error: { code: "ENVIRONMENT_NOT_FOUND" } };
    const snapshot = await this.store.read(namespaceId, ref.environmentId);
    if (!snapshot || snapshot.environmentHash !== ref.environmentHash)
      return { ok: false, status: 409, error: { code: "ENVIRONMENT_BINDING_UNCERTAIN" } };
    const result = await this.service.inspect(namespaceId, ref.environmentId);
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
  const release = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/release$/);
  const detail = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment$/);
  if (!provision && !reconcile && !release && !detail) return false;
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
    const workflowId = decodeURIComponent((reconcile ?? release ?? detail)[1]);
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
    if (release && method === "POST") {
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
export {
  AGENT_STEP_ATTEMPT_IMMUTABLE_FIELDS,
  AGENT_STEP_ATTEMPT_STATUSES,
  AGENT_STEP_ATTEMPT_TERMINAL_STATUSES,
  AGENT_STEP_ATTEMPT_TRANSITIONS,
  AGENT_STEP_RESULT_LIMITS,
  AGENT_STEP_RESULT_STATUSES,
  AgentStepAttemptStore,
  AgentStepResultStore,
  DEFAULT_PROCESS_LOCK_FILE,
  ENVIRONMENT_STORE_ERROR_CODES,
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  HUMAN_INTERACTION_KINDS,
  KeyedLock,
  OracleDefinitionRegistry,
  OracleDefinitionRegistryCore,
  STORAGE_FORMAT_VERSION,
  STORAGE_KERNEL_ERROR_CODES,
  StorageKernelError,
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
  agentStepAttemptKey,
  appendDurableJson,
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
  countTaskOutcomes,
  createAgentOsHttpCaseTerminator,
  createAgentOsHttpClient,
  createAgentOsRuntimeAdapter,
  createCase,
  createFilesystemOracleDefinitionSource,
  createFilesystemWorkflowDefinitionRepository,
  createFilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowHumanInteractionRepository,
  createFilesystemWorkflowInstanceRepository,
  createKeyedLock,
  createRun,
  createShutdownController,
  createWorkflowEvidence,
  createWorkflowInstance,
  diffSince,
  diffSnapshots,
  endCurrentRunOnce,
  endRun,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  evaluateWorkflowTransition,
  executeAgentStepAttempt,
  executeOracle,
  extractOracleDiagnostics,
  failPhase,
  getActiveCaseId,
  getActiveCaseIds,
  getAgentOsRuntimeAdapter,
  getCase,
  getCurrentRun,
  handleWorkUnitEnvironmentRequest,
  hashAgentBrief,
  hashAgentStepResult,
  hashOracleDefinition,
  hashStructuredAgentResult,
  hashWorkflowDefinition,
  humanInteractionSemanticHash,
  installSigtermHandler,
  isAgentStepAttemptStatus,
  isAgentStepAttemptTerminal,
  isInfrastructureIdentity,
  isNotFoundError,
  isSafeAgentStepResultId,
  isValidAgentStepAttemptInstant,
  killCase,
  listAgents,
  listEvents,
  listIntegrations,
  materializeInlineArtifact,
  normalizeDiagnosticLine,
  openedInteractionRevision,
  oracleArtifact,
  oracleRootIdentity,
  parseAgentStepResult,
  passPhase,
  postMessage,
  preflightAgent,
  preflightReadOnlyWorkspace,
  preflightWorkspace,
  preflightWritableWorkspace,
  processExit,
  readFormatVersion,
  readJsonLines,
  registerActiveCase,
  resolveBuildHosts,
  resolveOwnerProjects,
  runAgentTurn,
  runBaselineOracle,
  runCommand,
  safeEqual,
  setActiveCaseId,
  sha256,
  snapshotDiff,
  startPhase,
  storageErrorCode,
  stripAnsi,
  syncDirectory,
  transitionScopeHash,
  transitionSemanticHash,
  unregisterActiveCase,
  validateAgentStepAttempt,
  validateAgentStepResultBusiness,
  validateCanonicalAbsolutePath,
  validateGitRef,
  validateHumanInteractionOpenInput,
  validateIsoInstant,
  validateNamespaceId,
  validateOracleDefinition,
  validateOracleRoot,
  validateWorkUnitEnvironment,
  validateWorkflowDefinition,
  validateWorkflowEvidenceInput,
  validateWorkflowTransitionRequest,
  withFormatVersion,
  withProcessLock,
  workflowStartCommandHash,
  wrapStorageError
};
