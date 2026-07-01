import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { startWith } from 'rxjs'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  AgentConfigControllerService,
  CaseDefinition,
  CaseDefinitionApiService,
  CaseDefinitionFrequency,
} from '@whoz-oss/agentos-api-client'
import { forkJoin } from 'rxjs'

/**
 * CaseDefinitionFormComponent — full-page create / edit form for a case definition.
 *
 * Mode is determined by the presence of `:caseDefinitionId` in the route params:
 * - `/:namespaceId/case-definitions/new`                        → create mode
 * - `/:namespaceId/case-definitions/:caseDefinitionId/edit`      → edit mode
 *
 * On success or cancel, navigates back to /:namespaceId/case-definitions.
 */
@Component({
  selector: 'agentos-case-definition-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './case-definition-form.component.html',
  styleUrl: './case-definition-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDefinitionFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly caseDefinitionApi = inject(CaseDefinitionApiService)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    agentId: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    prompt: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    frequency: new FormControl<CaseDefinitionFrequency>('DAILY', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    dayOfWeek: new FormControl<string | null>(null),
    timeUtc: new FormControl<string>('09:00', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/^\d{2}:\d{2}$/)],
    }),
    enabled: new FormControl<boolean>(true, { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get agentIdControl() {
    return this.form.controls.agentId
  }
  protected get promptControl() {
    return this.form.controls.prompt
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

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  protected readonly availableAgents = signal<AgentConfig[]>([])
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
   * Drives the conditional display and validation of dayOfWeek.
   * Uses startWith to emit the current value immediately.
   */
  protected readonly isWeekly = computed(() => this.frequencySignal() === 'WEEKLY')
  private readonly frequencySignal = toSignal(
    this.frequencyControl.valueChanges.pipe(startWith(this.frequencyControl.value))
  )

  /** Kept in edit mode to retrieve the id for the PUT path param. */
  private existingDefinition: CaseDefinition | null = null

  ngOnInit(): void {
    // Keep dayOfWeek validation in sync with frequency changes.
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
      this.loadDefinitionAndAgents(caseDefinitionId)
    } else {
      this.loadAgents()
    }
  }

  private loadDefinitionAndAgents(caseDefinitionId: string): void {
    this.isLoading.set(true)
    forkJoin({
      definition: this.caseDefinitionApi.getById(this.namespaceId, caseDefinitionId),
      agents: this.agentConfigController.listByParentAgentConfig(this.namespaceId),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ definition, agents }) => {
          this.existingDefinition = definition
          this.availableAgents.set(agents)
          this.nameControl.setValue(definition.name)
          this.descriptionControl.setValue(definition.description ?? null)
          this.agentIdControl.setValue(definition.agentId)
          this.promptControl.setValue(definition.prompt)
          this.frequencyControl.setValue(definition.frequency)
          // Trigger validation sync before setting dayOfWeek value.
          if (definition.frequency === 'WEEKLY') {
            this.dayOfWeekControl.setValidators([Validators.required])
            this.dayOfWeekControl.updateValueAndValidity()
          }
          this.dayOfWeekControl.setValue(definition.dayOfWeek ?? null)
          this.timeUtcControl.setValue(definition.timeUtc)
          this.enabledControl.setValue(definition.enabled)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  private loadAgents(): void {
    this.agentConfigController
      .listByParentAgentConfig(this.namespaceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (agents) => this.availableAgents.set(agents),
      })
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    // Build an explicit payload — id is in the URL path param, not needed in the body.
    const payload: CaseDefinition = {
      namespaceId: this.namespaceId,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      agentId: this.agentIdControl.value,
      prompt: this.promptControl.value.trim(),
      frequency: this.frequencyControl.value,
      dayOfWeek: this.frequencyControl.value === 'WEEKLY' ? (this.dayOfWeekControl.value ?? undefined) : undefined,
      timeUtc: this.timeUtcControl.value,
      enabled: this.enabledControl.value,
    }

    const call$ = this.isEditMode()
      ? this.caseDefinitionApi.update(this.namespaceId, this.existingDefinition!.id ?? '', payload)
      : this.caseDefinitionApi.create(this.namespaceId, payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'case-definitions'])
  }
}
