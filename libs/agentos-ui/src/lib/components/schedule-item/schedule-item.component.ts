import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { Schedule } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * ScheduleItemComponent — presentational component for a single schedule card.
 *
 * Displays the message (truncated), agent name, enabled state, and schedule type.
 * Edit navigates to the dedicated edit route; delete uses inline confirmation.
 */
@Component({
  selector: 'agentos-schedule-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './schedule-item.component.html',
  styleUrl: './schedule-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduleItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) schedule!: Schedule
  @Input({ required: true }) namespaceId!: string

  @Output() deleteRequested = new EventEmitter<Schedule>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit schedule', icon: 'edit' },
    { key: 'delete', label: 'Delete schedule', icon: 'delete', variant: 'danger' },
  ]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'schedules', this.schedule.id, 'edit'])
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.schedule)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }

  protected get scheduleTypeLabel(): string {
    if (this.schedule.oneShot) return 'One-shot'
    if (this.schedule.intervalSchedule) return 'Recurring'
    return 'Scheduled'
  }

  protected get nextTriggerDisplay(): string | null {
    if (!this.schedule.nextTriggerAt) return null
    return new Date(this.schedule.nextTriggerAt).toLocaleString()
  }
}
