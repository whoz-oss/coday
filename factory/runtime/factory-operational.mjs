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

// ../src/ports/agent-runtime-gateway.ts
function asRuntimeExecutionId(value) {
  return value;
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
  const realpath = deps.realpath ?? realpathSync;
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
    } catch (error) {
      return { ok: false, reason: `Unable to list integrations: ${error}`, rootPath: null, integration: null };
    }
    const target = names[0];
    const integration = configs.find((config) => config.name === target);
    let actual = null;
    const declaredRoot = integration?.parameters?.rootPath;
    if (declaredRoot) {
      try {
        actual = realpath(declaredRoot);
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
    } catch (error) {
      return { ok: false, reason: `Unable to list integrations: ${error}`, rootPath: null, integration: null };
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
        canonicalRoot = realpath(rootPath);
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
  return new Promise((resolve) => setTimeout(resolve, ms));
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
  const { safe = false, maximum = 256 } = options;
  if (typeof value !== "string" || !value.trim() || value.length > maximum || safe && !SAFE_ID.test(value))
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

// ../src/infrastructure/storage/storage-kernel.ts
import { createHash as createHash5, randomBytes as randomBytes2 } from "node:crypto";
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
function storageErrorCode(error) {
  const code = error?.code;
  return typeof code === "string" ? code : void 0;
}
function isNotFoundError(error) {
  return storageErrorCode(error) === "ENOENT";
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
  } catch (error) {
    if (isNotFoundError(error)) return [];
    throw error;
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
  return createHash5("sha256").update(canonicalJson(value), "utf8").digest("hex");
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
  } catch (error) {
    if (storageErrorCode(error) === "EEXIST")
      throw new StorageKernelError(STORAGE_KERNEL_ERROR_CODES.LOCK_HELD, { path: lockPath }, error);
    throw error;
  }
  try {
    await handle.writeFile(`${JSON.stringify({ pid: process.pid, acquiredAt: (/* @__PURE__ */ new Date()).toISOString() })}
`, "utf8");
    await handle.sync();
  } catch (error) {
    await handle.close();
    await rm(lockPath, { force: true });
    throw error;
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

// ../src/entrypoints/factory-operational.ts
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
export {
  DEFAULT_PROCESS_LOCK_FILE,
  FilesystemWorkflowDefinitionRepository,
  FilesystemWorkflowEvidenceRepository,
  FilesystemWorkflowHumanInteractionRepository,
  FilesystemWorkflowInstanceRepository,
  HUMAN_INTERACTION_KINDS,
  KeyedLock,
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
  WorkflowDefinitionRepositoryError,
  WorkflowHumanInteractionRepositoryError,
  WorkflowInstanceRepositoryError,
  acquireProcessLock,
  appendDurableJson,
  applyHumanCheckpointOpen,
  applyWorkflowTransition,
  asRuntimeExecutionId,
  assertSupportedFormatVersion,
  atomicTemporaryPath,
  atomicWriteJson,
  bindFactoryStepResult,
  canonicalHumanInteractionInput,
  canonicalJson,
  canonicalize2 as canonicalize,
  canonicalizeWorkflowDefinition,
  clearActiveCaseId,
  computeCanonicalHash,
  createAgentOsHttpCaseTerminator,
  createAgentOsHttpClient,
  createAgentOsRuntimeAdapter,
  createCase,
  createFilesystemWorkflowDefinitionRepository,
  createFilesystemWorkflowEvidenceRepository,
  createFilesystemWorkflowHumanInteractionRepository,
  createFilesystemWorkflowInstanceRepository,
  createKeyedLock,
  createRun,
  createShutdownController,
  createWorkflowEvidence,
  createWorkflowInstance,
  endCurrentRunOnce,
  endRun,
  evaluateHumanCheckpointOpen,
  evaluateHumanResolutionTransition,
  evaluateWorkflowTransition,
  failPhase,
  getActiveCaseId,
  getActiveCaseIds,
  getAgentOsRuntimeAdapter,
  getCase,
  getCurrentRun,
  hashWorkflowDefinition,
  humanInteractionSemanticHash,
  installSigtermHandler,
  isNotFoundError,
  killCase,
  listAgents,
  listEvents,
  listIntegrations,
  openedInteractionRevision,
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
  runAgentTurn,
  setActiveCaseId,
  startPhase,
  storageErrorCode,
  syncDirectory,
  transitionScopeHash,
  transitionSemanticHash,
  unregisterActiveCase,
  validateHumanInteractionOpenInput,
  validateWorkflowDefinition,
  validateWorkflowEvidenceInput,
  validateWorkflowTransitionRequest,
  withFormatVersion,
  withProcessLock,
  workflowStartCommandHash,
  wrapStorageError
};
