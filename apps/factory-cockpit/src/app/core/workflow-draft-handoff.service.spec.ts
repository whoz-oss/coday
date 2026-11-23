import { TestBed } from '@angular/core/testing'
import { DRAFT_HANDOFF_TTL_MS, WorkflowDraftHandoffService } from './workflow-draft-handoff.service'

describe('WorkflowDraftHandoffService', () => {
  afterEach(() => jest.restoreAllMocks())

  it('hands a draft over exactly once', () => {
    const service = TestBed.inject(WorkflowDraftHandoffService)
    const draft = { workflowType: 'derived', version: '1.0.0', steps: [] }
    expect(service.take()).toBeNull()
    service.set(draft)
    expect(service.take()).toBe(draft)
    expect(service.take()).toBeNull()
  })

  it('keeps only the latest draft', () => {
    const service = TestBed.inject(WorkflowDraftHandoffService)
    service.set({ workflowType: 'first' })
    service.set({ workflowType: 'second' })
    expect(service.take()?.workflowType).toBe('second')
  })

  it('drops a draft on clear()', () => {
    const service = TestBed.inject(WorkflowDraftHandoffService)
    service.set({ workflowType: 'derived' })
    service.clear()
    expect(service.take()).toBeNull()
  })

  it('ignores an abandoned draft once the delay has passed, and still empties the slot', () => {
    const service = TestBed.inject(WorkflowDraftHandoffService)
    const now = jest.spyOn(Date, 'now').mockReturnValue(1_000)
    service.set({ workflowType: 'stale' })
    now.mockReturnValue(1_000 + DRAFT_HANDOFF_TTL_MS + 1)
    expect(service.take()).toBeNull()
    now.mockReturnValue(1_000)
    expect(service.take()).toBeNull()
  })

  it('still hands over a draft read within the delay', () => {
    const service = TestBed.inject(WorkflowDraftHandoffService)
    const now = jest.spyOn(Date, 'now').mockReturnValue(1_000)
    service.set({ workflowType: 'fresh' })
    now.mockReturnValue(1_000 + DRAFT_HANDOFF_TTL_MS)
    expect(service.take()?.workflowType).toBe('fresh')
  })
})
