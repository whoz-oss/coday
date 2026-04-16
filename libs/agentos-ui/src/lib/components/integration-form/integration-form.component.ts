import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
} from '@whoz-oss/agentos-api-client'
import { JsonSchemaFormComponent, JsonSchemaObject } from '@whoz-oss/design-system'

/**
 * IntegrationFormComponent — full-page create / edit form for an integration config.
 *
 * Mode is determined by the presence of `:integrationId` in the route params:
 * - `/:namespaceId/integrations/new`                       → create mode
 * - `/:namespaceId/integrations/:integrationId/edit`       → edit mode
 *
 * On success or cancel, navigates back to /:namespaceId/integrations.
 */
@Component({
  selector: 'agentos-integration-form',
  standalone: true,
  imports: [ReactiveFormsModule, JsonSchemaFormComponent],
  templateUrl: './integration-form.component.html',
  styleUrl: './integration-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)
  private readonly integrationTypeController = inject(IntegrationTypeControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    type: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
  })

  protected get nameControl() {
    return this.form.controls.name
  }
  protected get descriptionControl() {
    return this.form.controls.description
  }
  protected get typeControl() {
    return this.form.controls.type
  }

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)

  /** Parsed parameters value from ds-json-schema-form */
  protected readonly paramsValue = signal<Record<string, unknown> | null>(null)

  /** Initial params to seed ds-json-schema-form on load */
  protected readonly initialParams = signal<Record<string, unknown> | null>(null)

  /** All known integration type descriptors */
  protected readonly integrationTypes = toSignal(this.integrationTypeController.listTypesIntegrationType(), {
    initialValue: [] as IntegrationTypeDescriptor[],
  })

  /** Currently selected type key as a signal — driven by typeControl value changes */
  private readonly selectedType = toSignal(this.form.controls.type.valueChanges, {
    initialValue: this.form.controls.type.value,
  })

  /** JSON Schema for the currently selected integration type, or null */
  protected readonly schema = computed<JsonSchemaObject | null>(() => {
    const typeKey = this.selectedType()
    if (!typeKey) return null
    const descriptor = this.integrationTypes().find((d) => d.type === typeKey)
    return (descriptor?.configSchema as JsonSchemaObject) ?? null
  })

  /** Kept for the update payload (preserves server-side fields) */
  private existingConfig: IntegrationConfig | null = null

  ngOnInit(): void {
    const integrationId = this.route.snapshot.paramMap.get('integrationId')
    if (integrationId) {
      this.isEditMode.set(true)
      this.loadConfig(integrationId)
    }
  }

  private loadConfig(id: string): void {
    this.isLoading.set(true)
    this.integrationConfigController
      .getByIdIntegrationConfig(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          this.existingConfig = config
          this.nameControl.setValue(config.name)
          this.descriptionControl.setValue(config.description ?? null)
          this.typeControl.setValue(config.integrationType)
          this.initialParams.set(config.parameters as Record<string, unknown> | null)
          this.paramsValue.set(config.parameters as Record<string, unknown> | null)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  protected onParamsChange(value: Record<string, unknown> | null): void {
    this.paramsValue.set(value)
  }

  protected submit(): void {
    if (this.nameControl.invalid || this.typeControl.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    const call$ = this.isEditMode()
      ? this.integrationConfigController.updateIntegrationConfig(this.existingConfig!.id ?? '', {
          ...this.existingConfig!,
          name: this.nameControl.value.trim(),
          description: this.descriptionControl.value?.trim() || null,
          integrationType: this.typeControl.value,
          parameters: this.paramsValue(),
        })
      : this.integrationConfigController.createIntegrationConfig({
          name: this.nameControl.value.trim(),
          description: this.descriptionControl.value?.trim() || null,
          integrationType: this.typeControl.value,
          namespaceId: this.namespaceId,
          parameters: this.paramsValue(),
        } as IntegrationConfig)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'integrations'])
  }

  protected trackByType(_index: number, descriptor: IntegrationTypeDescriptor): string {
    return descriptor.type
  }
}
