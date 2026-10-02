import { RecentTask, Sandbox, SessionDetail } from './models'

/** Données de démo reprises des captures — à remplacer par l'API / SSE. */

const sdlcPhases = (plan: number, build: number) => [
  { key: 'request', ratio: 0.01, tone: 'amber' as const, status: 'done' as const },
  { key: 'plan', ratio: plan, tone: 'violet' as const, status: 'done' as const },
  { key: 'code', ratio: 0.01, tone: 'green' as const, status: 'done' as const },
  { key: 'build', ratio: build, tone: 'cyan' as const, status: 'running' as const },
]

export const SANDBOXES: Sandbox[] = [
  {
    name: 'coday-agentos-execution-adapter-03b2',
    project: 'coday',
    branch: 'integration/agentos-bridge',
    roster: 'default',
    status: 'working',
    wave: 'vague 2 · agentos-execution-adapter',
    archayCostUsd: 0,
    run: {
      id: '872641a8',
      workflow: 'adw_simple_sdlc',
      status: 'running',
      currentPhase: 'build',
      goal: '@Archay Goal: Implement explicit AgentOS Execution Adapter boundary & robust SSE Client in factory-service without disabling…',
      costUsd: 1.0723,
      durationSec: 732,
      tokens: 606_500,
      phases: sdlcPhases(0.28, 0.7),
    },
  },
  {
    name: 'coday-factory-attempt-durability-22df',
    project: 'coday',
    branch: 'integration/agentos-bridge',
    roster: 'default',
    status: 'working',
    wave: 'vague 2 · factory-attempt-durability',
    archayCostUsd: 0,
    run: {
      id: 'c5f49c91',
      workflow: 'adw_simple_sdlc',
      status: 'running',
      currentPhase: 'build',
      goal: "Modélisation durable de la tentative d'exécution côté factory-service (Lot C durable-execution)",
      costUsd: 0.9944,
      durationSec: 729,
      tokens: 533_700,
      phases: sdlcPhases(0.31, 0.67),
    },
  },
  ...(
    [
      ['coday-bridge-host-wiring-and-durability-66c8', 1.3221],
      ['coday-agentos-sse-contract-scout-ba73', 1.413],
      ['coday-sse-truth-after-commit-5e9a', 0.8898],
      ['coday-sse-truth-after-commit-197e', 0],
      ['coday-cockpit-convergence-4d93', 0.5579],
      ['coday-tx-boundaries-62c7', 1.1901],
    ] as const
  ).map(
    ([name, cost]): Sandbox => ({
      name,
      project: 'coday',
      roster: 'default',
      status: 'destroyed',
      archayCostUsd: 0,
      finalCostUsd: cost,
    })
  ),
]

export const RECENT_TASKS: RecentTask[] = SANDBOXES.filter((s) => s.status === 'destroyed').map((s) => ({
  title: `teardown ${s.name}`,
  log: `==> removed container sbx-${s.name}`,
  costUsd: s.finalCostUsd ?? 0,
}))

