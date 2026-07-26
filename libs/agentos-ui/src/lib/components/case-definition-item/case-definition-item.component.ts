import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { CaseDefinition, DayOfWeek, SchedulerUnit } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

const DAY_LABELS: Record<DayOfWeek, string> = {
  MON: 'Monday',
  TUE: 'Tuesday',
  WED: 'Wednesday',
  THU: 'Thursday',
  FRI: 'Friday',
  SAT: 'Saturday',
  SUN: 'Sunday',
}

/**
 * CaseDefinitionItemComponent — presentational component for a single case definition card.
 *
 * Displays definition name, schedule, and enabled status.
 * Actions: edit (navigates), toggle enable/disable, delete (two-step inline confirm).
 *
 * When platformMode is true, the edit route navigates to /admin/case-definitions instead
 * of /:namespaceId/case-definitions.
 * When readOnly is true, mutation actions (edit, toggle, delete) are hidden.
 */
@Component({
  selector: 'agentos-case-definition-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './case-definition-item.component.html',
  styleUrl: './case-definition-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionItemComponent {
  private readonly router = inject(Router)

  readonly definition = input.required<CaseDefinition>()
  readonly namespaceId = input<string | undefined>(undefined)
  /** When true, edit navigates to the admin platform route instead of the namespace route. */
  readonly platformMode = input(false)
  /**
   * When true, edit, toggle and delete actions are hidden.
   * Used for platform-level definitions displayed in a namespace context (read-only visibility).
   */
  readonly readOnly = input(false)

  readonly toggleRequested = output<CaseDefinition>()
  readonly deleteRequested = output<CaseDefinition>()

  protected readonly pendingDelete = signal(false)

  /**
   * Human-readable schedule label.
   * Examples:
   *   "Every day at 09:00 UTC"
   *   "Every 2 weeks (Mon, Wed) at 09:00 UTC"
   *   "Every 3 months at 09:00 UTC"
   */
  protected get scheduleLabel(): string {
    const { recurrence } = this.definition()
    const every = recurrence.every ?? 1
    const unit = recurrence.unit ?? SchedulerUnit.DAY
    const time = recurrence.timeUtc

    const unitLabel = (() => {
      switch (unit) {
        case SchedulerUnit.DAY:
          return every === 1 ? 'day' : 'days'
        case SchedulerUnit.WEEK:
          return every === 1 ? 'week' : 'weeks'
        case SchedulerUnit.MONTH:
          return every === 1 ? 'month' : 'months'
      }
    })()

    const dayFilter =
      unit === SchedulerUnit.WEEK && recurrence.days?.length
        ? ` (${recurrence.days.map((d) => DAY_LABELS[d] ?? d).join(', ')})`
        : ''

    return `Every ${every} ${unitLabel}${dayFilter} at ${time} UTC`
  }

  protected get menuItems(): KebabMenuItem[] {
    const def = this.definition()
    return [
      { key: 'edit', label: 'Edit definition', icon: 'edit' },
      {
        key: 'toggle',
        label: def.enabled ? 'Disable' : 'Enable',
        icon: def.enabled ? 'toggle_on' : 'toggle_off',
      },
      { key: 'delete', label: 'Delete definition', icon: 'delete', variant: 'danger' },
    ]
  }

  protected onMenuAction(key: string): void {
    const def = this.definition()
    switch (key) {
      case 'edit':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'case-definitions', def.id, 'edit'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'case-definitions', def.id, 'edit'])
        }
        break
      case 'toggle':
        this.toggleRequested.emit(def)
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.definition())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
