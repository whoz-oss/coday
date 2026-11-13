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
  AgentConfigExecutionModeEnum,
  DAY_OF_WEEK_LABELS,
  DayOfWeek,
  ScheduledPrompt,
  SchedulerEndType,
  SchedulerUnit,
} from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, of, Observable } from 'rxjs'
import { ScheduledPromptStateService } from '../../services/scheduled-prompt-state.service'

/**
 * ScheduledPromptFormComponent — full-page create / edit form for a scheduled prompt.
 *
 * Mode is determined by the presence of `:scheduledPromptId` in the route params:
 * - `/:namespaceId/scheduled-prompts/new`                          → create mode (namespace scope)
 * - `/:namespaceId/scheduled-prompts/:scheduledPromptId/edit`       → edit mode (namespace scope)
 * - `/admin/scheduled-prompts/new`                                 → create mode (platform scope)
 * - `/admin/scheduled-prompts/:scheduledPromptId/edit`             → edit mode (platform scope)
 *
 * Platform mode is detected when `namespaceId` is absent from the route params.
 */
@Component({
  selector: 'agentos-scheduled-prompt-form',
  imports: [ReactiveFormsModule],
  templateUrl: './scheduled-prompt-form.component.html',
  styleUrl: './scheduled-prompt-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScheduledPromptFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(ScheduledPromptStateService)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  /** True when loaded from /admin/scheduled-prompts — platform scope, no namespaceId in route. */
  protected readonly isPlatformMode = !this.namespaceId

  // ---------------------------------------------------------------------------
  // Form structure
  // ---------------------------------------------------------------------------

  protected readonly form = new FormGroup(
    {
      // Identity
      name: new FormControl<string>('', {
        nonNullable: true,
        validators: [Validators.required],
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
      unit: new FormControl<SchedulerUnit>(SchedulerUnit.WEEK, {
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

      // Loop mode (experimental). The three fields below are only enabled while `loopEnabled` is on,
      // so a hidden, half-typed value can never block the submit of a historical-mode prompt.
      loopEnabled: new FormControl<boolean>(false, { nonNullable: true }),
      agentLoopName: new FormControl<string>('', { nonNullable: true }),
      searchToolName: new FormControl<string>('', { nonNullable: true }),
      searchToolArgsJson: new FormControl<string>('', {
        nonNullable: true,
        validators: [jsonObjectValidator],
      }),

      // State
      enabled: new FormControl<boolean>(true, { nonNullable: true }),

      // External metadata
      externalMetadataJson: new FormControl<string>('', {
        nonNullable: true,
        validators: [jsonValidator],
      }),
    },
    { validators: [endDateRequiredValidator, occurrenceCountRequiredValidator, loopModeRequiredValidator] }
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
  protected get loopEnabledControl() {
    return this.form.controls.loopEnabled
  }
  protected get agentLoopNameControl() {
    return this.form.controls.agentLoopName
  }
  protected get searchToolNameControl() {
    return this.form.controls.searchToolName
  }
  protected get searchToolArgsJsonControl() {
    return this.form.controls.searchToolArgsJson
  }
  protected get enabledControl() {
    return this.form.controls.enabled
  }
  protected get externalMetadataJsonControl() {
    return this.form.controls.externalMetadataJson
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
    { value: SchedulerUnit.WEEK, label: 'Week(s)' },
    { value: SchedulerUnit.MONTH, label: 'Month(s)' },
  ]

  protected readonly endTypes: { value: SchedulerEndType; label: string }[] = [
    { value: SchedulerEndType.NEVER, label: 'Never' },
    { value: SchedulerEndType.ON_DATE, label: 'On date' },
    { value: SchedulerEndType.OCCURRENCES, label: 'After N occurrences' },
  ]

  protected readonly dayOptions: { value: DayOfWeek; label: string }[] = (
    Object.entries(DAY_OF_WEEK_LABELS) as [DayOfWeek, string][]
  ).map(([value, label]) => ({ value, label }))

  private readonly unitSignal = toSignal(this.unitControl.valueChanges.pipe(startWith(this.unitControl.value)))
  private readonly endTypeSignal = toSignal(this.endTypeControl.valueChanges.pipe(startWith(this.endTypeControl.value)))

  private readonly loopEnabledSignal = toSignal(
    this.loopEnabledControl.valueChanges.pipe(startWith(this.loopEnabledControl.value))
  )

  protected readonly isLoopMode = computed(() => !!this.loopEnabledSignal())
  protected readonly showDayFilter = computed(() => this.unitSignal() === SchedulerUnit.WEEK)
  protected readonly showEndDate = computed(() => this.endTypeSignal() === SchedulerEndType.ON_DATE)
  protected readonly showOccurrenceCount = computed(() => this.endTypeSignal() === SchedulerEndType.OCCURRENCES)

  private readonly agentConfigs = signal<AgentConfig[]>([])
  protected readonly availableAgentConfigs = this.agentConfigs.asReadonly()

  /**
   * Platform-level agent configs, loaded in namespace mode only: `listByParent` does not return them,
   * yet the loop agent is resolved by name in the namespace then at platform level. In platform mode
   * they are already part of `agentConfigs`.
   */
  private readonly platformAgentConfigs = signal<AgentConfig[]>([])

  /** Names of the enabled LOOP-mode agent configs, suggested for the free-text "Loop agent" field. */
  protected readonly loopAgentSuggestions = computed(() => {
    const names = [...this.agentConfigs(), ...this.platformAgentConfigs()]
      .filter((c) => c.executionMode === AgentConfigExecutionModeEnum.LOOP && c.enabled !== false)
      .map((c) => c.name)
    return [...new Set(names)]
  })

  /** Placeholders the loop agent's `steps` loopConfig reads from the session context (`loopData`). */
  protected readonly loopDataPlaceholders = [
    '${loopData.searchToolName}',
    '${loopData.searchToolArgs}',
    '${loopData.agentName}',
    '${loopData.prompt}',
    '${loopData.scheduledPromptId}',
  ]

  private existingPrompt: ScheduledPrompt | null = null

  constructor() {
    this.loopEnabledControl.valueChanges
      .pipe(startWith(this.loopEnabledControl.value), takeUntilDestroyed(this.destroyRef))
      .subscribe((enabled) => this.setLoopControlsEnabled(enabled))
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  ngOnInit(): void {
    this.startDateControl.setValue(todayIsoDate())

    const scheduledPromptId = this.route.snapshot.paramMap.get('scheduledPromptId')
    if (scheduledPromptId) {
      this.isEditMode.set(true)
      this.loadPromptAndResources(scheduledPromptId)
    } else {
      this.loadResources()
    }
    this.loadPlatformLoopAgents()
  }

  private loadPlatformLoopAgents(): void {
    if (this.isPlatformMode) return
    this.agentConfigController
      .listPlatformAgentsAgentConfig()
      .pipe(
        catchError(() => of([] as AgentConfig[])),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((configs) => this.platformAgentConfigs.set(configs))
  }

  private setLoopControlsEnabled(enabled: boolean): void {
    for (const control of [this.agentLoopNameControl, this.searchToolNameControl, this.searchToolArgsJsonControl]) {
      if (enabled) control.enable()
      else control.disable()
    }
  }

  private loadPromptAndResources(id: string): void {
    this.isLoading.set(true)
    forkJoin({
      prompt: this.state.getById(id),
      agentConfigs: this.agentConfigs$(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ prompt, agentConfigs }) => {
          this.existingPrompt = prompt
          this.agentConfigs.set(agentConfigs)
          this.hydrateForm(prompt)
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
      .subscribe((agentConfigs) => this.agentConfigs.set(agentConfigs))
  }

  private agentConfigs$(): Observable<AgentConfig[]> {
    const call$ = this.isPlatformMode
      ? this.agentConfigController.listPlatformAgentsAgentConfig()
      : this.agentConfigController.listByParentAgentConfig(this.namespaceId!)
    return call$.pipe(catchError(() => of([] as AgentConfig[])))
  }

  private hydrateForm(def: ScheduledPrompt): void {
    this.nameControl.setValue(def.name)
    this.descriptionControl.setValue(def.description ?? null)
    this.agentConfigIdControl.setValue(def.agentConfigId)
    this.promptContentControl.setValue(def.promptContent)
    this.unitControl.setValue(def.recurrence.unit)
    this.daysControl.setValue(def.recurrence.days ?? [])
    this.timeUtcControl.setValue(def.recurrence.timeUtc)
    this.startDateControl.setValue(def.planning.startDate)
    this.endTypeControl.setValue(def.planning.endType)
    this.endDateControl.setValue(def.planning.endDate ?? null)
    this.occurrenceCountControl.setValue(def.planning.maxOccurrenceCount ?? null)
    this.loopEnabledControl.setValue(!!def.agentLoopName)
    this.agentLoopNameControl.setValue(def.agentLoopName ?? '')
    this.searchToolNameControl.setValue(def.loopConfiguration?.searchToolName ?? '')
    const searchToolArgs = def.loopConfiguration?.searchToolArgs
    this.searchToolArgsJsonControl.setValue(searchToolArgs != null ? JSON.stringify(searchToolArgs, null, 2) : '')
    this.enabledControl.setValue(def.enabled)
    this.externalMetadataJsonControl.setValue(def.externalMetadata ? JSON.stringify(def.externalMetadata, null, 2) : '')
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

    const isEdit = this.isEditMode() && !!this.existingPrompt?.id
    const endType = this.endTypeControl.value
    const loopOn = this.loopEnabledControl.value

    const payload: ScheduledPrompt = {
      ...(this.existingPrompt ?? {}),
      namespaceId: this.namespaceId ?? null,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      agentConfigId: isEdit ? this.existingPrompt!.agentConfigId : this.agentConfigIdControl.value,
      promptContent: this.promptContentControl.value.trim(),
      recurrence: {
        unit: this.unitControl.value,
        days: this.daysControl.value,
        timeUtc: this.timeUtcControl.value,
      },
      planning: {
        startDate: this.startDateControl.value,
        endType,
        endDate: endType === SchedulerEndType.ON_DATE ? (this.endDateControl.value ?? undefined) : undefined,
        maxOccurrenceCount:
          endType === SchedulerEndType.OCCURRENCES ? (this.occurrenceCountControl.value ?? undefined) : undefined,
      },
      enabled: this.enabledControl.value,
      // Set explicitly: the payload spreads the existing prompt, and an omitted field on PUT is what
      // clears the loop mode (back to the historical mode). Both are set together, or neither.
      agentLoopName: loopOn ? this.agentLoopNameControl.value.trim() : undefined,
      loopConfiguration: loopOn
        ? {
            searchToolName: this.searchToolNameControl.value.trim(),
            searchToolArgs: this.parseSearchToolArgs(),
          }
        : undefined,
      removed: this.existingPrompt?.removed ?? false,
      externalMetadata: (() => {
        const raw = this.externalMetadataJsonControl.value.trim()
        return raw ? JSON.parse(raw) : null
      })(),
    }

    const call$ = isEdit ? this.state.update(this.existingPrompt!.id!, payload) : this.state.create(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: HttpErrorResponse) => {
        this.isSubmitting.set(false)
        if (err.status === 409) {
          this.errorMessage.set(`A scheduled prompt named "${payload.name}" already exists in this scope.`)
        } else if (err.status === 400) {
          this.errorMessage.set(err.error?.message ?? 'Invalid scheduled prompt data.')
        } else {
          this.errorMessage.set('An unexpected error occurred. Please try again.')
        }
      },
    })
  }

  /** Search tool input: the parsed JSON object, or null (= `{}` server-side) when left empty. */
  private parseSearchToolArgs(): object | null {
    const raw = this.searchToolArgsJsonControl.value.trim()
    return raw ? (JSON.parse(raw) as object) : null
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'scheduled-prompts'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'scheduled-prompts'])
    }
  }
}

// ---------------------------------------------------------------------------
// Cross-field validators
// ---------------------------------------------------------------------------

function endDateRequiredValidator(group: AbstractControl): ValidationErrors | null {
  const endType = group.get('endType')?.value as SchedulerEndType | undefined
  const endDate = group.get('endDate')?.value as string | null | undefined
  if (endType === SchedulerEndType.ON_DATE && !endDate) {
    return { endDateRequired: true }
  }
  return null
}

function occurrenceCountRequiredValidator(group: AbstractControl): ValidationErrors | null {
  const endType = group.get('endType')?.value as SchedulerEndType | undefined
  const count = group.get('occurrenceCount')?.value as number | null | undefined
  if (endType === SchedulerEndType.OCCURRENCES && (count === null || count === undefined || count < 1)) {
    return { occurrenceCountRequired: true }
  }
  return null
}

/** Mirror of the backend `isLoopModeConsistent` + `@NotBlank searchToolName`. */
function loopModeRequiredValidator(group: AbstractControl): ValidationErrors | null {
  if (!group.get('loopEnabled')?.value) return null
  const errors: ValidationErrors = {}
  if (!(group.get('agentLoopName')?.value as string | undefined)?.trim()) errors['loopAgentRequired'] = true
  if (!(group.get('searchToolName')?.value as string | undefined)?.trim()) errors['loopSearchToolRequired'] = true
  return Object.keys(errors).length > 0 ? errors : null
}

/** Empty, or a JSON *object*: the loop requires the SEARCH args to resolve to an object. */
function jsonObjectValidator(control: AbstractControl): ValidationErrors | null {
  const value = (control.value as string)?.trim()
  if (!value) return null
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? null : { notJsonObject: true }
  } catch {
    return { invalidJson: true }
  }
}

function jsonValidator(control: AbstractControl): ValidationErrors | null {
  const value = (control.value as string)?.trim()
  if (!value) return null
  try {
    JSON.parse(value)
    return null
  } catch {
    return { invalidJson: true }
  }
}

function todayIsoDate(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
