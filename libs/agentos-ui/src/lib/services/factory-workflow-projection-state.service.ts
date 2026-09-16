import { inject, Injectable, OnDestroy, signal } from '@angular/core'
import { forkJoin, interval, Subscription, switchMap, take, timer } from 'rxjs'
import { FactoryApiService } from './factory-api.service'
import { WorkflowProjectionEvent, WorkflowProjectionSnapshotDto } from './factory-workflow-projection.model'

const FALLBACK_POLL_MS = 15_000
const RECONNECT_MS = 5_000
const MAX_RECONNECT_ATTEMPTS = 6
export type WorkflowProjectionSynchronization = 'idle' | 'connecting' | 'live' | 'polling'
export type WorkflowProjectionAction = 'remove' | 'restore' | 'purge'

@Injectable({ providedIn: 'root' })
export class FactoryWorkflowProjectionStateService implements OnDestroy {
  private readonly api = inject(FactoryApiService)
  readonly namespaceId = signal<string | null>(null)
  readonly workflows = signal<WorkflowProjectionSnapshotDto[]>([])
  readonly removedWorkflows = signal<WorkflowProjectionSnapshotDto[]>([])
  readonly loading = signal(false)
  readonly error = signal<string | null>(null)
  readonly actionWorkflowId = signal<string | null>(null)
  readonly action = signal<WorkflowProjectionAction | null>(null)
  readonly actionError = signal<string | null>(null)
  readonly synchronization = signal<WorkflowProjectionSynchronization>('idle')

  private generation = 0
  private streamSubscription: Subscription | null = null
  private pollingSubscription: Subscription | null = null
  private reconnectSubscription: Subscription | null = null
  private reconnectAttempts = 0

  selectNamespace(namespaceId: string): void {
    if (this.namespaceId() === namespaceId) return
    this.clearConnections()
    this.namespaceId.set(namespaceId)
    this.workflows.set([])
    this.removedWorkflows.set([])
    this.actionWorkflowId.set(null)
    this.action.set(null)
    this.actionError.set(null)
    this.reconnectAttempts = 0
    this.loadLists(namespaceId, true, () => this.connect(namespaceId))
  }

  refresh(): void {
    const id = this.namespaceId()
    if (id) this.loadLists(id, true)
  }

  remove(workflowId: string): void {
    this.runAction('remove', workflowId)
  }
  restore(workflowId: string): void {
    this.runAction('restore', workflowId)
  }
  purge(workflowId: string): void {
    this.runAction('purge', workflowId)
  }

  clear(): void {
    this.clearConnections()
    this.namespaceId.set(null)
    this.workflows.set([])
    this.removedWorkflows.set([])
    this.loading.set(false)
    this.error.set(null)
    this.actionWorkflowId.set(null)
    this.action.set(null)
    this.actionError.set(null)
    this.synchronization.set('idle')
  }

  private runAction(action: WorkflowProjectionAction, workflowId: string): void {
    const namespaceId = this.namespaceId()
    if (!namespaceId || this.action()) return
    const generation = this.generation
    this.action.set(action)
    this.actionWorkflowId.set(workflowId)
    this.actionError.set(null)
    const request =
      action === 'remove'
        ? this.api.removeWorkflowProjection(namespaceId, workflowId)
        : action === 'restore'
          ? this.api.restoreWorkflowProjection(namespaceId, workflowId)
          : this.api.purgeWorkflowProjection(namespaceId, workflowId)
    request.pipe(take(1)).subscribe({
      next: ({ data }) => {
        if (
          !this.isCurrent(namespaceId, generation) ||
          data.namespaceId !== namespaceId ||
          data.workflowId !== workflowId
        )
          return
        if (action === 'remove') this.workflows.update((items) => this.without(items, workflowId))
        else this.removedWorkflows.update((items) => this.without(items, workflowId))
        this.finishAction()
        this.loadLists(namespaceId, false)
      },
      error: (error: unknown) => {
        if (!this.isCurrent(namespaceId, generation)) return
        this.actionError.set(
          this.errorMessage(
            error,
            `Workflow could not be ${action === 'purge' ? 'deleted permanently' : action + 'd'}.`
          )
        )
        this.finishAction(false)
      },
    })
  }

  private finishAction(clearError = true): void {
    this.action.set(null)
    this.actionWorkflowId.set(null)
    if (clearError) this.actionError.set(null)
  }

  private loadLists(namespaceId: string, visible: boolean, afterLoad?: () => void): void {
    const generation = this.generation
    if (visible) this.loading.set(true)
    forkJoin({
      active: this.api.listWorkflowProjections(namespaceId),
      removed: this.api.listRemovedWorkflowProjections(namespaceId),
    })
      .pipe(take(1))
      .subscribe({
        next: ({ active, removed }) => {
          if (
            !this.isCurrent(namespaceId, generation) ||
            active.data.namespaceId !== namespaceId ||
            removed.data.namespaceId !== namespaceId
          )
            return
          this.workflows.set(this.authoritative(this.workflows(), active.data.items))
          this.removedWorkflows.set(this.authoritative(this.removedWorkflows(), removed.data.items))
          this.loading.set(false)
          this.error.set(null)
          afterLoad?.()
        },
        error: (error: unknown) => {
          if (!this.isCurrent(namespaceId, generation)) return
          this.loading.set(false)
          this.error.set(this.errorMessage(error, 'Generic workflows could not be loaded. Please try again.'))
          afterLoad?.()
        },
      })
  }

