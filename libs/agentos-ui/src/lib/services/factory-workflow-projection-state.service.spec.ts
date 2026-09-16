import { TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing'
import { Subject, of } from 'rxjs'
import { FactoryApiService } from './factory-api.service'
import { FactoryWorkflowProjectionStateService } from './factory-workflow-projection-state.service'
import { WorkflowProjectionSnapshotDto } from './factory-workflow-projection.model'

const NS1 = '11111111-1111-4111-8111-111111111111'
const NS2 = '22222222-2222-4222-8222-222222222222'
const snapshot = (revision: number, workflowId = 'workflow-1'): WorkflowProjectionSnapshotDto => ({
  workflowId,
  revision,
  projectionHash: `hash-${revision}`,
  projection: {
    schemaVersion: '1',
    workflowId,
    workflowType: 'delivery',
    title: `Workflow ${revision}`,
    status: 'running',
    steps: [],
  },
})

describe('FactoryWorkflowProjectionStateService', () => {
  let service: FactoryWorkflowProjectionStateService
  let api: {
    listWorkflowProjections: jest.Mock
    listRemovedWorkflowProjections: jest.Mock
    getWorkflowProjection: jest.Mock
    streamWorkflowProjectionUpdates: jest.Mock
    removeWorkflowProjection: jest.Mock
    restoreWorkflowProjection: jest.Mock
    purgeWorkflowProjection: jest.Mock
  }
  type TestEvent =
    | { type: 'open'; namespaceId: string }
    | {
        type: 'updated' | 'removed' | 'restored' | 'purged'
        workflowId: string
        namespaceId: string
        revision?: number
      }
  let streams: Subject<TestEvent>[]

  beforeEach(() => {
    streams = []
    api = {
      listWorkflowProjections: jest.fn((namespaceId: string) =>
        of({ data: { namespaceId, state: 'active' as const, items: [snapshot(1)] } })
      ),
      listRemovedWorkflowProjections: jest.fn((namespaceId: string) =>
        of({ data: { namespaceId, state: 'removed' as const, items: [] } })
      ),
      getWorkflowProjection: jest.fn(),
      streamWorkflowProjectionUpdates: jest.fn(() => {
        const stream = new Subject<TestEvent>()
        streams.push(stream)
        return stream
      }),
      removeWorkflowProjection: jest.fn(),
      restoreWorkflowProjection: jest.fn(),
      purgeWorkflowProjection: jest.fn(),
    }
    TestBed.configureTestingModule({
      providers: [FactoryWorkflowProjectionStateService, { provide: FactoryApiService, useValue: api }],
    })
    service = TestBed.inject(FactoryWorkflowProjectionStateService)
  })

  afterEach(() => service.ngOnDestroy())

  it('loads only a newer revision after an invalidation', () => {
    service.selectNamespace(NS1)
    api.getWorkflowProjection.mockReturnValue(of({ data: { namespaceId: NS1, ...snapshot(2) } }))
    streams[0].next({ type: 'updated', workflowId: 'workflow-1', namespaceId: NS1, revision: 2 })
    expect(service.workflows()[0].revision).toBe(2)
    expect(api.getWorkflowProjection).toHaveBeenCalledWith(NS1, 'workflow-1')
  })

  it('rejects stale events and late stale detail responses', () => {
    const detail = new Subject<{ data: WorkflowProjectionSnapshotDto & { namespaceId: string } }>()
    service.selectNamespace(NS1)
    api.getWorkflowProjection.mockReturnValue(detail)
    streams[0].next({ type: 'updated', workflowId: 'workflow-1', namespaceId: NS1, revision: 2 })
    detail.next({ data: { namespaceId: NS1, ...snapshot(1) } })
    streams[0].next({ type: 'updated', workflowId: 'workflow-1', namespaceId: NS1, revision: 1 })
    expect(service.workflows()[0].revision).toBe(1)
    expect(api.getWorkflowProjection).toHaveBeenCalledTimes(1)
  })

  it('cleans up the previous stream and rejects its namespace on switch', () => {
    service.selectNamespace(NS1)
    const oldStream = streams[0]
    service.selectNamespace(NS2)
    expect(oldStream.observed).toBe(false)
    oldStream.next({ type: 'updated', workflowId: 'old', namespaceId: NS1, revision: 9 })
    expect(service.namespaceId()).toBe(NS2)
    expect(service.workflows().some((item) => item.workflowId === 'old')).toBe(false)
  })

  it('keeps authoritative polling active until EventSource open, then becomes live', fakeAsync(() => {
    service.selectNamespace(NS1)
    expect(service.synchronization()).toBe('connecting')
    api.listWorkflowProjections.mockClear()
    tick(15_000)
    expect(api.listWorkflowProjections).toHaveBeenCalledWith(NS1)
    streams[0].next({ type: 'open', namespaceId: NS1 })
    expect(service.synchronization()).toBe('live')
    api.listWorkflowProjections.mockClear()
    tick(15_000)
    expect(api.listWorkflowProjections).not.toHaveBeenCalled()
    discardPeriodicTasks()
  }))

  it('reconciles over HTTP before reconnect and bounds repeated reconnect errors', fakeAsync(() => {
    service.selectNamespace(NS1)
    for (let attempt = 0; attempt < 7; attempt++) {
      streams[attempt].error(new Error('offline'))
      tick(5_000)
    }
    expect(api.streamWorkflowProjectionUpdates).toHaveBeenCalledTimes(7)
    expect(api.listWorkflowProjections.mock.calls.length).toBeGreaterThanOrEqual(7)
    tick(30_000)
    expect(api.streamWorkflowProjectionUpdates).toHaveBeenCalledTimes(7)
    service.clear()
    discardPeriodicTasks()
  }))

  it('retains cards and exposes backend errors while lifecycle actions fail', () => {
    api.removeWorkflowProjection.mockReturnValue(new Subject())
    service.selectNamespace(NS1)
    service.remove('workflow-1')
    expect(service.actionWorkflowId()).toBe('workflow-1')
    expect(service.workflows()).toHaveLength(1)
  })

  it('reconciles remove, restore and purge responses without duplicates', () => {
    api.removeWorkflowProjection.mockReturnValue(
      of({ data: { namespaceId: NS1, workflowId: 'workflow-1', state: 'removed' } })
    )
    api.restoreWorkflowProjection.mockReturnValue(
      of({ data: { namespaceId: NS1, workflowId: 'workflow-1', revision: 1, state: 'active' } })
    )
    api.purgeWorkflowProjection.mockReturnValue(
      of({ data: { namespaceId: NS1, workflowId: 'workflow-1', state: 'purged' } })
    )
    service.selectNamespace(NS1)
    service.remove('workflow-1')
    expect(service.workflows()).toEqual([])
    service.restore('workflow-1')
    service.purge('workflow-1')
    expect(service.action()).toBeNull()
  })

  it('treats purge SSE as an authoritative removed-list invalidation', () => {
    api.listRemovedWorkflowProjections.mockImplementation((namespaceId: string) =>
      of({ data: { namespaceId, state: 'removed' as const, items: [snapshot(1, 'removed-1')] } })
    )
    service.selectNamespace(NS1)
    expect(service.removedWorkflows()).toHaveLength(1)
    const reconciliation = new Subject<{
      data: { namespaceId: string; state: 'removed'; items: WorkflowProjectionSnapshotDto[] }
    }>()
    api.listRemovedWorkflowProjections.mockReturnValue(reconciliation)
    streams[0].next({ type: 'purged', workflowId: 'removed-1', namespaceId: NS1 })
    expect(api.listRemovedWorkflowProjections).toHaveBeenLastCalledWith(NS1)
    expect(service.removedWorkflows()).toHaveLength(1)
    reconciliation.next({ data: { namespaceId: NS1, state: 'removed', items: [] } })
    expect(service.removedWorkflows()).toEqual([])
  })

  it('tears down stream, polling, and reconnect timer on namespace switch', fakeAsync(() => {
    service.selectNamespace(NS1)
    const oldStream = streams[0]
    oldStream.error(new Error('offline'))
    service.selectNamespace(NS2)
    tick(5_000)
    expect(api.streamWorkflowProjectionUpdates).toHaveBeenCalledTimes(2)
    expect(service.namespaceId()).toBe(NS2)
    service.clear()
    discardPeriodicTasks()
  }))

  it('rejects a late list response from a previous namespace', () => {
    const first = new Subject<{
      data: { namespaceId: string; state: 'active'; items: WorkflowProjectionSnapshotDto[] }
    }>()
    api.listWorkflowProjections
      .mockReturnValueOnce(first)
      .mockImplementation((namespaceId: string) => of({ data: { namespaceId, state: 'active', items: [] } }))
    service.selectNamespace(NS1)
    service.selectNamespace(NS2)
    first.next({ data: { namespaceId: NS1, state: 'active', items: [snapshot(8, 'late')] } })
    expect(service.workflows()).toEqual([])
  })
})
