import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core'
import { Case, CaseStatusEnum } from '@whoz-oss/agentos-api-client'

/**
 * CaseDrawerItem — view model for a single case card in the sidebar list.
 */
interface CaseDrawerItem {
  id: string
  name: string
  status: 'run' | 'ask' | 'idle' | 'killed' | 'error'
  statusLabel: string
  isRunning: boolean
  timeAgo: string
  canInterrupt: boolean
  canKill: boolean
  canDelete: boolean
}

/**
 * CaseDrawerComponent — presentational sidebar card list for cases.
 *
 * Compact at rest, expands on hover via CSS-only max-height + opacity transition,
 * revealing a detail panel with meta info and action buttons.
 *
 * Outputs:
 * - (caseSelected)       — card click
 * - (deleteRequested)    — delete confirmed (2-step arm)
 * - (interruptRequested) — interrupt a RUNNING case
 * - (killRequested)      — kill a RUNNING case
 *
 * Kept presentational: no Router, no HttpClient, no state services.
 * Uses ds-entity-list for the chrome (toolbar, search, create button).
 * The itemTemplate handles hierarchical rendering: each root item renders
 * its sub-cases inline, collapsed by default.
 */
@Component({
  selector: 'agentos-case-drawer',
  imports: [],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly interruptRequested = output<string>()
  readonly killRequested = output<string>()

  protected readonly caseItems = computed(() => this.cases().map((c) => this.toCaseDrawerItem(c)))

  /** Id of the card currently armed for deletion (first click). */
  protected readonly pendingDeleteId = signal<string | null>(null)

  constructor() {
    // Reset pending delete whenever the case list changes
    effect(() => {
      this.cases()
      this.pendingDeleteId.set(null)
    })
  }

  protected onItemSelected(id: string): void {
    this.caseSelected.emit(id)
  }

  protected onStop(event: MouseEvent, id: string): void {
    event.stopPropagation()
    this.interruptRequested.emit(id)
  }

  protected onKill(event: MouseEvent, id: string): void {
    event.stopPropagation()
    this.killRequested.emit(id)
  }

  protected onDeleteArm(event: MouseEvent, id: string): void {
    event.stopPropagation()
    this.pendingDeleteId.set(id)
  }

  protected onDeleteConfirm(event: MouseEvent, id: string): void {
    event.stopPropagation()
    this.pendingDeleteId.set(null)
    this.deleteRequested.emit(id)
  }

  protected onDeleteCancel(event: MouseEvent): void {
    event.stopPropagation()
    this.pendingDeleteId.set(null)
  }

  // ---------------------------------------------------------------------------
  // Mapping helpers
  // ---------------------------------------------------------------------------

  private toCaseDrawerItem(c: Case): CaseDrawerItem {
    const running = c.status === CaseStatusEnum.RUNNING
    const terminal = c.status === CaseStatusEnum.KILLED || c.status === CaseStatusEnum.ERROR
    return {
      id: c.id ?? '',
      name: c.title ?? c.id ?? '—',
      status: this.deriveStatus(c.status),
      statusLabel: this.deriveStatusLabel(c.status),
      isRunning: running,
      timeAgo: c.created ? timeAgo(c.created) : '',
      canInterrupt: running,
      canKill: !terminal,
      canDelete: !running,
    }
  }

  private deriveStatus(status: CaseStatusEnum): 'run' | 'ask' | 'idle' | 'killed' | 'error' {
    switch (status) {
      case CaseStatusEnum.RUNNING:
        return 'run'
      case CaseStatusEnum.PENDING:
        return 'ask'
      case CaseStatusEnum.KILLED:
        return 'killed'
      case CaseStatusEnum.ERROR:
        return 'error'
      default:
        return 'idle'
    }
  }

  private deriveStatusLabel(status: CaseStatusEnum): string {
    switch (status) {
      case CaseStatusEnum.RUNNING:
        return 'RUN'
      case CaseStatusEnum.PENDING:
        return 'ASK'
      case CaseStatusEnum.KILLED:
        return 'KILLED'
      case CaseStatusEnum.ERROR:
        return 'ERR'
      default:
        return 'IDLE'
    }
  }
}

/**
 * Returns a human-readable relative time string from an ISO date string.
 * e.g. "il y a 2h", "il y a 3j", "il y a 5min"
 */
function timeAgo(iso: string): string {
  const now = Date.now()
  const then = new Date(iso).getTime()
  if (isNaN(then)) return ''
  const diffMs = now - then
  const diffMin = Math.floor(diffMs / 60_000)
  if (diffMin < 1) return 'il y a quelques secondes'
  if (diffMin < 60) return `il y a ${diffMin}min`
  const diffH = Math.floor(diffMin / 60)
  if (diffH < 24) return `il y a ${diffH}h`
  const diffD = Math.floor(diffH / 24)
  if (diffD < 30) return `il y a ${diffD}j`
  const diffM = Math.floor(diffD / 30)
  if (diffM < 12) return `il y a ${diffM} mois`
  return `il y a ${Math.floor(diffM / 12)} an(s)`
}
