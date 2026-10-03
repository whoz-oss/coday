import { Injectable } from '@angular/core'
import { Observable, of } from 'rxjs'
import {
  AllowedActionDto,
  ControllerHistoryDto,
  HumanActionId,
  HumanActionRequiredDto,
  PlanChangeProposalDto,
  StepAttemptDto,
  StepLane,
  WorkflowActionsResponseDto,
  WorkflowBlockerDto,
  WorkflowDetailDto,
  WorkflowListDto,
  WorkstreamDto,
} from '../models/workstream.model'

/**
 * Mock data service backing the Workstream Cockpit scaffold (Phase 11 prep).
 *
 * Every method returns in-memory fixtures via `of(...)` so views can later swap
 * to real HTTP with minimal change. Methods return the UNWRAPPED `data` payload;
 * the real Factory envelope is `{ data: ... }` on success and
 * `{ error: { code, message, details } }` on error (Phase 0 doc §2.3, §7) —
 * Phase 6 wiring must unwrap it.
 *
 * Each method carries a `TODO(Phase 6)` marker naming the real endpoint/tool it
 * will call. NO real HTTP is performed here.
 */

/** Freshness anchor displayed by the cockpit "as of" badge (mock data). */
export const MOCK_AS_OF = '2026-10-03T09:30:00.000Z'

const MOCK_WORKSTREAM: WorkstreamDto = {
  workstreamId: 'ws-demo',
  organizationId: 'org-whoz',
  name: 'Coday Platform Evolution',
  status: 'active',
  revision: 7,
}

const MOCK_WORKFLOW_LIST: WorkflowListDto = {
  items: [
    {
      workflowId: 'wf-101',
      workflowType: 'feature-delivery',
      title: 'Implement cockpit v2 lanes',
      status: 'running',
      revision: 12,
    },
    {
      workflowId: 'wf-102',
      workflowType: 'feature-delivery',
      title: 'Migrate postgres adapters',
      status: 'waiting_human',
      revision: 5,
    },
    {
      workflowId: 'wf-103',
      workflowType: 'bugfix',
      title: 'Fix SSE error handling',
      status: 'blocked',
      revision: 3,
    },
  ],
  nextCursor: null,
}

const DEFAULT_WORKFLOW_DETAIL: WorkflowDetailDto = {
  state: 'existing',
  workflowId: 'wf-101',
  revision: 12,
  workflowType: 'feature-delivery',
  status: 'running',
  steps: [
    { stepId: 'step-spec', status: 'completed', revision: 4 },
    { stepId: 'step-design', status: 'completed', revision: 3 },
    { stepId: 'step-implement', status: 'running', revision: 6 },
    { stepId: 'step-verify', status: 'pending', revision: 1 },
    { stepId: 'step-review', status: 'waiting_human', revision: 2 },
  ],
  blockers: [
    {
      code: 'WAITING_HUMAN_INTERACTION',
      stepId: 'step-review',
      message: 'Design review checkpoint is waiting for a human decision.',
    },
    {
      code: 'UNKNOWN_RUNTIME',
      stepId: 'step-implement',
      message: 'Attempt heartbeat lost; runtime state is indeterminate pending reconciliation.',
    },
  ],
}

const MOCK_WORKFLOW_DETAILS: Record<string, WorkflowDetailDto> = {
  'wf-101': DEFAULT_WORKFLOW_DETAIL,
  'wf-102': {
    state: 'existing',
    workflowId: 'wf-102',
    revision: 5,
    workflowType: 'feature-delivery',
    status: 'waiting_human',
    steps: [
      { stepId: 'step-scan', status: 'completed', revision: 2 },
      { stepId: 'step-migrate', status: 'blocked', revision: 3 },
      { stepId: 'step-approve', status: 'waiting_human', revision: 1 },
    ],
    blockers: [
      {
        code: 'STEP_BLOCKED',
        stepId: 'step-migrate',
        message: 'Migration step is blocked: upstream schema change not approved.',
      },
      {
        code: 'WAITING_HUMAN_INTERACTION',
        stepId: 'step-approve',
        message: 'Schema change approval required from the data owner.',
      },
    ],
  },
  'wf-103': {
    state: 'existing',
    workflowId: 'wf-103',
    revision: 3,
    workflowType: 'bugfix',
    status: 'blocked',
    steps: [
      { stepId: 'step-reproduce', status: 'completed', revision: 2 },
      { stepId: 'step-fix', status: 'failed', revision: 4 },
      { stepId: 'step-regression-test', status: 'pending', revision: 1 },
    ],
    blockers: [
      {
        code: 'ATTEMPT_FAILED',
        stepId: 'step-fix',
        message: 'Latest attempt failed with SSE_BRIDGE_TIMEOUT; retry is available.',
      },
    ],
  },
}

