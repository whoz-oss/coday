import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatDialog } from '@angular/material/dialog'
import { MatIconModule } from '@angular/material/icon'
import { MatTableModule } from '@angular/material/table'
import { Observable, map } from 'rxjs'
import { FactoryApiError, FactoryApiService, WorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'
import { ConfirmDialogComponent, ConfirmDialogData } from './confirm-dialog.component'

/** Machine code rendered when the server refuses an admin command. */
export const FORBIDDEN_ADMIN_REQUIRED = 'FORBIDDEN_ADMIN_REQUIRED'

/** Structured error descriptor used to render an admin failure. */
export interface AdminErrorDescriptor {
  forbidden: boolean
  message: string
}

/**
 * Normalize an error into an escapable descriptor. The server is authoritative:
 * a `403` or a `FORBIDDEN_ADMIN_REQUIRED` code is always rendered verbatim
 * (never swallowed, never masked).
 */
export function describeAdminError(error: FactoryApiError): AdminErrorDescriptor {
  const forbidden = error?.status === 403 || error?.code === FORBIDDEN_ADMIN_REQUIRED
  const serverMessage = typeof error?.message === 'string' && error.message.trim() ? error.message.trim() : null
  if (forbidden) {
    const detail = serverMessage ?? 'admin rights required'
    return { forbidden: true, message: `Access denied: ${detail} (${FORBIDDEN_ADMIN_REQUIRED})` }
  }
  return { forbidden: false, message: serverMessage ?? 'Admin command failed (network or server error).' }
}

function extractDefinitions(payload: unknown): WorkflowDefinition[] {
  if (Array.isArray(payload)) return payload as WorkflowDefinition[]
  const items = (payload as { items?: unknown } | null)?.items
  return Array.isArray(items) ? (items as WorkflowDefinition[]) : []
}

/**
 * Administration page for workflow definitions.
 *
 * Server authorization is authoritative: any `403` / `FORBIDDEN_ADMIN_REQUIRED`
 * response disables every admin control and renders the server message verbatim.
 */
@Component({
  selector: 'sf-admin-page',
  imports: [MatButtonModule, MatIconModule, MatTableModule, StatusChipComponent],
  templateUrl: './admin-page.component.html',
  styleUrl: './admin-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminPageComponent {
  private readonly api = inject(FactoryApiService)
  private readonly dialog = inject(MatDialog)

  /** True once the server has refused an admin command (403). */
  protected readonly adminDisabled = signal(false)
  protected readonly forbiddenError = signal<string | null>(null)

  // -- Workflow definitions --------------------------------------------------
  protected readonly definitions = signal<WorkflowDefinition[]>([])
  protected readonly definitionsLoading = signal(false)
  protected readonly uploading = signal(false)
  protected readonly selectedFile = signal<File | null>(null)
  protected readonly definitionsError = signal<string | null>(null)
  protected readonly deletingKey = signal<string | null>(null)
  protected readonly definitionColumns = ['workflowType', 'version', 'definitionHash', 'actions']

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workflow definitions' }])
    this.loadDefinitions()
  }

  /** Fetch the registered workflow definitions. */
  protected loadDefinitions(): void {
    if (this.adminDisabled()) return
    this.definitionsLoading.set(true)
    this.definitionsError.set(null)
    this.api.getWorkflowDefinitions().subscribe({
      next: (payload) => {
        this.definitions.set(extractDefinitions(payload))
        this.definitionsLoading.set(false)
      },
      error: (error: FactoryApiError) => {
        this.applyError(error, (message) => this.definitionsError.set(message))
        this.definitionsLoading.set(false)
      },
    })
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement
    this.selectedFile.set(input.files?.[0] ?? null)
  }

  /** Upload the selected definition file then refresh the registry. */
  protected uploadDefinition(): void {
    if (this.adminDisabled() || this.uploading()) return
    const file = this.selectedFile()
    if (!file) {
      this.definitionsError.set('Please select a JSON definition file.')
      return
    }
    this.uploading.set(true)
    this.definitionsError.set(null)
    this.api.uploadWorkflowDefinition(file).subscribe({
      next: () => {
        this.selectedFile.set(null)
        this.uploading.set(false)
        this.loadDefinitions()
      },
      error: (error: FactoryApiError) => {
        this.applyError(error, (message) => this.definitionsError.set(message))
        this.uploading.set(false)
      },
    })
  }

  /** Delete a workflow definition after an explicit confirmation. */
  protected deleteDefinition(definition: WorkflowDefinition): void {
    if (this.adminDisabled() || this.deletingKey()) return
    const workflowType = String(definition.workflowType ?? '').trim()
    const version = String(definition.version ?? '').trim()
    if (!workflowType || !version) return
    this.confirm({
      title: 'Delete definition',
      message: 'This will permanently delete the workflow definition. Already running executions are not affected.',
      detail: `${workflowType}@${version}`,
      confirmLabel: 'Delete',
      destructive: true,
    }).subscribe((approved) => {
      if (!approved) return
      this.deletingKey.set(`${workflowType}@${version}`)
      this.definitionsError.set(null)
      this.api.deleteWorkflowDefinition(workflowType, version).subscribe({
        next: () => {
          this.deletingKey.set(null)
          this.loadDefinitions()
        },
        error: (error: FactoryApiError) => {
          this.applyError(error, (message) => this.definitionsError.set(message))
          this.deletingKey.set(null)
        },
      })
    })
  }

  protected definitionKey(definition: WorkflowDefinition): string {
    return `${definition.workflowType ?? ''}@${definition.version ?? ''}`
  }

  private confirm(data: ConfirmDialogData): Observable<boolean> {
    return this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, { data, width: '440px' })
      .afterClosed()
      .pipe(map((confirmed) => confirmed === true))
  }

  /**
   * Route an error to the right surface: 403 / FORBIDDEN_ADMIN_REQUIRED locks
   * the whole page and shows the verbatim server message; other failures stay
   * in their section banner.
   */
  private applyError(error: FactoryApiError, setSectionError: (message: string) => void): void {
    const described = describeAdminError(error)
    if (described.forbidden) {
      this.adminDisabled.set(true)
      this.forbiddenError.set(described.message)
    } else {
      setSectionError(described.message)
    }
  }
}
