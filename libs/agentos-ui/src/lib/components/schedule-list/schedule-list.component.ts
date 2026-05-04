import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Schedule, ScheduleControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { ScheduleItemComponent } from '../schedule-item/schedule-item.component'

/**
 * ScheduleListComponent — list view for schedules of a namespace.
 *
 * Loaded at /:namespaceId/schedules. Responsibilities:
 * - Load and display the list of Schedule via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/schedules/new)
 * - Deletion delegated to ScheduleItemComponent
 *
 * Create and edit are handled by ScheduleFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-schedule-list',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, ScheduleItemComponent, IconButtonComponent],
  templateUrl: './schedule-list.component.html',
  styleUrl: './schedule-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduleListComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly scheduleController = inject(ScheduleControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw schedules, kept for delete lookups. */
  private readonly schedules$ = this.refresh$.pipe(
    switchMap(() => this.scheduleController.listByParentSchedule(this.namespaceId))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly scheduleItems$ = this.schedules$.pipe(
    map((schedules) =>
      schedules.map(
        (s): EntityListItem => ({
          id: s.id ?? '',
          name: s.message.length > 60 ? s.message.slice(0, 60) + '…' : s.message,
          description: s.agentName ?? undefined,
          badges: [
            ...(!s.enabled ? [{ label: 'Disabled', variant: 'warning' as const }] : []),
            ...(s.oneShot
              ? [{ label: 'One-shot', variant: 'info' as const }]
              : [{ label: 'Recurring', variant: 'info' as const }]),
          ],
        })
      )
    )
  )

  /** Full schedule objects indexed by id — used to resolve itemTemplate events. */
  private schedulesById = new Map<string, Schedule>()

  constructor() {
    this.schedules$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((schedules) => {
      this.schedulesById = new Map(schedules.map((s) => [s.id ?? '', s]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'schedules', 'new'])
  }

  protected deleteSchedule(schedule: Schedule): void {
    this.scheduleController
      .deleteSchedule(schedule.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveSchedule(id: string): Schedule | null {
    return this.schedulesById.get(id) ?? null
  }
}
