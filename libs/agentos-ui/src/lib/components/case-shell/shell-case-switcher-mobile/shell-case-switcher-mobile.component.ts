import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { Case, CaseStatusEnum } from '@whoz-oss/agentos-api-client'

/**
 * ShellCaseSwitcherMobileComponent — mobile case switcher.
 *
 * Responsible for:
 * - Trigger row showing the active case title + status
 * - Expandable list of other cases
 * - Per-case action panel: Open, Favorite, Delete
 * - "New case" shortcut
 *
 * Visible only on mobile (hidden via CSS on desktop).
 */
@Component({
  selector: 'agentos-shell-case-switcher-mobile',
  templateUrl: './shell-case-switcher-mobile.component.html',
  styleUrl: './shell-case-switcher-mobile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellCaseSwitcherMobileComponent {
  // ── Inputs ──────────────────────────────────────────────
  readonly cases = input.required<Case[]>()
  readonly activeCaseId = input.required<string | null>()
  readonly open = input.required<boolean>()

  // ── Derived signals ─────────────────────────────────────────
  protected readonly activeCase = computed(() => {
    const id = this.activeCaseId()
    return id ? (this.cases().find((c) => c.id === id) ?? null) : null
  })

  protected readonly activeCaseTitle = computed(() => {
    const c = this.activeCase()
    return c?.title ?? this.activeCaseId() ?? 'New case'
  })

  protected readonly activeCaseStatus = computed(() => this.deriveMobileStatus(this.activeCase()?.status))

  protected readonly activeCaseStatusLabel = computed(() => this.activeCase()?.status?.toLowerCase() ?? '')

  /** Cases to show in the switcher list (all except the active one) */
  protected readonly otherCases = computed(() => this.cases().filter((c) => c.id !== this.activeCaseId()))

  /** Id of the case whose action panel is currently open, null if none. */
  protected readonly expandedActionId = signal<string | null>(null)

  /** Id of the case currently awaiting delete confirmation, null if none. */
  protected readonly pendingDeleteId = signal<string | null>(null)

  // ── Outputs ──────────────────────────────────────────────
  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly toggled = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()

  protected toggleActionPanel(event: Event, caseId: string): void {
    event.stopPropagation()
    this.expandedActionId.update((current) => (current === caseId ? null : caseId))
    // Close any pending delete confirmation when switching panels
    this.pendingDeleteId.set(null)
  }

  protected onDeleteRequested(event: Event, caseId: string): void {
    event.stopPropagation()
    this.pendingDeleteId.set(caseId)
  }

  protected onDeleteConfirmed(event: Event, caseId: string): void {
    event.stopPropagation()
    this.pendingDeleteId.set(null)
    this.expandedActionId.set(null)
    this.deleteRequested.emit(caseId)
  }

  protected onDeleteCancelled(event: Event): void {
    event.stopPropagation()
    this.pendingDeleteId.set(null)
  }

  protected onStarToggled(event: Event, c: Case): void {
    event.stopPropagation()
    this.starToggled.emit({ id: c.id ?? '', starred: !c.favorite })
  }

  protected onCaseSelected(caseId: string): void {
    this.expandedActionId.set(null)
    this.pendingDeleteId.set(null)
    this.caseSelected.emit(caseId)
  }

  protected deriveMobileStatus(status: CaseStatusEnum | undefined): string {
    switch (status) {
      case CaseStatusEnum.RUNNING:
        return 'run'
      case CaseStatusEnum.KILLED:
        return 'killed'
      case CaseStatusEnum.ERROR:
        return 'error'
      default:
        return 'idle'
    }
  }
}
