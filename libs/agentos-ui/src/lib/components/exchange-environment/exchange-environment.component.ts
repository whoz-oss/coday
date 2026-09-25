import { DatePipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core'
import { MatDialog, MatDialogRef } from '@angular/material/dialog'
import { catchError, combineLatest, exhaustMap, finalize, merge, of, Subject, Subscription, timer } from 'rxjs'
import { CaseWorkspaceService } from '../../services/case-workspace.service'
import {
  EnvironmentScope,
  ExchangeEnvironment,
  ExchangeEnvironmentService,
} from '../../services/exchange-environment.service'
import { ExchangeDiffComponent } from './exchange-diff.component'

@Component({
  selector: 'agentos-exchange-environment',
  imports: [DatePipe],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exchange-environment.component.html',
  styleUrl: './exchange-environment.component.scss',
})
export class ExchangeEnvironmentComponent {
  readonly scope = input.required<EnvironmentScope>()
  readonly canWrite = input(false)
  readonly environmentChanged = output<{ scope: EnvironmentScope; view: ExchangeEnvironment | null }>()
  protected readonly view = signal<ExchangeEnvironment | null>(null)
  protected readonly error = signal(false)
  private readonly service = inject(ExchangeEnvironmentService)
  private readonly dialog = inject(MatDialog)
  private readonly workspaces = inject(CaseWorkspaceService)
  private readonly refreshEnvironment = new Subject<void>()
  private diffDialog?: MatDialogRef<ExchangeDiffComponent>
  private refreshRequest?: Subscription
  protected readonly refreshing = signal(false)
  protected readonly actionError = signal('')
  constructor() {
    effect((cleanup) => {
      const scope = this.scope()
      this.view.set(null)
      this.error.set(false)
      this.environmentChanged.emit({ scope, view: null })
      this.actionError.set('')
      const environment = merge(timer(0, 10000), this.refreshEnvironment).pipe(
        exhaustMap(() => this.service.get(scope).pipe(catchError(() => of(null))))
      )
      const subscription = combineLatest([environment, this.workspaces.watch(scope.id)]).subscribe(
        ([environment, workspace]) => {
          const view =
            environment && workspace.view
              ? {
                  ...environment,
                  equipped: workspace.view.equipped,
                  status: workspace.view.status ?? environment.status,
                }
              : null
          this.view.set(view)
          this.error.set(!view)
          this.environmentChanged.emit({ scope, view })
        }
      )
      cleanup(() => {
        subscription.unsubscribe()
        this.refreshRequest?.unsubscribe()
        this.diffDialog?.close()
      })
    })
  }
  protected refreshGit(): void {
    if (!this.canWrite() || this.refreshing()) return
    this.refreshing.set(true)
    this.actionError.set('')
    this.refreshRequest = this.workspaces
      .act(this.scope().id, 'refresh')
      .pipe(finalize(() => this.refreshing.set(false)))
      .subscribe({
        next: () => this.refreshEnvironment.next(),
        error: () => this.actionError.set('Could not refresh Git status. Try again.'),
      })
  }
  openDiff(path?: string) {
    const environment = this.view()
    if (!environment?.changes) return
    this.diffDialog?.close()
    this.diffDialog = this.dialog.open(ExchangeDiffComponent, {
      data: { scope: this.scope(), environment, path },
      width: '1200px',
      maxWidth: '96vw',
      height: '85vh',
      autoFocus: 'first-tabbable',
      restoreFocus: true,
    })
  }
}
