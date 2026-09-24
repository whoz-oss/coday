// GENERATED FILE — DO NOT EDIT. Source: factory/src/entrypoints/active-case-contract.ts
var __defProp = Object.defineProperty;
var __export = (target, all) => {
  for (var name in all)
    __defProp(target, name, { get: all[name], enumerable: true });
};

// ../src/lib/active-case.ts
var active_case_exports = {};
__export(active_case_exports, {
  clearActiveCaseId: () => clearActiveCaseId,
  getActiveCaseId: () => getActiveCaseId,
  getActiveCaseIds: () => getActiveCaseIds,
  registerActiveCase: () => registerActiveCase,
  setActiveCaseId: () => setActiveCaseId,
  unregisterActiveCase: () => unregisterActiveCase
});
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

// ../src/entrypoints/active-case-contract.ts
var expectedExports = [
  "clearActiveCaseId",
  "getActiveCaseId",
  "getActiveCaseIds",
  "registerActiveCase",
  "setActiveCaseId",
  "unregisterActiveCase"
];
function assert(condition, message) {
  if (!condition) throw new Error(`active-case contract violation: ${message}`);
}
function verifyActiveCaseContract() {
  assert(
    JSON.stringify(Object.keys(active_case_exports).sort()) === JSON.stringify(expectedExports),
    "unexpected public exports"
  );
  assert(getActiveCaseId() === null, "registry must initially be empty");
  assert(getActiveCaseIds().length === 0, "registry snapshot must initially be empty");
  registerActiveCase("contract-a", "editor");
  registerActiveCase("contract-a", "ignored");
  registerActiveCase("contract-b", "reviewer");
  assert(getActiveCaseId() === "contract-a", "first registered case must remain active");
  assert(
    JSON.stringify(getActiveCaseIds()) === JSON.stringify(["contract-a", "contract-b"]),
    "registration must be ordered and idempotent"
  );
  const snapshot = getActiveCaseIds();
  unregisterActiveCase("contract-a");
  unregisterActiveCase("contract-a");
  assert(snapshot.length === 2, "snapshots must not expose mutable registry state");
  setActiveCaseId("contract-legacy");
  clearActiveCaseId(null);
  clearActiveCaseId("contract-b");
  assert(getActiveCaseId() === "contract-legacy", "legacy API must delegate to the registry");
  clearActiveCaseId("contract-legacy");
  assert(getActiveCaseId() === null, "contract exercise must leave the registry empty");
}
try {
  verifyActiveCaseContract();
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
export {
  clearActiveCaseId,
  getActiveCaseId,
  getActiveCaseIds,
  registerActiveCase,
  setActiveCaseId,
  unregisterActiveCase,
  verifyActiveCaseContract
};
