import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { catchError, of } from 'rxjs'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AiProvider,
  AiProviderControllerService,
  AiModel,
  AiModelControllerService,
} from '@whoz-oss/agentos-api-client'

/**
 * AiModelFormComponent — full-page create / edit form for an AI model.
 *
 * Mode is determined by the presence of `:modelId` in the route params:
 * - `/:namespaceId/ai-models/new`               → create mode
 * - `/:namespaceId/ai-models/:modelId/edit`      → edit mode
 *
 * The namespaceId is fixed at creation time and never shown as an editable field.
 * The aiProviderId (provider) is chosen from a select in create mode and becomes
 * read-only in edit mode — it cannot be reassigned after creation.
 *
 * On success or cancel, navigates back to /:namespaceId/ai-models.
 */
@Component({
  selector: 'agentos-ai-model-form',
  imports: [ReactiveFormsModule],
  templateUrl: './ai-model-form.component.html',
  styleUrl: './ai-model-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiModelController = inject(AiModelControllerService)
  private readonly aiProviderController = inject(AiProviderControllerService)

  /**
   * Namespace scoping this form. `undefined` when loaded from an admin route
   * (`admin/ai-models/new`, `admin/ai-models/:id/edit`) — platform context.
   * A concrete UUID when loaded from `/:namespaceId/ai-models/*`.
   */
  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined

  /**
   * Whether this form is operating in platform-admin context.
   * True when there is no `:namespaceId` param in the route (admin/* routes).
   * In platform mode:
   * - providers are loaded from the platform scope (namespaceId IS NULL)
   * - create payload has namespaceId: undefined (omitted → backend infers platform scope)
   * - navigateBack() returns to /agentos/admin/ai-models
   */
  protected readonly isPlatformMode = this.namespaceId === undefined

  /**
   * All providers for the current scope — used to populate the provider select.
   * In platform mode, loads platform-level providers (listAiProvider with namespaceId='none'
   * sentinel — the only place where the sentinel is used, at the API boundary).
   * In namespace mode, loads namespace-scoped providers.
   */
  protected readonly providers = toSignal(
    this.aiProviderController
      .listAiProvider(this.isPlatformMode ? 'none' : this.namespaceId)
      .pipe(catchError(() => of([] as AiProvider[]))),
    { initialValue: [] as AiProvider[] }
  )

  protected readonly form = new FormGroup({
    aiProviderId: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    apiModelName: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    alias: new FormControl<string>('', { nonNullable: true }),
    priority: new FormControl<number>(0, { nonNullable: true }),
    temperature: new FormControl<number | null>(null),
    maxTokens: new FormControl<number | null>(null),
  })

  protected get aiProviderIdControl() {
    return this.form.controls.aiProviderId
  }

  protected get apiModelNameControl() {
    return this.form.controls.apiModelName
  }

  protected get descriptionControl() {
    return this.form.controls.description
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
          this.apiModelNameControl.setValue(model.apiModelName)
          this.descriptionControl.setValue(model.description ?? null)
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
      // In platform mode, namespaceId is omitted (undefined) so the backend infers
      // platform scope (namespaceId IS NULL). In namespace mode, the existing model's
      // namespaceId is preserved via the spread above.
      namespaceId: this.isPlatformMode ? undefined : (this.existingModel?.namespaceId ?? this.namespaceId),
      apiModelName: raw.apiModelName.trim(),
      description: raw.description?.trim() || undefined,
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
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'ai-models'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId!, 'ai-models'])
    }
  }

  protected trackByProvider(_index: number, provider: AiProvider): string {
    return provider.id ?? ''
  }
}