const MOCK_STEP_ATTEMPTS: Record<string, StepAttemptDto[]> = {
  'wf-101/step-design': [
    {
      attemptId: 'att-201',
      stepId: 'step-design',
      attemptNumber: 1,
      agentName: 'ProductEngineer',
      status: 'succeeded',
      caseId: 'case-881',
      failureCode: null,
      resultEvidenceId: 'ev-88',
      revision: 2,
      createdAt: '2026-10-02T14:05:00.000Z',
      startedAt: '2026-10-02T14:05:12.000Z',
      completedAt: '2026-10-02T14:31:40.000Z',
    },
  ],
  'wf-101/step-implement': [
    {
      attemptId: 'att-301',
      stepId: 'step-implement',
      attemptNumber: 1,
      agentName: 'SweAgent',
      status: 'failed',
      caseId: 'case-902',
      failureCode: 'TRANSIENT_INFRA',
      resultEvidenceId: null,
      revision: 3,
      createdAt: '2026-10-02T16:00:00.000Z',
      startedAt: '2026-10-02T16:00:08.000Z',
      completedAt: '2026-10-02T16:20:51.000Z',
    },
    {
      attemptId: 'att-302',
      stepId: 'step-implement',
      attemptNumber: 2,
      agentName: 'SweAgent',
      status: 'indeterminate',
      caseId: 'case-917',
      failureCode: null,
      resultEvidenceId: null,
      revision: 1,
      createdAt: '2026-10-03T07:12:00.000Z',
      startedAt: '2026-10-03T07:12:05.000Z',
      completedAt: null,
    },
    {
      attemptId: 'att-303',
      stepId: 'step-implement',
      attemptNumber: 3,
      agentName: 'SweAgent',
      status: 'running',
      caseId: 'case-931',
      failureCode: null,
      resultEvidenceId: null,
      revision: 2,
      createdAt: '2026-10-03T08:40:00.000Z',
      startedAt: '2026-10-03T08:40:11.000Z',
      completedAt: null,
    },
  ],
  'wf-101/step-review': [
    {
      attemptId: 'att-401',
      stepId: 'step-review',
      attemptNumber: 1,
      agentName: 'Reviewer',
      status: 'waiting_human',
      caseId: 'case-944',
      failureCode: null,
      resultEvidenceId: null,
      revision: 2,
      createdAt: '2026-10-03T09:02:00.000Z',
      startedAt: '2026-10-03T09:02:04.000Z',
      completedAt: null,
    },
  ],
  'wf-102/step-migrate': [
    {
      attemptId: 'att-501',
      stepId: 'step-migrate',
      attemptNumber: 1,
      agentName: 'DataMigrator',
      status: 'pending',
      caseId: 'case-955',
      failureCode: null,
      resultEvidenceId: null,
      revision: 1,
      createdAt: '2026-10-03T08:00:00.000Z',
      startedAt: null,
      completedAt: null,
    },
  ],
  'wf-103/step-fix': [
    {
      attemptId: 'att-601',
      stepId: 'step-fix',
      attemptNumber: 1,
      agentName: 'SweAgent',
      status: 'interrupted',
      caseId: 'case-960',
      failureCode: 'OPERATOR_CANCEL',
      resultEvidenceId: null,
      revision: 2,
      createdAt: '2026-10-02T11:00:00.000Z',
      startedAt: '2026-10-02T11:00:06.000Z',
      completedAt: '2026-10-02T11:09:12.000Z',
    },
    {
      attemptId: 'att-602',
      stepId: 'step-fix',
      attemptNumber: 2,
      agentName: 'SweAgent',
      status: 'failed',
      caseId: 'case-971',
      failureCode: 'SSE_BRIDGE_TIMEOUT',
      resultEvidenceId: null,
      revision: 4,
      createdAt: '2026-10-03T06:30:00.000Z',
      startedAt: '2026-10-03T06:30:09.000Z',
      completedAt: '2026-10-03T06:58:44.000Z',
    },
  ],
}

