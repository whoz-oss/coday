import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormsModule } from '@angular/forms'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import { finalize, skip, take } from 'rxjs'
import { MatDialog } from '@angular/material/dialog'
import { FactoryApiError } from '../../core/factory-api.service'
import { WorkflowDefinitionsStateService } from '../../core/workflow-definitions-state.service'
import { ConfirmDialogComponent, ConfirmDialogData } from '../admin/confirm-dialog.component'
import { ShellState } from '../../core/shell-state'
import { WorkflowStepEditorState, legacyAdoption } from '../../core/workflow-step-editor.state'
import { WorkflowDefinitionDiagramComponent } from './workflow-definition-diagram.component'

export function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** Shared block composer and properties editor, backed by immutable definitions. */
@Component({
  selector: 'sf-workflow-detail-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    RouterLink,
    WorkflowDefinitionDiagramComponent,
  ],
  templateUrl: './workflow-detail-page.component.html',
  styleUrl: './workflow-detail-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [WorkflowStepEditorState, WorkflowDefinitionsStateService],
})
export class WorkflowDetailPageComponent {
  readonly state = inject(WorkflowStepEditorState)
  private readonly registry = inject(WorkflowDefinitionsStateService)
  private readonly dialog = inject(MatDialog)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly deleting = signal(false)
  protected readonly deleteError = signal<string | null>(null)

  protected deleteWorkflow(): void {
    const definition = this.state.definition()
    if (!definition || this.state.editing() || this.state.creating() || this.state.loading() || this.deleting()) return
    const { workflowType, version } = definition
    if (!workflowType || !version) return
    this.deleting.set(true)
    const data: ConfirmDialogData = {
      title: 'Delete workflow',
      detail: `${definition.title || workflowType} · ${workflowType}@${version}`,
      message:
        'Permanently delete this registered version? It will no longer be available for new runs. Runs are not deleted or changed. Deletion is refused if any active or historical run pins this version, including archived or purged runs.',
      confirmLabel: 'Delete workflow',
      destructive: true,
    }
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data,
        width: '520px',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .pipe(take(1), takeUntilDestroyed(this.destroyRef))
      .subscribe((confirmed) => {
        const current = this.state.definition()
        if (
          confirmed !== true ||
          current?.workflowType !== workflowType ||
          current?.version !== version ||
          this.state.editing()
        ) {
          this.deleting.set(false)
          return
        }
        this.deleteError.set(null)
        this.registry
          .delete(workflowType, version)
          .pipe(
            finalize(() => this.deleting.set(false)),
            takeUntilDestroyed(this.destroyRef)
          )
          .subscribe({
            next: () => {
              void this.router.navigate(this.backLink)
            },
            error: (error: FactoryApiError) => {
              this.deleteError.set(
                `${error.message || 'Unable to delete workflow. Nothing was changed.'}${error.code ? ` (${error.code})` : ''}`
              )
            },
          })
      })
  }
  protected readonly backLink = ['/workflows']
  protected readonly formatJson = formatJson
  protected readonly legacyAdoption = legacyAdoption
  protected readonly workflowType: string
  protected readonly version: string

  constructor() {
    const shell = inject(ShellState)
    effect(() => {
      const saved = this.state.saved()
      untracked(() => {
        const definition = this.state.definition()
        if (saved && !this.state.editing() && definition) {
          shell.crumbs.set([
            { label: 'Workflows', link: '/workflows' },
            { label: `${definition.workflowType}@${definition.version}`, mono: true },
          ])
          void this.router.navigate(['/workflows', definition.workflowType, definition.version], { replaceUrl: true })
        }
      })
    })
    const route = inject(ActivatedRoute)
    const snapshot = route.snapshot
    const params = snapshot.paramMap
    const creating = snapshot.data?.['create'] === true
    this.workflowType = params.get('type') ?? ''
    this.version = params.get('version') ?? ''
    inject(ShellState).crumbs.set([
      { label: 'Workflows', link: '/workflows' },
      { label: creating ? 'New workflow' : `${this.workflowType}@${this.version}`, mono: true },
    ])
    if (creating) this.state.startNew()
    else this.state.load(this.workflowType, this.version)

    // Angular reuses this component when only a definition's route parameters change.
    route.paramMap?.pipe(skip(1), takeUntilDestroyed()).subscribe((params) => {
      const type = params.get('type') ?? ''
      const version = params.get('version') ?? ''
      const current = this.state.definition()
      if (!creating && type && version && (current?.workflowType !== type || current?.version !== version)) {
        shell.crumbs.set([
          { label: 'Workflows', link: '/workflows' },
          { label: `${type}@${version}`, mono: true },
        ])
        this.deleteError.set(null)
        this.state.load(type, version)
      }
    })
  }

  @HostListener('window:beforeunload', ['$event'])
  protected beforeUnload(event: BeforeUnloadEvent): void {
    if (this.state.dirty() || this.state.saving()) event.preventDefault()
  }

  canLeave(): boolean {
    return !this.state.saving() && (!this.state.dirty() || window.confirm('Discard unsaved workflow changes?'))
  }

  protected cancel(): void {
    if (this.canLeave()) {
      const creating = this.state.creating()
      this.state.cancel()
      if (creating) void this.router.navigate(this.backLink)
    }
  }

  private readonly router = inject(Router)

  protected dependencyLabel(id: string): string {
    const step = this.state.definition()?.steps?.find((candidate) => candidate.id === id)
    return step ? `${step.name} (${id})` : id
  }

  protected updateAction(field: 'entry' | 'exit', index: number, value: string): void {
    const draft = this.state.draft()
    if (!draft) return
    this.state.update(
      field,
      draft[field].map((item, position) => (position === index ? value : item))
    )
  }

  protected addAction(field: 'entry' | 'exit'): void {
    const draft = this.state.draft()
    if (draft) this.state.update(field, [...draft[field], ''])
  }

  protected removeAction(field: 'entry' | 'exit', index: number): void {
    const draft = this.state.draft()
    if (draft)
      this.state.update(
        field,
        draft[field].filter((_, position) => position !== index)
      )
  }
}
