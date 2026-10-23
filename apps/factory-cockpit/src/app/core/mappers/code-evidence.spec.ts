import { mapProjectionToSessionDetail } from './workflow'

describe('Factory code evidence projection', () => {
  it('projects failed code verification into code lane and global duration/status', () => {
    const workflow = {
      workflowId: 'wf-1',
      projection: {
        workflowId: 'wf-1',
        workflowType: 'accessibility-ticket',
        title: 'Accessibility',
        status: 'pending',
        steps: [
          {
            id: 'frontend-build',
            name: 'Factory builds frontend',
            status: 'pending',
            responsibility: { kind: 'code', name: 'frontend-build' },
          },
          {
            id: 'a11y-audit',
            name: 'Audit',
            status: 'pending',
            responsibility: { kind: 'agent', name: 'A11yReviewer' },
          },
        ],
      },
    }
    const evidence = {
      items: [
        {
          kind: 'code-verification',
          stepId: 'frontend-build',
          outcome: 'fail',
          facts: { stepId: 'frontend-build', exitCode: 1, timedOut: false, durationMs: 2500 },
        },
      ],
    }

    const session = mapProjectionToSessionDetail(workflow, undefined, evidence)

    expect(session.status).toBe('failed')
    expect(session.durationSec).toBeGreaterThanOrEqual(3)
    expect(session.lanes.find((lane) => lane.id === 'code')?.blocks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ stepId: 'frontend-build', status: 'failed', startSec: 0, endSec: 2.5 }),
      ])
    )
    expect(session.lanes.find((lane) => lane.label === 'A11yReviewer')?.blocks ?? []).toHaveLength(0)
  })

  it('projects one running agent attempt before completion without duplicating its step block', () => {
    const workflow = {
      workflowId: 'wf-2',
      projection: {
        workflowId: 'wf-2',
        workflowType: 'delivery',
        title: 'Live',
        status: 'running',
        steps: [
          {
            id: 'architect',
            name: 'Architect',
            status: 'running',
            startedAt: '2026-01-01T00:00:00Z',
            responsibility: { kind: 'agent', name: 'Architect' },
          },
        ],
      },
    }
    const attempts = {
      data: [
        {
          attemptId: 'a-1',
          stepId: 'architect',
          attemptNumber: 1,
          agentName: 'Architect',
          status: 'running',
          caseId: 'case-1',
          startedAt: '2026-01-01T00:00:00Z',
        },
      ],
    }

    const session = mapProjectionToSessionDetail(workflow, undefined, undefined, undefined, undefined, attempts)
    const blocks = session.lanes.find((lane) => lane.label === 'Architect')?.blocks ?? []
    expect(blocks).toHaveLength(1)
    expect(blocks[0]).toEqual(expect.objectContaining({ stepId: 'architect', status: 'running' }))
  })
})