export const SESSION_872641A8: SessionDetail = {
  id: '872641a8',
  sandbox: 'coday-agentos-execution-adapter-03b2',
  goal: '@Archay Goal: Implement explicit AgentOS Execution Adapter boundary & robust SSE Client in factory-service',
  status: 'running',
  startedAt: '2026-09-30T18:08:22+02:00',
  workflow: 'adw_simple_sdlc',
  costUsd: 1.07,
  durationSec: 856,
  tokens: 606_500,
  tokensRead: 73_500,
  tokensWritten: 17_900,
  nowSec: 856,
  steps: [
    { key: 'request', tone: 'amber', status: 'done', durationSec: 0 },
    { key: 'plan', tone: 'violet', status: 'done', durationSec: 236 },
    { key: 'workspace', tone: 'green', status: 'done' },
    { key: 'build', tone: 'cyan', status: 'running', durationSec: 619 },
  ],
  lanes: [
    {
      id: 'human',
      label: 'benjamin.valdes',
      subtitle: 'engineer',
      kind: 'human',
      tone: 'amber',
      blocks: [],
      request: { label: 'request', description: 'Capture the incoming ask', startSec: 0, endSec: 0, status: 'done' },
    },
    {
      id: 'code',
      label: 'code',
      subtitle: 'workspace',
      kind: 'workspace',
      tone: 'cyan',
      blocks: [{ label: 'workspace', startSec: 229, endSec: 237, status: 'done' }],
    },
    {
      id: 'planner',
      label: 'planner',
      subtitle: 'claude-opus-4-8',
      kind: 'agent',
      tone: 'violet',
      contextPct: 7,
      blocks: [
        {
          label: 'plan',
          description: 'Turn the request into an implementable plan',
          startSec: 0,
          endSec: 236,
          status: 'done',
          ticksSec: [6, 12, 18, 24, 30, 90, 180, 230],
          errorTicksSec: [48, 52],
        },
      ],
    },
    {
      id: 'builder',
      label: 'builder',
      subtitle: 'kimi-k3',
      kind: 'agent',
      tone: 'cyan',
      blocks: [
        {
          label: 'build',
          description: 'Implement the plan exactly',
          startSec: 237,
          endSec: 856,
          status: 'running',
          ticksSec: [252, 260, 268, 276, 284, 292, 780, 840],
        },
      ],
    },
  ],
  phase: {
    name: 'build',
    status: 'running',
    durationSec: 619,
    owner: 'builder',
    kind: 'agent',
    attempt: '0/0',
    sections: [
      { label: "Configuration de l'agent" },
      { label: 'Description' },
      { label: 'Prompts compilés', count: 2 },
      { label: 'Gates', count: 0 },
      { label: 'Sorties', count: 0 },
    ],
  },
  events: [
    { time: '18:12:19', type: 'phase_start', text: 'build' },
    { time: '18:12:19', type: 'agent_start', text: 'builder' },
    {
      time: '18:12:27',
      type: 'thinking',
      text: 'Let me start by reading the plan and spec files, and exploring the factory-service…',
    },
    {
      time: '18:12:27',
      type: 'tool_call',
      tool: 'read',
      text: '/work/data/sessions/872641a8/context_handoff/plan.md',
      durationSec: 0.01,
    },
    {
      time: '18:12:27',
      type: 'tool_call',
      tool: 'read',
      text: '/work/app/specs/872641a8_agentos_execution_adapter_sse.md',
      durationSec: 0.01,
    },
    {
      time: '18:12:36',
      type: 'tool_call',
      tool: 'bash',
      text: 'find /work/app -type d -name factory-service | head',
      durationSec: 0.47,
    },
    {
      time: '18:12:43',
      type: 'tool_call',
      tool: 'ls',
      text: '/work/app/factory-service/src/main/kotlin/io/whozoss/factory',
      durationSec: 0,
    },
    {
      time: '18:13:02',
      type: 'thinking',
      text: 'Now let me read the app_docs (SSE contract), and check the build setup…',
    },
    {
      time: '18:13:02',
      type: 'tool_call',
      tool: 'read',
      text: '/work/app/factory-service/build.gradle.kts',
      durationSec: 0.01,
    },
    {
      time: '18:13:13',
      type: 'tool_call',
      tool: 'read',
      text: '/work/app/app_docs/agentos-sse-contract.md',
      durationSec: 0,
    },
    {
      time: '18:21:18',
      type: 'agent_message',
      text: 'I have full context now. Let me check the Nx/Gradle wiring for running factory-service…',
    },
    {
      time: '18:22:14',
      type: 'agent_message',
      text: "Now I'll implement the new package. Starting with the main source files.",
    },
    {
      time: '18:22:14',
      type: 'tool_call',
      tool: 'write',
      text: '/work/app/factory-service/src/main/kotlin/io/whozoss/factory/adapter/…',
      durationSec: 0,
    },
  ],
}
