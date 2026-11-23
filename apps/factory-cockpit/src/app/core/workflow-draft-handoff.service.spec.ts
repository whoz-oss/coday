import { TestBed } from '@angular/core/testing'
import { WorkflowDraftHandoffService } from './workflow-draft-handoff.service'

describe('WorkflowDraftHandoffService', () => {
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
})
