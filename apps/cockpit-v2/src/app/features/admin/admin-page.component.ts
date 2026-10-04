import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatDialog } from '@angular/material/dialog'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatIconModule } from '@angular/material/icon'
import { MatInputModule } from '@angular/material/input'
import { MatTableModule } from '@angular/material/table'
import { Observable, map } from 'rxjs'
import {
  FactoryApiError,
  FactoryApiService,
  GcReport,
  LegalHoldResult,
  PurgeResult,
  WorkflowDefinition,
} from '../../core/factory-api.service'
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
    const detail = serverMessage ?? 'droits d’administration requis'
    return { forbidden: true, message: `Accès refusé : ${detail} (${FORBIDDEN_ADMIN_REQUIRED})` }
  }
  return { forbidden: false, message: serverMessage ?? 'Commande admin impossible (erreur réseau ou serveur).' }
}

/** Human-readable byte size (`null` when the value is absent/invalid). */
export function formatBytes(value: unknown): string | null {
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return null
  if (bytes < 1024) return `${bytes} o`
  const units = ['Ko', 'Mo', 'Go', 'To']
  let size = bytes / 1024
  let unit = units[0]
  for (let index = 1; index < units.length && size >= 1024; index++) {
    size /= 1024
    unit = units[index]
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${unit}`
}

function extractDefinitions(payload: unknown): WorkflowDefinition[] {
  if (Array.isArray(payload)) return payload as WorkflowDefinition[]
  const items = (payload as { items?: unknown } | null)?.items
  return Array.isArray(items) ? (items as WorkflowDefinition[]) : []
}

function asRecord<T>(payload: unknown, fallback: T): T {
  return (typeof payload === 'object' && payload !== null ? payload : fallback) as T
}

/**
 * Administration page of the artifact & workflow governance surface, ported
 * from the legacy `artifact-admin` view.
 *
 * Server authorization is authoritative: any `403` / `FORBIDDEN_ADMIN_REQUIRED`
 * response disables every admin control and renders the server message verbatim.
 */
@Component({
  selector: 'sf-admin-page',
  imports: [
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTableModule,
    StatusChipComponent,
  ],
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

  // ── Garbage collection ────────────────────────────────────────────────
  protected readonly gcDryRun = signal(false)
  protected readonly gcRunning = signal(false)
  protected readonly gcReport = signal<GcReport | null>(null)
  protected readonly gcError = signal<string | null>(null)
  protected readonly gcReclaimed = computed(() => this.gcReport()?.reclaimedStagingKeys?.length ?? 0)
  protected readonly gcBlobs = computed(() => this.gcReport()?.scannedBlobKeys?.length ?? 0)
  protected readonly gcRows = computed(() => this.gcReport()?.scannedMetadataRows ?? 0)
  protected readonly gcAnomalies = computed(() => this.gcReport()?.anomalies?.length ?? 0)
  protected readonly gcTimestamp = computed(() => this.gcReport()?.timestamp ?? null)

  // ── Purge ─────────────────────────────────────────────────────────────
  protected readonly purgeArtifactId = signal('')
  protected readonly purgeReason = signal('')
  protected readonly purgeRunning = signal(false)
  protected readonly purgeResult = signal<PurgeResult | null>(null)
  protected readonly purgeError = signal<string | null>(null)
  protected readonly purgeFreed = computed(() => formatBytes(this.purgeResult()?.metadata?.size))

  // ── Legal hold ────────────────────────────────────────────────────────
  protected readonly legalArtifactId = signal('')
  protected readonly legalHold = signal(true)
  protected readonly legalReason = signal('')
  protected readonly legalRunning = signal(false)
  protected readonly legalResult = signal<LegalHoldResult | null>(null)
  protected readonly legalError = signal<string | null>(null)
  protected readonly legalActive = computed(() => this.legalResult()?.legalHold === true)
  protected readonly legalReasonText = computed(
    () => this.legalResult()?.legalHoldReason ?? this.legalResult()?.reason ?? null
  )

  // ── Workflow definitions ──────────────────────────────────────────────
  protected readonly definitions = signal<WorkflowDefinition[]>([])
  protected readonly definitionsLoading = signal(false)
  protected readonly uploading = signal(false)
  protected readonly selectedFile = signal<File | null>(null)
  protected readonly definitionsError = signal<string | null>(null)
  protected readonly deletingKey = signal<string | null>(null)
  protected readonly definitionColumns = ['workflowType', 'version', 'definitionHash', 'actions']

  protected readonly formatBytes = formatBytes

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workflows' }])
    this.loadDefinitions()
  }

  /** Run the garbage-collection command, optionally as a dry run. */
  protected runGc(): void {
    if (this.adminDisabled() || this.gcRunning()) return
    this.gcRunning.set(true)
    this.gcError.set(null)
    this.gcReport.set(null)
    const body = this.gcDryRun() ? { dryRun: true } : {}
    this.api.runGarbageCollection(body).subscribe({
      next: (report) => {
        this.gcReport.set(asRecord<GcReport>(report, {}))
        this.gcRunning.set(false)
      },
      error: (error: FactoryApiError) => {
        this.applyError(error, (message) => this.gcError.set(message))
        this.gcRunning.set(false)
      },
    })
  }

  /** Purge an artifact after an explicit confirmation. */
  protected purge(): void {
    if (this.adminDisabled() || this.purgeRunning()) return
    const artifactId = this.purgeArtifactId().trim()
    if (!artifactId) {
      this.purgeError.set('Identifiant d’artefact requis.')
      return
    }
    this.purgeError.set(null)
    this.confirm({
      title: 'Purger l’artefact',
      message: 'Suppression définitive et irréversible. Le legal hold et la rétention doivent avoir expiré.',
      detail: artifactId,
      confirmLabel: 'Purger',
      destructive: true,
    }).subscribe((approved) => {
      if (!approved) return
      const reason = this.purgeReason().trim()
      this.purgeRunning.set(true)
      this.purgeResult.set(null)
      this.api.purgeArtifact(artifactId, reason ? { reason } : {}).subscribe({
        next: (result) => {
          this.purgeResult.set(asRecord<PurgeResult>(result, { status: 'purged', artifactId, reason }))
          this.purgeRunning.set(false)
        },
        error: (error: FactoryApiError) => {
          this.applyError(error, (message) => this.purgeError.set(message))
          this.purgeRunning.set(false)
        },
      })
    })
  }

  /** Toggle the legal hold of an artifact. */
  protected applyLegalHold(): void {
    if (this.adminDisabled() || this.legalRunning()) return
    const artifactId = this.legalArtifactId().trim()
    if (!artifactId) {
      this.legalError.set('Identifiant d’artefact requis.')
      return
    }
    const reason = this.legalReason().trim()
    const body: { legalHold: boolean; reason?: string } = { legalHold: this.legalHold() }
    if (reason) body.reason = reason
    this.legalRunning.set(true)
    this.legalError.set(null)
    this.legalResult.set(null)
    this.api.setLegalHold(artifactId, body).subscribe({
      next: (result) => {
        this.legalResult.set(asRecord<LegalHoldResult>(result, { id: artifactId, legalHold: this.legalHold() }))
        this.legalRunning.set(false)
      },
      error: (error: FactoryApiError) => {
        this.applyError(error, (message) => this.legalError.set(message))
        this.legalRunning.set(false)
      },
    })
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
      this.definitionsError.set('Sélectionnez un fichier de définition JSON.')
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
      title: 'Supprimer la définition',
      message:
        'Suppression définitive de la définition de workflow. Les exécutions déjà démarrées ne sont pas affectées.',
      detail: `${workflowType}@${version}`,
      confirmLabel: 'Supprimer',
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