  private connect(namespaceId: string): void {
    if (this.namespaceId() !== namespaceId || (this.streamSubscription && !this.streamSubscription.closed)) return
    this.reconnectSubscription?.unsubscribe()
    this.reconnectSubscription = null
    this.synchronization.set('connecting')
    this.startPolling(namespaceId, false)
    this.streamSubscription = this.api.streamWorkflowProjectionUpdates(namespaceId).subscribe({
      next: (event) => this.handleInvalidation(namespaceId, event),
      error: () => this.onStreamError(namespaceId),
      complete: () => this.onStreamError(namespaceId),
    })
  }

  private handleInvalidation(namespaceId: string, event: WorkflowProjectionEvent): void {
    if (event.namespaceId !== namespaceId || this.namespaceId() !== namespaceId) return

    switch (event.type) {
      case 'open':
        this.reconnectAttempts = 0
        this.stopPolling()
        this.synchronization.set('live')
        return
      case 'updated':
        this.fetchActive(namespaceId, event.workflowId, event.revision)
        return
      case 'removed':
        this.workflows.update((items) => this.without(items, event.workflowId))
        this.loadLists(namespaceId, false)
        return
      case 'restored':
        this.removedWorkflows.update((items) => this.without(items, event.workflowId))
        this.fetchActive(namespaceId, event.workflowId, event.revision)
        return
      case 'purged':
        this.loadRemovedList(namespaceId)
        return
      default:
        return this.assertNever(event)
    }
  }

  private assertNever(event: never): never {
    throw new Error(`Unhandled workflow projection event: ${JSON.stringify(event)}`)
  }

  private fetchActive(namespaceId: string, workflowId: string, revision: number): void {
    const current = this.workflows().find((item) => item.workflowId === workflowId)?.revision ?? 0
    if (revision <= current) return
    const generation = this.generation
    this.api
      .getWorkflowProjection(namespaceId, workflowId)
      .pipe(take(1))
      .subscribe({
        next: ({ data }) => {
          if (!this.isCurrent(namespaceId, generation) || data.namespaceId !== namespaceId || data.revision < revision)
            return
          this.workflows.update((items) => this.upsert(items, data))
        },
        error: () => this.loadLists(namespaceId, false),
      })
  }

  private onStreamError(namespaceId: string): void {
    if (this.namespaceId() !== namespaceId) return
    this.streamSubscription?.unsubscribe()
    this.streamSubscription = null
    this.startPolling(namespaceId)
    if (
      this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS ||
      (this.reconnectSubscription && !this.reconnectSubscription.closed)
    )
      return
    this.reconnectAttempts++
    this.reconnectSubscription = timer(RECONNECT_MS).subscribe(() => {
      this.reconnectSubscription = null
      if (this.namespaceId() === namespaceId) this.loadLists(namespaceId, false, () => this.connect(namespaceId))
    })
  }

  private loadRemovedList(namespaceId: string): void {
    const generation = this.generation
    this.api
      .listRemovedWorkflowProjections(namespaceId)
      .pipe(take(1))
      .subscribe({
        next: ({ data }) => {
          if (!this.isCurrent(namespaceId, generation) || data.namespaceId !== namespaceId || data.state !== 'removed')
            return
          this.removedWorkflows.set(this.authoritative(this.removedWorkflows(), data.items))
        },
        error: () => {},
      })
  }

  private startPolling(namespaceId: string, setStatus = true): void {
    if (this.pollingSubscription && !this.pollingSubscription.closed) return
    if (setStatus) this.synchronization.set('polling')
    this.pollingSubscription = interval(FALLBACK_POLL_MS)
      .pipe(
        switchMap(() =>
          forkJoin({
            active: this.api.listWorkflowProjections(namespaceId),
            removed: this.api.listRemovedWorkflowProjections(namespaceId),
          })
        )
      )
      .subscribe({
        next: ({ active, removed }) => {
          if (this.namespaceId() === namespaceId) {
            this.workflows.set(this.authoritative(this.workflows(), active.data.items))
            this.removedWorkflows.set(this.authoritative(this.removedWorkflows(), removed.data.items))
          }
        },
        error: () => this.stopPolling(),
      })
  }

  private authoritative(
    current: WorkflowProjectionSnapshotDto[],
    incoming: WorkflowProjectionSnapshotDto[]
  ): WorkflowProjectionSnapshotDto[] {
    const byId = new Map(current.map((item) => [item.workflowId, item]))
    return incoming
      .map((item) => ((byId.get(item.workflowId)?.revision ?? -1) > item.revision ? byId.get(item.workflowId)! : item))
      .sort((a, b) => a.workflowId.localeCompare(b.workflowId))
  }
  private upsert(
    items: WorkflowProjectionSnapshotDto[],
    incoming: WorkflowProjectionSnapshotDto
  ): WorkflowProjectionSnapshotDto[] {
    const existing = items.find((item) => item.workflowId === incoming.workflowId)
    if (existing && existing.revision >= incoming.revision) return items
    return [...this.without(items, incoming.workflowId), incoming].sort((a, b) =>
      a.workflowId.localeCompare(b.workflowId)
    )
  }
  private without(items: WorkflowProjectionSnapshotDto[], id: string): WorkflowProjectionSnapshotDto[] {
    return items.filter((item) => item.workflowId !== id)
  }
  private isCurrent(namespaceId: string, generation: number): boolean {
    return this.namespaceId() === namespaceId && this.generation === generation
  }
  private errorMessage(error: unknown, fallback: string): string {
    const value = error as { error?: { error?: { message?: string } } }
    return value?.error?.error?.message || fallback
  }
  private stopPolling(): void {
    this.pollingSubscription?.unsubscribe()
    this.pollingSubscription = null
  }
  private clearConnections(): void {
    this.generation++
    this.streamSubscription?.unsubscribe()
    this.streamSubscription = null
    this.stopPolling()
    this.reconnectSubscription?.unsubscribe()
    this.reconnectSubscription = null
  }
  ngOnDestroy(): void {
    this.clear()
  }
}
