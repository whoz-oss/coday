import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
} from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, JsonSchemaFormComponent, JsonSchemaObject } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

/**
 * NamespaceIntegrationsComponent — smart container for integration config management.
 *
 * Loaded at /:namespaceId/integrations. Responsibilities:
 * - Load and display the list of integration configs for the current namespace
 * - Inline creation form (name + integrationType selector + dynamic schema-driven parameters)
 * - Inline edit form
 * - Deletion with confirmation (delegated to IntegrationConfigItemComponent)
 * - Load available integration types from the server to populate the type selector
 *
 * Parameters are edited via ds-json-schema-form when the selected integration type
 * provides a configSchema. When schema is null (no parameters needed), the
 * parameters section is hidden entirely.
 */
@Component({
  selector: 'agentos-namespace-integrations',
  standalone: true,
  imports: [
    AsyncPipe,
    ReactiveFormsModule,
    IconButtonComponent,
    IntegrationConfigItemComponent,
    JsonSchemaFormComponent,
  ],
  templateUrl: './namespace-integrations.component.html',
  styleUrl: './namespace-integrations.component.scss',
})
export class NamespaceIntegrationsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)
  private readonly integrationTypeController = inject(IntegrationTypeControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.integrationConfigController.listByNamespace(this.namespaceId))
  )

  protected readonly integrationTypes$ = this.integrationTypeController.listTypes()

  /** All known integration type descriptors as a signal (for schema lookup) */
  protected readonly integrationTypes = toSignal(this.integrationTypes$, {
    initialValue: [] as IntegrationTypeDescriptor[],
  })

  // --- Create form ---

  protected readonly createNameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly createTypeControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required],
  })

  protected readonly isCreating = signal(false)
  protected readonly isSubmitting = signal(false)

  /** Parsed parameters value from ds-json-schema-form for the create form */
  protected readonly createParamsValue = signal<Record<string, unknown> | null>(null)

  /** Currently selected type key in the create form, as a signal */
  private readonly createSelectedType = toSignal(this.createTypeControl.valueChanges, {
    initialValue: this.createTypeControl.value,
  })

  /** Schema for the currently selected type in the create form */
  protected readonly createSchema = computed<JsonSchemaObject | null>(() => this.findSchema(this.createSelectedType()))

  // --- Edit form ---

  protected readonly editNameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly editTypeControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required],
  })

  protected readonly editingConfig = signal<IntegrationConfig | null>(null)
  protected readonly isEditSubmitting = signal(false)

  /** Parsed parameters value from ds-json-schema-form for the edit form */
  protected readonly editParamsValue = signal<Record<string, unknown> | null>(null)

  /** Current params to seed the edit form with when opening */
  protected readonly editInitialParams = signal<Record<string, unknown> | null>(null)

  /** Currently selected type key in the edit form, as a signal */
  private readonly editSelectedType = toSignal(this.editTypeControl.valueChanges, {
    initialValue: this.editTypeControl.value,
  })

  /** Schema for the currently selected type in the edit form */
  protected readonly editSchema = computed<JsonSchemaObject | null>(() => this.findSchema(this.editSelectedType()))

  // --- Navigation ---

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  // --- Create ---

  protected openCreateForm(): void {
    this.createNameControl.reset()
    this.createTypeControl.reset()
    this.createParamsValue.set(null)
    this.isCreating.set(true)
    this.editingConfig.set(null)
  }

  protected cancelCreate(): void {
    this.isCreating.set(false)
  }

  protected onCreateParamsChange(value: Record<string, unknown> | null): void {
    this.createParamsValue.set(value)
  }

  protected submitCreate(): void {
    if (this.createNameControl.invalid || this.createTypeControl.invalid || this.isSubmitting()) return

    // If a schema exists, params must be valid (non-null from the form)
    if (this.createSchema() !== null && this.createParamsValue() === null) return

    const payload = {
      name: this.createNameControl.value.trim(),
      integrationType: this.createTypeControl.value,
      namespaceId: this.namespaceId,
      parameters: this.createParamsValue(),
      metadata: {},
    } as unknown as IntegrationConfig

    this.isSubmitting.set(true)
    this.integrationConfigController
      .create1(payload)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.isCreating.set(false)
          this.isSubmitting.set(false)
          this.refresh$.next()
        },
        error: () => this.isSubmitting.set(false),
      })
  }

  // --- Edit ---

  protected openEditForm(config: IntegrationConfig): void {
    this.editNameControl.setValue(config.name)
    this.editTypeControl.setValue(config.integrationType)
    this.editInitialParams.set(config.parameters as Record<string, unknown> | null)
    this.editParamsValue.set(config.parameters as Record<string, unknown> | null)
    this.editingConfig.set(config)
    this.isCreating.set(false)
  }

  protected cancelEdit(): void {
    this.editingConfig.set(null)
  }

  protected onEditParamsChange(value: Record<string, unknown> | null): void {
    this.editParamsValue.set(value)
  }

  protected submitEdit(): void {
    const config = this.editingConfig()
    if (!config || this.editNameControl.invalid || this.editTypeControl.invalid || this.isEditSubmitting()) return

    // If a schema exists, params must be valid (non-null from the form)
    if (this.editSchema() !== null && this.editParamsValue() === null) return

    const payload: IntegrationConfig = {
      ...config,
      name: this.editNameControl.value.trim(),
      integrationType: this.editTypeControl.value,
      parameters: this.editParamsValue(),
    }

    this.isEditSubmitting.set(true)
    this.integrationConfigController
      .update1(config.id, payload)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.editingConfig.set(null)
          this.isEditSubmitting.set(false)
          this.refresh$.next()
        },
        error: () => this.isEditSubmitting.set(false),
      })
  }

  // --- Delete ---

  protected deleteConfig(config: IntegrationConfig): void {
    this.integrationConfigController
      .delete1(config.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected trackById(_index: number, config: IntegrationConfig): string {
    return config.id
  }

  protected trackByType(_index: number, descriptor: IntegrationTypeDescriptor): string {
    return descriptor.type
  }

  /**
   * Returns the configSchema for a given integration type key,
   * or null if the type is unknown or has no schema.
   */
  private findSchema(typeKey: string): JsonSchemaObject | null {
    if (!typeKey) return null
    const descriptor = this.integrationTypes().find((d) => d.type === typeKey)
    return (descriptor?.configSchema as JsonSchemaObject) ?? null
  }
}
