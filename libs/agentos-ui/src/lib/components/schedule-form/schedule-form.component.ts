import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { Schedule, ScheduleControllerService } from '@whoz-oss/agentos-api-client'

/**
 * ScheduleFormComponent — full-page create / edit form for a schedule.
 *
 * Mode is determined by the presence of `:scheduleId` in the route params:
 * - `/:namespaceId/schedules/new`                   → create mode
 * - `/:namespaceId/schedules/:scheduleId/edit`       → edit mode
 *
 * Schedule type (one-shot vs recurring) is toggled via a radio group.
 * - One-shot: requires `triggerAt` (datetime-local)
 * - Recurring: requires `intervalSchedule.startTimestamp` and `intervalSchedule.interval`
 *
 * The namespaceId is fixed at creation time and never exposed as an editable field.
 * On success or cancel, navigates back to /:namespaceId/schedules.
 */
@Component({
  selector: 'agentos-schedule-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './schedule-form.component.html',
  styleUrl: './schedule-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduleFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly scheduleController = inject(ScheduleControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly form = new FormGroup({
    message: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    agentName: new FormControl<string>('', { nonNullable: true }),
    caseId: new FormControl<string>('', { nonNullable: true }),
    enabled: new FormControl<boolean>(true, { nonNullable: true }),
    scheduleType: new FormControl<'oneShot' | 'recurring'>('oneShot', { nonNullable: true }),
    // One-shot fields
    triggerAt: new FormControl<string>('', { nonNullable: true }),
    // Recurring fields
    intervalStart: new FormControl<string>('', { nonNullable: true }),
    intervalDuration: new FormControl<string>('', { nonNullable: true }),
  })

  protected get messageControl() {
    return this.form.controls.message
  }
  protected get agentNameControl() {
    return this.form.controls.agentName
  }
  protected get caseIdControl() {
    return this.form.controls.caseId
  }
  protected get enabledControl() {
    return this.form.controls.enabled
  }
  protected get scheduleTypeControl() {
    return this.form.controls.scheduleType
  }
  protected get triggerAtControl() {
    return this.form.controls.triggerAt
  }
  protected get intervalStartControl() {
    return this.form.controls.intervalStart
  }
  protected get intervalDurationControl() {
    return this.form.controls.intervalDuration
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side fields). */
  private existingSchedule: Schedule | null = null

  ngOnInit(): void {
    const scheduleId = this.route.snapshot.paramMap.get('scheduleId')
    if (scheduleId) {
      this.isEditMode.set(true)
      this.loadSchedule(scheduleId)
    }
  }

  private loadSchedule(id: string): void {
    this.isLoading.set(true)
    this.scheduleController
      .getByIdSchedule(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (schedule) => {
          this.existingSchedule = schedule
          this.patchForm(schedule)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  /**
   * Convert an ISO 8601 UTC string (e.g. "2025-01-15T16:00:00.000Z") to the
   * "YYYY-MM-DDTHH:mm" format expected by <input type="datetime-local">,
   * expressed in the browser's local timezone.
   *
   * Using toLocaleString + manual formatting avoids the offset drift that
   * occurs when naively slicing the UTC string.
   */
  private toLocalDatetimeInputValue(isoUtc: string): string {
    const d = new Date(isoUtc)
    const pad = (n: number) => String(n).padStart(2, '0')
    return (
      d.getFullYear() +
      '-' +
      pad(d.getMonth() + 1) +
      '-' +
      pad(d.getDate()) +
      'T' +
      pad(d.getHours()) +
      ':' +
      pad(d.getMinutes())
    )
  }

  private patchForm(schedule: Schedule): void {
    this.messageControl.setValue(schedule.message)
    this.agentNameControl.setValue(schedule.agentName ?? '')
    this.caseIdControl.setValue(schedule.caseId ?? '')
    this.enabledControl.setValue(schedule.enabled)

    if (schedule.oneShot && schedule.triggerAt) {
      this.scheduleTypeControl.setValue('oneShot')
      // Convert UTC ISO string to local datetime for the datetime-local input
      this.triggerAtControl.setValue(this.toLocalDatetimeInputValue(schedule.triggerAt))
    } else if (schedule.intervalSchedule) {
      this.scheduleTypeControl.setValue('recurring')
      this.intervalStartControl.setValue(this.toLocalDatetimeInputValue(schedule.intervalSchedule.startTimestamp))
      this.intervalDurationControl.setValue(schedule.intervalSchedule.interval)
    }
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    const scheduleType = this.scheduleTypeControl.value

    // Validate schedule-type-specific required fields
    if (scheduleType === 'oneShot' && !this.triggerAtControl.value) {
      this.triggerAtControl.setErrors({ required: true })
      return
    }
    if (scheduleType === 'recurring' && (!this.intervalStartControl.value || !this.intervalDurationControl.value)) {
      if (!this.intervalStartControl.value) this.intervalStartControl.setErrors({ required: true })
      if (!this.intervalDurationControl.value) this.intervalDurationControl.setErrors({ required: true })
      return
    }

    this.isSubmitting.set(true)

    const payload: Schedule = {
      ...(this.existingSchedule ?? {}),
      namespaceId: this.namespaceId,
      message: this.messageControl.value.trim(),
      agentName: this.agentNameControl.value.trim() || undefined,
      caseId: this.caseIdControl.value.trim() || undefined,
      enabled: this.enabledControl.value,
      oneShot: scheduleType === 'oneShot',
      triggerAt: scheduleType === 'oneShot' ? new Date(this.triggerAtControl.value).toISOString() : undefined,
      intervalSchedule:
        scheduleType === 'recurring'
          ? {
              startTimestamp: new Date(this.intervalStartControl.value).toISOString(),
              interval: this.intervalDurationControl.value.trim(),
            }
          : undefined,
      occurrenceCount: this.existingSchedule?.occurrenceCount ?? 0,
    }

    const call$ = this.isEditMode()
      ? this.scheduleController.updateSchedule(this.existingSchedule!.id ?? '', payload)
      : this.scheduleController.createSchedule(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'schedules'])
  }
}
