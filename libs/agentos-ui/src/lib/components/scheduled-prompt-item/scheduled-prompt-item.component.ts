import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { DayOfWeek, ScheduledPrompt, SchedulerUnit } from '@whoz-oss/agentos-api-client'
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
 * ScheduledPromptItemComponent — presentational component for a single scheduled prompt card.
 *
 * Displays name, schedule, and enabled status.
 * Actions: edit (navigates), toggle enable/disable, delete (two-step inline confirm).
 *
 * When platformMode is true, the edit route navigates to /admin/scheduled-prompts instead
 * of /:namespaceId/scheduled-prompts.
 * When readOnly is true, mutation actions (edit, toggle, delete) are hidden.
 */
@Component({
  selector: 'agentos-scheduled-prompt-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './scheduled-prompt-item.component.html',
  styleUrl: './scheduled-prompt-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduledPromptItemComponent {
  private readonly router = inject(Router)

  readonly definition = input.required<ScheduledPrompt>()
  readonly namespaceId = input<string | undefined>(undefined)
  /** When true, edit navigates to the admin platform route instead of the namespace route. */
  readonly platformMode = input(false)
  /**
   * When true, edit, toggle and delete actions are hidden.
   * Used for platform-level definitions displayed in a namespace context (read-only visibility).
   */
  readonly readOnly = input(false)

  readonly toggleRequested = output<ScheduledPrompt>()
  readonly deleteRequested = output<ScheduledPrompt>()

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

  /**
   * Formats an ISO instant to "25 Jul 2026 at 09:00 UTC".
   * Returns null if the value is absent.
   */
  private formatInstant(iso: string | null | undefined): string | null {
    if (!iso) return null
    const d = new Date(iso)
    const day = d.getUTCDate()
    const month = d.toLocaleString('en-GB', { month: 'short', timeZone: 'UTC' })
    const year = d.getUTCFullYear()
    const hh = String(d.getUTCHours()).padStart(2, '0')
    const mm = String(d.getUTCMinutes()).padStart(2, '0')
    return `${day} ${month} ${year} at ${hh}:${mm} UTC`
  }

  protected get nextRunLabel(): string | null {
    return this.formatInstant(this.definition().nextRunAt)
  }

  protected get lastRunLabel(): string | null {
    return this.formatInstant(this.definition().lastRunAt)
  }

  protected get menuItems(): KebabMenuItem[] {
    const def = this.definition()
    return [
      { key: 'edit', label: 'Edit scheduled prompt', icon: 'edit' },
      {
        key: 'toggle',
        label: def.enabled ? 'Disable' : 'Enable',
        icon: def.enabled ? 'toggle_on' : 'toggle_off',
      },
      { key: 'delete', label: 'Delete scheduled prompt', icon: 'delete', variant: 'danger' },
    ]
  }

  protected onMenuAction(key: string): void {
    const def = this.definition()
    switch (key) {
      case 'edit':
        if (this.platformMode()) {
          this.router.navigate(['/agentos', 'admin', 'scheduled-prompts', def.id, 'edit'])
        } else {
          this.router.navigate(['/agentos', this.namespaceId(), 'scheduled-prompts', def.id, 'edit'])
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
