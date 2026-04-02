import { AsyncPipe } from '@angular/common'
import { Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import {
  IntegrationConfig,
  IntegrationConfigControllerService,
  IntegrationTypeControllerService,
  IntegrationTypeDescriptor,
} from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { IntegrationConfigItemComponent } from '../integration-config-item/integration-config-item.component'

/**
 * NamespaceIntegrationsComponent — smart container for integration config management.
 *
 * Loaded at /:namespaceId/integrations. Responsibilities:
 * - Load and display the list of integration configs for the current namespace
 * - Inline creation form (name + integrationType selector + optional JSON parameters)
 * - Inline edit form
 * - Deletion with confirmation (delegated to IntegrationConfigItemComponent)
 * - Load available integration types from the server to populate the type selector
 */
@Component({
  selector: 'agentos-namespace-integrations',
  standalone: true,
  imports: [AsyncPipe, ReactiveFormsModule, IconButtonComponent, IntegrationConfigItemComponent],
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

  // --- Create form ---

  protected readonly createNameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly createTypeControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required],
  })
  protected readonly createParamsControl = new FormControl<string>('', { nonNullable: true })

  protected readonly isCreating = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly createParamsError = signal<string | null>(null)

  // --- Edit form ---

  protected readonly editNameControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required, Validators.minLength(1)],
  })
  protected readonly editTypeControl = new FormControl<string>('', {
    nonNullable: true,
    validators: [Validators.required],
  })
  protected readonly editParamsControl = new FormControl<string>('', { nonNullable: true })

  protected readonly editingConfig = signal<IntegrationConfig | null>(null)
  protected readonly isEditSubmitting = signal(false)
  protected readonly editParamsError = signal<string | null>(null)

  // --- Navigation ---

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  // --- Create ---

  protected openCreateForm(): void {
    this.createNameControl.reset()
    this.createTypeControl.reset()
    this.createParamsControl.reset()
    this.createParamsError.set(null)
    this.isCreating.set(true)
    this.editingConfig.set(null)
  }

  protected cancelCreate(): void {
    this.isCreating.set(false)
  }

  protected submitCreate(): void {
    if (this.createNameControl.invalid || this.createTypeControl.invalid || this.isSubmitting()) return

    const parsedParams = this.parseParams(this.createParamsControl.value, this.createParamsError)
    if (parsedParams === false) return

    const payload = {
      name: this.createNameControl.value.trim(),
      integrationType: this.createTypeControl.value,
      namespaceId: this.namespaceId,
      parameters: parsedParams,
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
    this.editParamsControl.setValue(config.parameters ? JSON.stringify(config.parameters, null, 2) : '')
    this.editParamsError.set(null)
    this.editingConfig.set(config)
    this.isCreating.set(false)
  }

  protected cancelEdit(): void {
    this.editingConfig.set(null)
  }

  protected submitEdit(): void {
    const config = this.editingConfig()
    if (!config || this.editNameControl.invalid || this.editTypeControl.invalid || this.isEditSubmitting()) return

    const parsedParams = this.parseParams(this.editParamsControl.value, this.editParamsError)
    if (parsedParams === false) return

    const payload: IntegrationConfig = {
      ...config,
      name: this.editNameControl.value.trim(),
      integrationType: this.editTypeControl.value,
      parameters: parsedParams,
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
   * Parses a JSON string from a textarea.
   * Returns the parsed value (or null if empty), or false if invalid JSON.
   * Sets the error signal accordingly.
   */
  private parseParams(
    raw: string,
    errorSignal: ReturnType<typeof signal<string | null>>
  ): Record<string, unknown> | null | false {
    const trimmed = raw.trim()
    if (!trimmed) {
      errorSignal.set(null)
      return null
    }
    try {
      const parsed = JSON.parse(trimmed)
      errorSignal.set(null)
      return parsed
    } catch {
      errorSignal.set('Invalid JSON')
      return false
    }
  }
}
