import { mapEvidenceToEvents } from './phase-detail'

describe('Factory code diagnostics', () => {
  it('maps a failed build with bounded structured output and removes ANSI', () => {
    const events = mapEvidenceToEvents(
      [
        {
          evidenceId: 'v1',
          stepId: 'frontend-build',
          kind: 'code-verification',
          outcome: 'fail',
          facts: {
            verification: 'frontend-build',
            executed: true,
            command: 'pnpm nx build client',
            exitCode: 1,
            timedOut: false,
            durationMs: 1234,
            code: 'VERIFICATION_FAILED',
            message: 'Verification exited with code 1.',
            stderr: {
              excerpt: '\u001b[31mRunning target build for project client failed\u001b[0m',
              truncated: true,
              sha256: 'sha256:error',
            },
            stdout: { excerpt: 'safe output', truncated: false, sha256: 'sha256:output' },
            cwd: '/worktree',
            observedHead: 'a'.repeat(40),
            rootIdentity: 'sha256:root',
            timeoutMs: 1000,
          },
        },
      ],
      []
    )
    const diagnostic = events[0]?.codeDiagnostic
    expect(events).toHaveLength(1)
    expect(diagnostic).toEqual(
      expect.objectContaining({ state: 'failed', exitCode: 1, verification: 'frontend-build' })
    )
    expect(diagnostic?.stderr?.excerpt).not.toContain('\u001b')
    expect(diagnostic?.stderr?.truncated).toBe(true)
    expect(diagnostic?.failedTasks).toContain('client')
    expect(diagnostic?.technical).toEqual(expect.objectContaining({ cwd: '/worktree', timeoutMs: 1000 }))
  })

  it('supports timeout refusal and legacy string streams without changing verdict from prose', () => {
    const events = mapEvidenceToEvents(
      [
        {
          evidenceId: 'timeout',
          stepId: 'build',
          kind: 'code-verification',
          outcome: 'fail',
          facts: {
            verification: 'build',
            executed: true,
            exitCode: 0,
            timedOut: true,
            stdout: 'all tests passed',
            stderr: '',
          },
        },
        {
          evidenceId: 'refused',
          stepId: 'test',
          kind: 'session-step',
          outcome: 'failed',
          facts: {
            verification: 'test',
            executed: false,
            code: 'VERIFICATION_NOT_DECLARED',
            message: 'not declared',
          },
        },
      ],
      []
    )
    expect(events[0]?.codeDiagnostic?.state).toBe('timed_out')
    expect(events[0]?.codeDiagnostic?.stdout?.excerpt).toBe('all tests passed')
    expect(events[1]?.codeDiagnostic?.state).toBe('refused')
  })

  it.each([false, true])('merges linked rich session diagnostics regardless of order (reverse=%s)', (reverse) => {
    const verification = {
      evidenceId: 'v1',
      stepId: 'build',
      kind: 'code-verification',
      outcome: 'fail',
      facts: {
        verification: 'build',
        command: 'pnpm build',
        exitCode: 1,
        durationMs: 1234,
      },
    }
    const terminal = {
      evidenceId: 's1',
      stepId: 'build',
      kind: 'session-step',
      outcome: 'failed',
      facts: {
        evidenceId: 'v1',
        verification: 'build',
        executed: true,
        exitCode: 1,
        durationMs: 1234,
        code: 'VERIFICATION_FAILED',
        message: 'Verification exited with code 1.',
        stdout: { excerpt: 'compiled some files', truncated: false },
        stderr: { excerpt: 'Factory build error', truncated: false },
        cwd: '/worktree',
      },
    }
    const events = mapEvidenceToEvents(reverse ? [terminal, verification] : [verification, terminal], [])
    expect(events).toHaveLength(1)
    expect(events[0]?.durationSec).toBe(1.234)
    expect(events[0]?.codeDiagnostic).toEqual(
      expect.objectContaining({
        state: 'failed',
        code: 'VERIFICATION_FAILED',
        message: 'Verification exited with code 1.',
        command: 'pnpm build',
        exitCode: 1,
      })
    )
    expect(events[0]?.codeDiagnostic?.stdout?.excerpt).toBe('compiled some files')
    expect(events[0]?.codeDiagnostic?.stderr?.excerpt).toBe('Factory build error')
    expect(events[0]?.codeDiagnostic?.technical).toEqual(expect.objectContaining({ cwd: '/worktree' }))
  })

  it('does not merge retries, running evidence, or legacy unlinked payloads by step alone', () => {
    const events = mapEvidenceToEvents(
      [
        {
          evidenceId: 'v1',
          stepId: 'build',
          kind: 'code-verification',
          outcome: 'fail',
          facts: { verification: 'build', exitCode: 1 },
        },
        {
          evidenceId: 's-running',
          stepId: 'build',
          kind: 'session-step',
          outcome: 'running',
          facts: { status: 'running', startedAt: '2025-01-01T00:00:00Z' },
        },
        {
          evidenceId: 's-legacy',
          stepId: 'build',
          kind: 'session-step',
          outcome: 'failed',
          facts: { verification: 'build', executed: true, exitCode: 2, stderr: 'retry two' },
        },
        {
          evidenceId: 'v2',
          stepId: 'build',
          kind: 'code-verification',
          outcome: 'fail',
          facts: { verification: 'build', exitCode: 2 },
        },
      ],
      []
    )
    expect(events).toHaveLength(4)
    expect(events.filter((event) => event.codeDiagnostic?.state === 'running')).toHaveLength(0)
    expect(events.map((event) => event.codeDiagnostic?.exitCode).filter((code) => code !== undefined)).toEqual([
      1, 2, 2,
    ])
    expect(events.find((event) => event.codeDiagnostic?.stderr)?.codeDiagnostic?.stderr?.excerpt).toBe('retry two')
  })

  it('bounds linked rich streams and commands', () => {
    const long = 'x'.repeat(20_000)
    const events = mapEvidenceToEvents(
      [
        {
          evidenceId: 'v1',
          stepId: 'build',
          kind: 'code-verification',
          outcome: 'fail',
          facts: {
            verification: 'build',
            executed: true,
            exitCode: 1,
            command: long,
          },
        },
        {
          evidenceId: 's1',
          stepId: 'build',
          kind: 'session-step',
          outcome: 'failed',
          facts: {
            evidenceId: 'v1',
            verification: 'build',
            executed: true,
            exitCode: 1,
            stderr: long,
          },
        },
      ],
      []
    )
    expect(events).toHaveLength(1)
    expect(events[0]?.codeDiagnostic?.command?.length).toBeLessThanOrEqual(2048)
    expect(events[0]?.codeDiagnostic?.stderr?.excerpt.length).toBeLessThanOrEqual(8192)
    expect(events[0]?.codeDiagnostic?.stderr?.truncated).toBe(true)
  })

  it('keeps authoritative success when output text says failed', () => {
    const [event] = mapEvidenceToEvents(
      [
        {
          evidenceId: 'v1',
          stepId: 'build',
          kind: 'code-verification',
          outcome: 'pass',
          facts: {
            verification: 'build',
            executed: true,
            exitCode: 0,
            timedOut: false,
            stderr: 'FAILED words are diagnostic only',
          },
        },
      ],
      []
    )
    expect(event?.codeDiagnostic?.state).toBe('passed')
  })
})
