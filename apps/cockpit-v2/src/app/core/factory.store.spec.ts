import { TestBed } from '@angular/core/testing'
import { FactoryStore } from './factory.store'
import { SANDBOXES } from './mock-data'

describe('FactoryStore', () => {
  let store: FactoryStore

  beforeEach(() => {
    TestBed.configureTestingModule({})
    store = TestBed.inject(FactoryStore)
  })

  it('exposes the mock sandboxes', () => {
    expect(store.sandboxes()).toEqual(SANDBOXES)
  })

  it('hides destroyed sandboxes from the active list', () => {
    expect(store.activeSandboxes()).toHaveLength(2)
    expect(store.activeSandboxes().every((sandbox) => sandbox.status !== 'destroyed')).toBe(true)
  })

  it('switches between active and visible sandboxes with showDestroyed', () => {
    expect(store.visibleSandboxes()).toEqual(store.activeSandboxes())

    store.showDestroyed.set(true)
    expect(store.visibleSandboxes()).toEqual(store.sandboxes())
  })

  it('aggregates the workflow costs of the active sandboxes', () => {
    const expected = store.activeSandboxes().reduce((sum, sandbox) => sum + (sandbox.run?.costUsd ?? 0), 0)

    expect(store.costs().workflowsUsd).toBeCloseTo(expected, 6)
    expect(store.costs().active).toBe(store.activeSandboxes().length)
  })

  it('marks a sandbox as destroyed and drops its run', () => {
    const target = store.activeSandboxes()[0]
    if (!target) {
      throw new Error('expected at least one active sandbox')
    }

    store.destroy(target.name)

    const updated = store.sandboxes().find((sandbox) => sandbox.name === target.name)
    expect(updated?.status).toBe('destroyed')
    expect(updated?.run).toBeUndefined()
    expect(updated?.finalCostUsd).toBe(target.run?.costUsd ?? 0)
  })

  it('reuses the demo session for an arbitrary run id', () => {
    const session = store.session('unknown-run')

    expect(session?.id).toBe('unknown-run')
  })
})