const MOCK_HUMAN_ACTIONS: Record<string, HumanActionRequiredDto[]> = {
  'wf-101': [
    {
      interactionId: 'int-501',
      stepId: 'step-review',
      questionEventId: 'q-77',
      prompt: 'Design review: approve the new cockpit lanes layout before implementation continues?',
      actions: [
        { id: 'approve', label: 'Approve layout' },
        { id: 'reject', label: 'Request changes' },
      ],
      expectedRevision: 12,
    },
  ],
  'wf-102': [
    {
      interactionId: 'int-502',
      stepId: 'step-approve',
      questionEventId: null,
      prompt: 'Approve the upstream schema change required by the postgres adapter migration?',
      actions: [
        { id: 'approve', label: 'Approve schema change' },
        { id: 'reject', label: 'Reject schema change' },
      ],
      expectedRevision: 5,
    },
  ],
  'wf-103': [],
}

const MOCK_PLAN_PROPOSALS: Record<string, PlanChangeProposalDto[]> = {
  'wf-101': [
    {
      proposalId: 'prop-9',
      workflowId: 'wf-101',
      status: 'pending_validation',
      summary: 'Add a load-test step before release and make verification human-owned.',
      operations: [
        { op: 'add_step', stepId: 'step-load-test', target: 'before:step-release' },
        { op: 'change_responsibility', stepId: 'step-verify', target: 'human' },
      ],
      revision: 12,
    },
  ],
  'wf-102': [],
  'wf-103': [],
}

const MOCK_CONTROLLER_HISTORY: Record<string, ControllerHistoryDto> = {
  'wf-101': {
    workflowId: 'wf-101',
    revision: 12,
    entries: [
      { caseId: 'case-ctrl-1', fromStatus: 'open', toStatus: 'running', at: '2026-10-02T13:58:00.000Z' },
      { caseId: 'case-ctrl-1', fromStatus: 'running', toStatus: 'completed', at: '2026-10-02T14:31:41.000Z' },
      { caseId: 'case-ctrl-2', fromStatus: 'open', toStatus: 'running', at: '2026-10-02T16:00:01.000Z' },
      {
        caseId: 'case-ctrl-2',
        fromStatus: 'running',
        toStatus: 'waiting_human',
        at: '2026-10-03T09:02:05.000Z',
        note: 'Design review checkpoint opened.',
      },
    ],
  },
  'wf-102': {
    workflowId: 'wf-102',
    revision: 5,
    entries: [
      { caseId: 'case-ctrl-3', fromStatus: 'open', toStatus: 'running', at: '2026-10-03T07:59:58.000Z' },
      {
        caseId: 'case-ctrl-3',
        fromStatus: 'running',
        toStatus: 'waiting_human',
        at: '2026-10-03T08:15:20.000Z',
        note: 'Schema approval checkpoint opened.',
      },
    ],
  },
  'wf-103': {
    workflowId: 'wf-103',
    revision: 3,
    entries: [
      { caseId: 'case-ctrl-4', fromStatus: 'open', toStatus: 'running', at: '2026-10-02T10:59:55.000Z' },
      {
        caseId: 'case-ctrl-4',
        fromStatus: 'running',
        toStatus: 'blocked',
        at: '2026-10-03T06:58:45.000Z',
        note: 'Attempt failed; workflow paused pending retry decision.',
      },
    ],
  },
}

