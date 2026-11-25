import { FactoryRun } from '../../core/models'
import { runStatusKey } from './run-status-filter'

function run(status: FactoryRun['status'], cardState?: NonNullable<FactoryRun['run']>['cardState']): FactoryRun {
  return {
    id: 'wf-1',
    title: 'Run',
    project: 'ns',
    status,
    costUsd: 0,
    durationSec: 0,
    tokens: 0,
    phases: [],
    run: {
      id: 'wf-1',
      workflow: 'wf',
      status: 'running',
      cardState,
      goal: '',
      costUsd: 0,
      durationSec: 0,
      tokens: 0,
      phases: [],
    },
  }
}

describe('runStatusKey', () => {
  it('uses the card state of a live run', () => {
    expect(runStatusKey(run('working', 'failed'))).toBe('failed')
  })

  it('lets destroyed win over the card state', () => {
    expect(runStatusKey(run('destroyed', 'running'))).toBe('destroyed')
  })

  it('defaults to pending (waiting) when the card state is absent', () => {
    expect(runStatusKey(run('working'))).toBe('pending')
    expect(runStatusKey({ ...run('working'), run: undefined })).toBe('pending')
  })
})
