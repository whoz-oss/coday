import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { HttpErrorResponse } from '@angular/common/http'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { startWith } from 'rxjs'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  CaseDefinition,
  CaseDefinitionFrequency,
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
 * frequency (DAILY|WEEKLY) + timeUtc (HH:mm) + optional dayOfWeek (WEEKLY only).
 * The backend converts these to a 5-field cron expression for storage.
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

  protected readonly form = new FormGroup({
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
    frequency: new FormControl<CaseDefinitionFrequency>('DAILY', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    dayOfWeek: new FormControl<string | null>(null),
    timeUtc: new FormControl<string>('09:00', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^([01]\d|2[0-3]):[0-5]\d$/)],
    }),
    enabled: new FormControl<boolean>(true, { nonNullable: true }),
  })

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
  protected get frequencyControl() {
    return this.form.controls.frequency
  }
  protected get dayOfWeekControl() {
    return this.form.controls.dayOfWeek
  }
  protected get timeUtcControl() {
    return this.form.controls.timeUtc
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

  protected readonly frequencies: CaseDefinitionFrequency[] = ['DAILY', 'WEEKLY']

  protected readonly daysOfWeek: { value: string; label: string }[] = [
    { value: 'MON', label: 'Monday' },
    { value: 'TUE', label: 'Tuesday' },
    { value: 'WED', label: 'Wednesday' },
    { value: 'THU', label: 'Thursday' },
    { value: 'FRI', label: 'Friday' },
    { value: 'SAT', label: 'Saturday' },
    { value: 'SUN', label: 'Sunday' },
  ]

  /**
   * Signal derived from the frequency control value changes.
   * Drives conditional display and validation of dayOfWeek.
   */
  private readonly frequencySignal = toSignal(
    this.frequencyControl.valueChanges.pipe(startWith(this.frequencyControl.value))
  )
  protected readonly isWeekly = computed(() => this.frequencySignal() === 'WEEKLY')

  /** Available AgentConfigs for the scope. */
  private readonly agentConfigs = signal<AgentConfig[]>([])
  protected readonly availableAgentConfigs = this.agentConfigs.asReadonly()

  /** Kept in edit mode to preserve immutable fields (id, namespaceId, agentConfigId). */
  private existingDefinition: CaseDefinition | null = null

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  ngOnInit(): void {
    // Sync dayOfWeek validators when frequency changes
    this.frequencyControl.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((freq) => {
      if (freq === 'WEEKLY') {
        this.dayOfWeekControl.setValidators([Validators.required])
      } else {
        this.dayOfWeekControl.clearValidators()
        this.dayOfWeekControl.setValue(null)
      }
      this.dayOfWeekControl.updateValueAndValidity()
    })

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
    this.frequencyControl.setValue(def.frequency)
    // Sync dayOfWeek validators before setting its value
    if (def.frequency === 'WEEKLY') {
      this.dayOfWeekControl.setValidators([Validators.required])
      this.dayOfWeekControl.updateValueAndValidity()
    }
    this.dayOfWeekControl.setValue(def.dayOfWeek ?? null)
    this.timeUtcControl.setValue(def.timeUtc)
    this.enabledControl.setValue(def.enabled)
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

    const payload: CaseDefinition = {
      ...(this.existingDefinition ?? {}),
      namespaceId: this.namespaceId ?? null,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      // agentConfigId is immutable post-creation: keep existing value on edit
      agentConfigId: isEdit ? this.existingDefinition!.agentConfigId : this.agentConfigIdControl.value,
      promptContent: this.promptContentControl.value.trim(),
      frequency: this.frequencyControl.value,
      dayOfWeek: this.frequencyControl.value === 'WEEKLY' ? (this.dayOfWeekControl.value ?? undefined) : undefined,
      timeUtc: this.timeUtcControl.value,
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
