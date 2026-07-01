import { ChangeDetectionStrategy, Component, EventEmitter, inject, Input, Output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { CaseDefinition } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

const DAY_LABELS: Record<string, string> = {
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
 * Displays definition name, frequency, time UTC, and enabled status.
 * Actions: edit (navigates), toggle enable/disable, delete (two-step inline confirm).
 */
@Component({
  selector: 'agentos-case-definition-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './case-definition-item.component.html',
  styleUrl: './case-definition-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionItemComponent {
  private readonly router = inject(Router)

  @Input({ required: true }) definition!: CaseDefinition
  @Input({ required: true }) namespaceId!: string

  @Output() toggleRequested = new EventEmitter<CaseDefinition>()
  @Output() deleteRequested = new EventEmitter<CaseDefinition>()

  protected readonly pendingDelete = signal(false)

  /** Human-readable schedule label, e.g. "Weekly on Monday at 09:00 UTC" or "Daily at 09:00 UTC". */
  protected get scheduleLabel(): string {
    if (this.definition.frequency === 'WEEKLY') {
      const day = this.definition.dayOfWeek ? (DAY_LABELS[this.definition.dayOfWeek] ?? this.definition.dayOfWeek) : ''
      return `Weekly${day ? ` on ${day}` : ''} at ${this.definition.timeUtc} UTC`
    }
    return `Daily at ${this.definition.timeUtc} UTC`
  }

  protected get menuItems(): KebabMenuItem[] {
    return [
      { key: 'edit', label: 'Edit definition', icon: 'edit' },
      {
        key: 'toggle',
        label: this.definition.enabled ? 'Disable' : 'Enable',
        icon: this.definition.enabled ? 'toggle_on' : 'toggle_off',
      },
      { key: 'delete', label: 'Delete definition', icon: 'delete', variant: 'danger' },
    ]
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId, 'case-definitions', this.definition.id, 'edit'])
        break
      case 'toggle':
        this.toggleRequested.emit(this.definition)
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.definition)
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
