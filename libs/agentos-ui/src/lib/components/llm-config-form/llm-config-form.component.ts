import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { LlmConfig, LlmConfigApiTypeEnum, AiProviderControllerService } from '@whoz-oss/agentos-api-client'

/**
 * LlmConfigFormComponent — full-page create / edit form for an LLM provider.
 *
 * Mode is determined by the presence of `:llmConfigId` in the route params:
 * - `/:namespaceId/llm-configs/new`                  → create mode
 * - `/:namespaceId/llm-configs/:llmConfigId/edit`    → edit mode
 *
 * The namespaceId is fixed at creation time and never exposed as an editable field.
 * On success or cancel, navigates back to /:namespaceId/llm-configs.
 */
@Component({
  selector: 'agentos-llm-config-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './llm-config-form.component.html',
  styleUrl: './llm-config-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LlmConfigFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiProviderController = inject(AiProviderControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly apiTypeOptions = Object.values(LlmConfigApiTypeEnum)

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    apiType: new FormControl<LlmConfigApiTypeEnum>(LlmConfigApiTypeEnum.OpenAI, {
      nonNullable: true,
      validators: [Validators.required],
    }),
    baseUrl: new FormControl<string>('', { nonNullable: true }),
    apiKey: new FormControl<string>('', { nonNullable: true }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get apiTypeControl() {
    return this.form.controls.apiType
  }
  protected get baseUrlControl() {
    return this.form.controls.baseUrl
  }
  protected get apiKeyControl() {
    return this.form.controls.apiKey
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Kept for the update payload (preserves server-side fields). */
  private existingConfig: LlmConfig | null = null

  ngOnInit(): void {
    const llmConfigId = this.route.snapshot.paramMap.get('llmConfigId')
    if (llmConfigId) {
      this.isEditMode.set(true)
      this.loadConfig(llmConfigId)
    }
  }

  private loadConfig(id: string): void {
    this.isLoading.set(true)
    this.aiProviderController
      .getByIdAiProvider(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          this.existingConfig = config
          this.nameControl.setValue(config.name)
          this.apiTypeControl.setValue(config.apiType)
          this.baseUrlControl.setValue(config.baseUrl ?? '')
          this.apiKeyControl.setValue(config.apiKey ?? '')
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

    const payload: LlmConfig = {
      ...(this.existingConfig ?? {}),
      namespaceId: this.namespaceId,
      name: this.nameControl.value.trim(),
      apiType: this.apiTypeControl.value,
      baseUrl: this.baseUrlControl.value.trim() || undefined,
      apiKey: this.apiKeyControl.value.trim() || undefined,
    }

    const call$ = this.isEditMode()
      ? this.aiProviderController.updateAiProvider(this.existingConfig!.id ?? '', payload)
      : this.aiProviderController.createAiProvider(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'llm-configs'])
  }
}
