import { blockerGroupTitle, blockerIcon, blockerKind, classifyBlockers } from './blocker-classification'
import { BlockerCode, WorkflowBlocker } from './models'

function blocker(code: BlockerCode | string, label = code, stepId?: string): WorkflowBlocker {
  return { code: code as BlockerCode, label, ...(stepId ? { stepId } : {}) }
}

describe('blocker-classification', () => {
  describe('blockerKind', () => {
    it.each([
      ['WAITING_HUMAN_INTERACTION', 'waiting'],
      ['NEEDS_RESEARCH', 'research'],
      ['ATTEMPT_FAILED', 'failure'],
      ['VERIFICATION_FAILED', 'failure'],
      ['WORKSPACE_PRECHECK_FAILED', 'failure'],
      ['STEP_BLOCKED', 'consequence'],
      ['REAL_COST_PAUSED', 'cost'],
      ['UNKNOWN_RUNTIME', 'indeterminate'],
      ['SOMETHING_ELSE', 'unknown'],
    ])('maps %s to %s', (code, kind) => {
      expect(blockerKind(blocker(code))).toBe(kind)
    })
  })

  describe('classifyBlockers', () => {
    it('returns the seven groups, all empty, for an empty input', () => {
      expect(classifyBlockers([])).toEqual({
        failures: [],
        consequences: [],
        waiting: [],
        research: [],
        cost: [],
        indeterminate: [],
        unknown: [],
      })
    })

    it('classifies ATTEMPT_FAILED and VERIFICATION_FAILED as real failures', () => {
      const attempt = blocker('ATTEMPT_FAILED', 'Attempt failed', 'build')
      const verification = blocker('VERIFICATION_FAILED', 'Verification failed', 'build')

      const groups = classifyBlockers([attempt, verification])

      expect(groups.failures).toEqual([attempt, verification])
      expect(groups.consequences).toEqual([])
    })

    it('groups STEP_BLOCKED as consequences, not root failures', () => {
      const failed = blocker('ATTEMPT_FAILED', 'Attempt failed', 'build')
      const test = blocker('STEP_BLOCKED', 'Test blocked', 'test')
      const deploy = blocker('STEP_BLOCKED', 'Deploy blocked', 'deploy')

      const groups = classifyBlockers([failed, test, deploy])

      expect(groups.failures).toEqual([failed])
      expect(groups.consequences).toEqual([test, deploy])
    })

    it('classifies a refused workspace pre-check as a root failure', () => {
      const precheck = blocker('WORKSPACE_PRECHECK_FAILED', 'Required agent(s) reviewer are not available.')

      const groups = classifyBlockers([precheck])

      expect(groups.failures).toEqual([precheck])
      expect(groups.unknown).toEqual([])
    })

    it('classifies UNKNOWN_RUNTIME as indeterminate, distinct from failures and consequences', () => {
      const runtime = blocker('UNKNOWN_RUNTIME', 'Unknown runtime state')

      const groups = classifyBlockers([runtime])

      expect(groups.indeterminate).toEqual([runtime])
      expect(groups.failures).toEqual([])
      expect(groups.consequences).toEqual([])
    })

    it('classifies NEEDS_RESEARCH as research, not as a failure or an indeterminate state', () => {
      const research = blocker('NEEDS_RESEARCH', 'Agent needs research', 'research')

      const groups = classifyBlockers([research])

      expect(groups.research).toEqual([research])
      expect(groups.failures).toEqual([])
      expect(groups.indeterminate).toEqual([])
    })

    it('keeps every root failure, in input order (does not pick only one)', () => {
      const build = blocker('ATTEMPT_FAILED', 'Build failed', 'build')
      const test = blocker('VERIFICATION_FAILED', 'Test failed', 'test')

      expect(classifyBlockers([build, test]).failures).toEqual([build, test])
    })

    it('puts waiting, cost and unknown codes in their own group only', () => {
      const waiting = blocker('WAITING_HUMAN_INTERACTION', 'Waiting', 'build')
      const cost = blocker('REAL_COST_PAUSED', 'Paused')
      const weird = blocker('SOMETHING_ELSE', 'Weird')

      expect(classifyBlockers([waiting, cost, weird])).toEqual({
        failures: [],
        consequences: [],
        waiting: [waiting],
        research: [],
        cost: [cost],
        indeterminate: [],
        unknown: [weird],
      })
    })
  })

  describe('blockerIcon', () => {
    it.each([
      ['WAITING_HUMAN_INTERACTION', 'person_alert'],
      ['NEEDS_RESEARCH', 'info'],
      ['ATTEMPT_FAILED', 'error'],
      ['VERIFICATION_FAILED', 'error'],
      ['WORKSPACE_PRECHECK_FAILED', 'error'],
      ['STEP_BLOCKED', 'link_off'],
      ['REAL_COST_PAUSED', 'paid'],
      ['UNKNOWN_RUNTIME', 'help'],
      ['SOMETHING_ELSE', 'warning'],
    ])('uses the %s icon %s', (code, icon) => {
      expect(blockerIcon(blocker(code))).toBe(icon)
    })
  })

  describe('blockerGroupTitle', () => {
    it.each([
      ['failure', 'Root failures'],
      ['consequence', 'Blocked steps (consequence)'],
      ['waiting', 'Waiting for human'],
      ['cost', 'Budget pause'],
      ['indeterminate', 'Indeterminate state'],
      ['unknown', 'Other blockers'],
    ] as const)('titles the %s group %s', (kind, title) => {
      expect(blockerGroupTitle(kind)).toBe(title)
    })
  })
})