/**
 * Lane assignments for the workflow-detail lanes view.
 * View-model metadata only — NOT part of the Phase 0 DTO contract.
 */
const MOCK_STEP_LANES: Record<string, Record<string, StepLane>> = {
  'wf-101': {
    'step-spec': 'human',
    'step-design': 'agent',
    'step-implement': 'agent',
    'step-verify': 'code',
    'step-review': 'human',
  },
  'wf-102': {
    'step-scan': 'code',
    'step-migrate': 'agent',
    'step-approve': 'human',
  },
  'wf-103': {
    'step-reproduce': 'agent',
    'step-fix': 'agent',
    'step-regression-test': 'code',
  },
}

@Injectable({ providedIn: 'root' })
export class WorkstreamMockService {
  /** Phase 0 tool: get_workstream. */
  // TODO(Phase 6): wire to GET /api/factory/workstreams (filter by workstreamId) and unwrap the { data } envelope.
  getWorkstream(workstreamId: string): Observable<WorkstreamDto> {
    console.log('[WORKSTREAM-MOCK] getWorkstream', workstreamId)
    return of({ ...MOCK_WORKSTREAM, workstreamId: workstreamId || MOCK_WORKSTREAM.workstreamId })
  }

  /** Phase 0 tool: list_workflows. */
  // TODO(Phase 6): wire to GET /api/factory/workflows?workstreamId=... and unwrap the { data } envelope.
  listWorkflows(workstreamId: string): Observable<WorkflowListDto> {
    console.log('[WORKSTREAM-MOCK] listWorkflows', workstreamId)
    return of(MOCK_WORKFLOW_LIST)
  }

  /** Phase 0 tool: get_workflow. */
  // TODO(Phase 6): wire to GET /api/factory/workflows/{workflowId} and unwrap the { data } envelope.
  getWorkflow(workflowId: string): Observable<WorkflowDetailDto> {
    console.log('[WORKSTREAM-MOCK] getWorkflow', workflowId)
    const detail = MOCK_WORKFLOW_DETAILS[workflowId] ?? DEFAULT_WORKFLOW_DETAIL
    return of(detail)
  }

  /** Phase 0 tool: get_step_attempts. */
  // TODO(Phase 6): wire to GET /api/factory/workflows/{workflowId}/attempts (filter by stepId), unwrap { data }.
  getStepAttempts(workflowId: string, stepId: string): Observable<StepAttemptDto[]> {
    console.log('[WORKSTREAM-MOCK] getStepAttempts', workflowId, stepId)
    return of(MOCK_STEP_ATTEMPTS[`${workflowId}/${stepId}`] ?? [])
  }

  /** Phase 0 tool: get_blockers. */
  // TODO(Phase 6): wire to GET /api/factory/workflows/{workflowId}/actions (blockers part), unwrap { data }.
  getBlockers(workflowId: string): Observable<WorkflowBlockerDto[]> {
    console.log('[WORKSTREAM-MOCK] getBlockers', workflowId)
    return of(MOCK_WORKFLOW_DETAILS[workflowId]?.blockers ?? [])
  }

  /**
   * Authoritative allowed actions (`GET /api/factory/workflows/{workflowId}/actions`).
   *
   * Mock mode derives the same shape as the real endpoint from the fixtures:
   * a `reply` action per open interaction and a `retry` action per failed /
   * indeterminate attempt. Components gate their controls on this list, never
   * on a hardcoded action set.
   */
  getAllowedActions(workflowId: string): Observable<WorkflowActionsResponseDto> {
    console.log('[WORKSTREAM-MOCK] getAllowedActions', workflowId)
    const allowedActions: AllowedActionDto[] = []
    for (const interaction of MOCK_HUMAN_ACTIONS[workflowId] ?? []) {
      allowedActions.push({
        type: 'reply',
        interactionId: interaction.interactionId,
        stepId: interaction.stepId,
        questionEventId: interaction.questionEventId ?? undefined,
        expectedRevision: interaction.expectedRevision,
        label: 'Reply',
      })
    }
    const attempts = Object.entries(MOCK_STEP_ATTEMPTS)
      .filter(([key]) => key.startsWith(`${workflowId}/`))
      .flatMap(([, list]) => list)
    for (const attempt of attempts) {
      if (attempt.status === 'failed' || attempt.status === 'indeterminate') {
        allowedActions.push({
          type: 'retry',
          stepId: attempt.stepId,
          expectedRevision: attempt.revision,
          label: 'Retry step',
        })
      }
    }
    return of({
      workflowId,
      revision: MOCK_WORKFLOW_DETAILS[workflowId]?.revision ?? 0,
      allowedActions,
      blockers: MOCK_WORKFLOW_DETAILS[workflowId]?.blockers ?? [],
    })
  }

