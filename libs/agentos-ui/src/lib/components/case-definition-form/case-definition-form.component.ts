import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { HttpErrorResponse } from '@angular/common/http'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { startWith } from 'rxjs'
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  CaseDefinition,
  DayOfWeek,
  SchedulerEndType,
  SchedulerUnit,
} from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, of, Observable } from 'rxjs'
import { CaseDefinitionStateService } from '../../services/case-definition-state.service'

/**
 * CaseDefinitionFormComponent — full-page create / edit form for a case definition.
 *
 * Mode is determined by the presence of `:caseDefinitionId` in the route params:
 * - `/:namespaceId/case-definitions/new`                        → create mode (namespace scope)
 * - `/:namespaceId/case-definitions/:caseDefinitionId/edit`      → edit mode (namespace scope)
 * - `/admin/case-definitions/new`                               → create mode (platform scope)
 * - `/admin/case-definitions/:caseDefinitionId/edit`            → edit mode (platform scope)
 *
 * Platform mode is detected when `namespaceId` is absent from the route params.
 *
 * ## Agent Config (always required, immutable post-creation)
 *
 * In create mode: a select lists available agents for the scope.
 * In edit mode: displayed as read-only text (agentConfigId is immutable).
 *
 * ## Opening message
 *
 * A textarea lets the user enter `promptContent` directly.
 * The backend manages the lifecycle of the underlying Prompt entity transparently.
 *
 * ## Schedule
 *
 * Recurrence: every N unit (DAY/WEEK/MONTH) + optional day-of-week filter + timeUtc.
 * Planning: startDate + endType (NEVER/ON_DATE/OCCURRENCES) with conditional fields.
 * Conditional validators: endDate required when endType=ON_DATE;
 * occurrenceCount required and ≥1 when endType=OCCURRENCES.
 */
