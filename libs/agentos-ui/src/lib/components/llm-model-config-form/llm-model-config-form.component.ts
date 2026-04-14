import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { catchError, of } from 'rxjs'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { LlmConfig, AiProviderControllerService, AiModel, AiModelControllerService } from '@whoz-oss/agentos-api-client'

/**
 * LlmModelConfigFormComponent — full-page create / edit form for an LLM model.
 *
 * Mode is determined by the presence of `:modelId` in the route params:
 * - `/:namespaceId/llm-models/new`               → create mode
 * - `/:namespaceId/llm-models/:modelId/edit`      → edit mode
 *
 * The namespaceId is fixed at creation time and never shown as an editable field.
 * The llmConfigId (provider) is chosen from a select in create mode and becomes
 * read-only in edit mode — it cannot be reassigned after creation.
 *
 * On success or cancel, navigates back to /:namespaceId/llm-models.
 */
@Component({
  selector: 'agentos-llm-model-config-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './llm-model-config-form.component.html',
  styleUrl: './llm-model-config-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LlmModelConfigFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiModelController = inject(AiModelControllerService)
  private readonly aiProviderController = inject(AiProviderControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  /** All providers for this namespace — used to populate the provider select. */
  protected readonly providers = toSignal(
    this.aiProviderController.listByParentAiProvider(this.namespaceId).pipe(catchError(() => of([] as LlmConfig[]))),
    { initialValue: [] as LlmConfig[] }
  )

  protected readonly form = new FormGroup({
    aiProviderId: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    apiName: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    alias: new FormControl<string>('', { nonNullable: true }),
    priority: new FormControl<number>(0, { nonNullable: true }),
    temperature: new FormControl<number | null>(null),
    maxTokens: new FormControl<number | null>(null),
  })

  protected get aiProviderIdControl() {
    return this.form.controls.aiProviderId
  }
  protected get apiNameControl() {
    return this.form.controls.apiName
  }
  protected get aliasControl() {
    return this.form.controls.alias
  }
  protected get priorityControl() {
    return this.form.controls.priority
  }
  protected get temperatureControl() {
    return this.form.controls.temperature
  }
  protected get maxTokensControl() {
    return this.form.controls.maxTokens
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /**
   * Display label for the locked provider field in edit mode.
   * Derived reactively from providers + aiProviderId so it resolves correctly
   * regardless of which signal settles first (providers load vs model load).
   */
  protected readonly selectedProviderLabel = computed(() => {
    const id = this.aiProviderIdControl.value
    const provider = this.providers().find((p) => p.id === id)
    return provider ? `${provider.name} (${provider.apiType})` : id
  })

  /** Kept for the update payload (preserves server-side fields). */
  private existingModel: AiModel | null = null

  ngOnInit(): void {
    const modelId = this.route.snapshot.paramMap.get('modelId')
    if (modelId) {
      this.isEditMode.set(true)
      this.loadModel(modelId)
    }
  }

  private loadModel(id: string): void {
    this.isLoading.set(true)
    this.aiModelController
      .getByIdAiModel(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (model) => {
          this.existingModel = model
          this.aiProviderIdControl.setValue(model.aiProviderId)
          // Provider cannot be changed after creation — lock the control
          this.aiProviderIdControl.disable()
          this.apiNameControl.setValue(model.apiName)
          this.aliasControl.setValue(model.alias ?? '')
          this.priorityControl.setValue(model.priority ?? 0)
          this.temperatureControl.setValue(model.temperature ?? null)
          this.maxTokensControl.setValue(model.maxTokens ?? null)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    // getRawValue() includes disabled controls (aiProviderId in edit mode)
    const raw = this.form.getRawValue()

    const payload: AiModel = {
      ...(this.existingModel ?? {}),
      aiProviderId: raw.aiProviderId,
      apiName: raw.apiName.trim(),
      alias: raw.alias.trim() || undefined,
      priority: raw.priority,
      temperature: raw.temperature ?? undefined,
      maxTokens: raw.maxTokens ?? undefined,
    }

    const call$ = this.isEditMode()
      ? this.aiModelController.updateAiModel(this.existingModel!.id ?? '', payload)
      : this.aiModelController.createAiModel(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'llm-models'])
  }

  protected trackByProvider(_index: number, provider: LlmConfig): string {
    return provider.id ?? ''
  }
}
