import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { NamespaceControllerService, Namespace } from '@whoz-oss/agentos-api-client'
import { ToolInvokeStateService } from '../../services/tool-invoke-state.service'

/**
 * ToolInvokeComponent — SUPER_ADMIN debug screen for calling a named tool directly.
 *
 * The user selects a namespace, optionally enters a user UUID, types the exact tool name
 * (e.g. `MY_FILES__listFiles`), and provides an optional JSON payload. On submit the
 * component calls ToolInvokeStateService which POSTs to /api/tools/invoke and displays
 * the raw ToolExecutionResult.
 *
 * Route: /agentos/admin/tool-invoke
 */
@Component({
  selector: 'agentos-tool-invoke',
  imports: [ReactiveFormsModule],
  templateUrl: './tool-invoke.component.html',
  styleUrl: './tool-invoke.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ToolInvokeComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef)
  private readonly namespaceController = inject(NamespaceControllerService)
  protected readonly state = inject(ToolInvokeStateService)

  protected readonly namespaces = signal<Namespace[]>([])
  protected readonly namespacesLoading = signal(true)

  protected readonly form = new FormGroup({
    namespaceId: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
    userId: new FormControl<string>('', { nonNullable: true }),
    toolName: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
    payload: new FormControl<string>('', { nonNullable: true }),
  })

  ngOnInit(): void {
    this.namespaceController
      .listAllNamespace()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.namespaces.set(list.map((item) => ({ id: item.id, name: item.name })))
          this.namespacesLoading.set(false)
        },
        error: () => this.namespacesLoading.set(false),
      })
  }

  protected submit(): void {
    if (this.form.invalid || this.state.isLoading()) return

    const { namespaceId, userId, toolName, payload } = this.form.getRawValue()

    this.state
      .invoke(namespaceId, userId.trim() || undefined, toolName.trim(), payload.trim() || undefined)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }

  protected reset(): void {
    this.state.reset()
  }

  /**
   * Pretty-print the metadata object for display.
   * Returns null when the metadata is empty so the section is hidden.
   */
  protected formatMetadata(metadata: Record<string, unknown>): string | null {
    if (!metadata || Object.keys(metadata).length === 0) return null
    return JSON.stringify(metadata, null, 2)
  }
}