@Component({
  selector: 'agentos-case-definition-form',
  imports: [ReactiveFormsModule],
  templateUrl: './case-definition-form.component.html',
  styleUrl: './case-definition-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly caseDefState = inject(CaseDefinitionStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  /** True when loaded from /admin/case-definitions — platform scope, no namespaceId in route. */
  protected readonly isPlatformMode = !this.namespaceId

  // ---------------------------------------------------------------------------
  // Form structure
  // ---------------------------------------------------------------------------

  protected readonly form = new FormGroup(
    {
      // Identity
      name: new FormControl<string>('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^[a-z][a-z0-9]*(-[a-z0-9]+)*$/)],
      }),
      description: new FormControl<string | null>(null),
      agentConfigId: new FormControl<string>('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      promptContent: new FormControl<string>('', {
        nonNullable: true,
        validators: [Validators.required],
      }),

      // Recurrence
      every: new FormControl<number>(1, {
        nonNullable: true,
        validators: [Validators.required, Validators.min(1)],
      }),
      unit: new FormControl<SchedulerUnit>(SchedulerUnit.DAY, {
        nonNullable: true,
        validators: [Validators.required],
      }),
      days: new FormControl<DayOfWeek[]>([], { nonNullable: true }),
      timeUtc: new FormControl<string>('09:00', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)],
      }),

      // Planning
      startDate: new FormControl<string>('', {
        nonNullable: true,
        validators: [Validators.required],
      }),
      endType: new FormControl<SchedulerEndType>(SchedulerEndType.NEVER, {
        nonNullable: true,
        validators: [Validators.required],
      }),
      endDate: new FormControl<string | null>(null),
      occurrenceCount: new FormControl<number | null>(null),

      // State
      enabled: new FormControl<boolean>(true, { nonNullable: true }),
    },
    { validators: [endDateRequiredValidator, occurrenceCountRequiredValidator] }
  )

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get agentConfigIdControl() {
    return this.form.controls.agentConfigId
  }
  protected get promptContentControl() {
    return this.form.controls.promptContent
  }
  protected get everyControl() {
    return this.form.controls.every
  }
  protected get unitControl() {
    return this.form.controls.unit
  }
  protected get daysControl() {
    return this.form.controls.days
  }
  protected get timeUtcControl() {
    return this.form.controls.timeUtc
  }
  protected get startDateControl() {
    return this.form.controls.startDate
  }
  protected get endTypeControl() {
    return this.form.controls.endType
  }
  protected get endDateControl() {
    return this.form.controls.endDate
  }
  protected get occurrenceCountControl() {
    return this.form.controls.occurrenceCount
  }
  protected get enabledControl() {
    return this.form.controls.enabled
  }

  // ---------------------------------------------------------------------------
  // UI state signals
  // ---------------------------------------------------------------------------

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  // Enum references for the template
  protected readonly SchedulerUnit = SchedulerUnit
  protected readonly SchedulerEndType = SchedulerEndType

  protected readonly schedulerUnits: { value: SchedulerUnit; label: string }[] = [
    { value: SchedulerUnit.DAY, label: 'Day(s)' },
    { value: SchedulerUnit.WEEK, label: 'Week(s)' },
    { value: SchedulerUnit.MONTH, label: 'Month(s)' },
  ]

  protected readonly endTypes: { value: SchedulerEndType; label: string }[] = [
    { value: SchedulerEndType.NEVER, label: 'Never' },
    { value: SchedulerEndType.ON_DATE, label: 'On date' },
    { value: SchedulerEndType.OCCURRENCES, label: 'After N occurrences' },
  ]

  protected readonly dayOptions: { value: DayOfWeek; label: string }[] = [
    { value: DayOfWeek.MON, label: 'Mon' },
    { value: DayOfWeek.TUE, label: 'Tue' },
    { value: DayOfWeek.WED, label: 'Wed' },
    { value: DayOfWeek.THU, label: 'Thu' },
    { value: DayOfWeek.FRI, label: 'Fri' },
    { value: DayOfWeek.SAT, label: 'Sat' },
    { value: DayOfWeek.SUN, label: 'Sun' },
  ]

  // Derived signals for conditional display
  private readonly unitSignal = toSignal(this.unitControl.valueChanges.pipe(startWith(this.unitControl.value)))
  private readonly endTypeSignal = toSignal(this.endTypeControl.valueChanges.pipe(startWith(this.endTypeControl.value)))

  protected readonly showDayFilter = computed(() => this.unitSignal() === SchedulerUnit.WEEK)
  protected readonly showEndDate = computed(() => this.endTypeSignal() === SchedulerEndType.ON_DATE)
  protected readonly showOccurrenceCount = computed(() => this.endTypeSignal() === SchedulerEndType.OCCURRENCES)

  /** Available AgentConfigs for the scope. */
  private readonly agentConfigs = signal<AgentConfig[]>([])
  protected readonly availableAgentConfigs = this.agentConfigs.asReadonly()

  /** Kept in edit mode to preserve immutable fields (id, namespaceId, agentConfigId). */
  private existingDefinition: CaseDefinition | null = null

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  ngOnInit(): void {
    // Default startDate to today
    this.startDateControl.setValue(todayIsoDate())

    const caseDefinitionId = this.route.snapshot.paramMap.get('caseDefinitionId')
    if (caseDefinitionId) {
      this.isEditMode.set(true)
      this.loadDefinitionAndResources(caseDefinitionId)
    } else {
      this.loadResources()
    }
  }

  private loadDefinitionAndResources(id: string): void {
    this.isLoading.set(true)
    forkJoin({
      definition: this.caseDefState.getById(id),
      agentConfigs: this.agentConfigs$(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ definition, agentConfigs }) => {
          this.existingDefinition = definition
          this.agentConfigs.set(agentConfigs)
          this.hydrateForm(definition)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  private loadResources(): void {
    this.agentConfigs$()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((agentConfigs) => {
        this.agentConfigs.set(agentConfigs)
      })
  }

  /**
   * Observable of AgentConfigs for the current scope.
   * Platform mode → platform agents; namespace mode → namespace agents.
   * Errors produce an empty list.
   */
  private agentConfigs$(): Observable<AgentConfig[]> {
    const call$ = this.isPlatformMode
      ? this.agentConfigController.listPlatformAgentsAgentConfig()
      : this.agentConfigController.listByParentAgentConfig(this.namespaceId!)
    return call$.pipe(catchError(() => of([] as AgentConfig[])))
  }

  /** Populate the reactive form from an existing CaseDefinition. */
  private hydrateForm(def: CaseDefinition): void {
    this.nameControl.setValue(def.name)
    this.descriptionControl.setValue(def.description ?? null)
    this.agentConfigIdControl.setValue(def.agentConfigId)
    this.promptContentControl.setValue(def.promptContent)

    // Recurrence
    this.everyControl.setValue(def.recurrence.every)
    this.unitControl.setValue(def.recurrence.unit)
    this.daysControl.setValue(def.recurrence.days ?? [])
    this.timeUtcControl.setValue(def.recurrence.timeUtc)

    // Planning
    this.startDateControl.setValue(def.planning.startDate)
    this.endTypeControl.setValue(def.planning.endType)
    this.endDateControl.setValue(def.planning.endDate ?? null)
    this.occurrenceCountControl.setValue(def.planning.occurrenceCount ?? null)

    this.enabledControl.setValue(def.enabled)
  }

  // ---------------------------------------------------------------------------
  // Day-of-week checkbox helpers
  // ---------------------------------------------------------------------------

  protected isDaySelected(day: DayOfWeek): boolean {
    return this.daysControl.value.includes(day)
  }

  protected toggleDay(day: DayOfWeek): void {
    const current = this.daysControl.value
    const next = current.includes(day) ? current.filter((d) => d !== day) : [...current, day]
    this.daysControl.setValue(next)
  }

  // ---------------------------------------------------------------------------
  // Display helpers
  // ---------------------------------------------------------------------------

  protected resolveAgentConfigName(id: string): string {
    return this.agentConfigs().find((c) => c.id === id)?.name ?? id
  }

  // ---------------------------------------------------------------------------
  // Submit / Cancel
  // ---------------------------------------------------------------------------

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return
    this.isSubmitting.set(true)
    this.errorMessage.set(null)

    const isEdit = this.isEditMode() && !!this.existingDefinition?.id
    const endType = this.endTypeControl.value

    const payload: CaseDefinition = {
      ...(this.existingDefinition ?? {}),
      namespaceId: this.namespaceId ?? null,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      // agentConfigId is immutable post-creation: keep existing value on edit
      agentConfigId: isEdit ? this.existingDefinition!.agentConfigId : this.agentConfigIdControl.value,
      promptContent: this.promptContentControl.value.trim(),
      recurrence: {
        every: this.everyControl.value,
        unit: this.unitControl.value,
        days: this.daysControl.value,
        timeUtc: this.timeUtcControl.value,
      },
      planning: {
        startDate: this.startDateControl.value,
        endType,
        endDate: endType === SchedulerEndType.ON_DATE ? (this.endDateControl.value ?? undefined) : null,
        occurrenceCount:
          endType === SchedulerEndType.OCCURRENCES ? (this.occurrenceCountControl.value ?? undefined) : null,
      },
      enabled: this.enabledControl.value,
    }

    const call$ = isEdit
      ? this.caseDefState.update(this.existingDefinition!.id!, payload)
      : this.caseDefState.create(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: HttpErrorResponse) => {
        this.isSubmitting.set(false)
        if (err.status === 409) {
          this.errorMessage.set(`A case definition named "${payload.name}" already exists in this scope.`)
        } else if (err.status === 400) {
          this.errorMessage.set(err.error?.message ?? 'Invalid case definition data.')
        } else {
          this.errorMessage.set('An unexpected error occurred. Please try again.')
        }
      },
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'case-definitions'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'case-definitions'])
    }
  }
}

// ---------------------------------------------------------------------------
// Cross-field validators
// ---------------------------------------------------------------------------

/** endDate is required when endType === ON_DATE. */
function endDateRequiredValidator(group: AbstractControl): ValidationErrors | null {
  const endType = group.get('endType')?.value as SchedulerEndType | undefined
  const endDate = group.get('endDate')?.value as string | null | undefined
  if (endType === SchedulerEndType.ON_DATE && !endDate) {
    return { endDateRequired: true }
  }
  return null
}

/** occurrenceCount is required and ≥ 1 when endType === OCCURRENCES. */
function occurrenceCountRequiredValidator(group: AbstractControl): ValidationErrors | null {
  const endType = group.get('endType')?.value as SchedulerEndType | undefined
  const count = group.get('occurrenceCount')?.value as number | null | undefined
  if (endType === SchedulerEndType.OCCURRENCES && (count === null || count === undefined || count < 1)) {
    return { occurrenceCountRequired: true }
  }
  return null
}

/** Returns today's date as an ISO date string (YYYY-MM-DD), local timezone. */
function todayIsoDate(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