  /** Phase 0 tool: get_required_human_actions. */
  // TODO(Phase 6): wire to GET /api/factory/workflows/{workflowId}/interactions (open only), unwrap { data }.
  getRequiredHumanActions(workflowId: string): Observable<HumanActionRequiredDto[]> {
    console.log('[WORKSTREAM-MOCK] getRequiredHumanActions', workflowId)
    return of(MOCK_HUMAN_ACTIONS[workflowId] ?? [])
  }

  /** Phase 0 tool: propose_plan_change (proposal listing). */
  // TODO(Phase 6): no listing endpoint exists yet — Phase 6 must expose proposals produced by propose_plan_change.
  getPlanChangeProposals(workflowId: string): Observable<PlanChangeProposalDto[]> {
    console.log('[WORKSTREAM-MOCK] getPlanChangeProposals', workflowId)
    return of(MOCK_PLAN_PROPOSALS[workflowId] ?? [])
  }

  /** Controller case history (worker steps excluded). */
  // TODO(Phase 6): controller case history source TBD — no concrete Phase 0 DTO; confirm shape before wiring.
  getControllerHistory(workflowId: string): Observable<ControllerHistoryDto> {
    console.log('[WORKSTREAM-MOCK] getControllerHistory', workflowId)
    const history = MOCK_CONTROLLER_HISTORY[workflowId] ?? { workflowId, revision: 1, entries: [] }
    return of(history)
  }

  /** Lane metadata for the lanes view (view-model only, not a Phase 0 DTO). */
  // TODO(Phase 6): replace mock lane metadata with the real lane/capability source once defined by the Factory.
  getStepLanes(workflowId: string): Observable<Record<string, StepLane>> {
    console.log('[WORKSTREAM-MOCK] getStepLanes', workflowId)
    return of(MOCK_STEP_LANES[workflowId] ?? {})
  }

  /** Phase 0 command tool: request_agent_retry (control-plane / human capability, §5). */
  // TODO(Phase 6): wire to POST /api/factory/workflows/{workflowId}/retries and unwrap the { data } envelope.
  requestAgentRetry(
    workflowId: string,
    stepId: string,
    expectedRevision: number,
    reasonCode: string
  ): Observable<{ status: string }> {
    console.log('[WORKSTREAM-MOCK] requestAgentRetry (stub)', { workflowId, stepId, expectedRevision, reasonCode })
    return of({ status: 'retry_requested' })
  }

  /** Reply to an open human interaction (control-plane / human only, §5). */
  // TODO(Phase 6): wire to POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply, unwrap { data }.
  respondToInteraction(
    workflowId: string,
    interactionId: string,
    actionId: HumanActionId
  ): Observable<{ state: string }> {
    console.log('[WORKSTREAM-MOCK] respondToInteraction (stub)', { workflowId, interactionId, actionId })
    return of({ state: 'answered' })
  }

  /** Decide a plan-change proposal (control-plane / human only, §5). */
  // TODO(Phase 6): plan-change decision endpoint TBD — propose_plan_change applies nothing by itself (§6.2).
  decidePlanChange(proposalId: string, decision: 'approve' | 'reject'): Observable<{ status: string }> {
    console.log('[WORKSTREAM-MOCK] decidePlanChange (stub)', { proposalId, decision })
    return of({ status: decision === 'approve' ? 'approved' : 'rejected' })
  }
}
