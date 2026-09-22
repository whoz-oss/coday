import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core'
import { MatDialog } from '@angular/material/dialog'
import { catchError, of, switchMap, timer } from 'rxjs'
import {
  EnvironmentScope,
  ExchangeEnvironment,
  ExchangeEnvironmentService,
} from '../../services/exchange-environment.service'
import { ExchangeDiffComponent } from './exchange-diff.component'

@Component({
  selector: 'agentos-exchange-environment',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './exchange-environment.component.html',
  styleUrl: './exchange-environment.component.scss',
})
export class ExchangeEnvironmentComponent {
  readonly scope = input.required<EnvironmentScope>()
  readonly environmentChanged = output<{ scope: EnvironmentScope; view: ExchangeEnvironment | null }>()
  protected readonly view = signal<ExchangeEnvironment | null>(null)
  protected readonly error = signal(false)
  private readonly service = inject(ExchangeEnvironmentService)
  private readonly dialog = inject(MatDialog)
  constructor() {
    effect((cleanup) => {
      const scope = this.scope()
      this.view.set(null)
      this.error.set(false)
      this.environmentChanged.emit({ scope, view: null })
      const subscription = timer(0, 10000)
        .pipe(switchMap(() => this.service.get(scope).pipe(catchError(() => of(null)))))
        .subscribe((view) => {
          this.view.set(view)
          this.error.set(!view)
          this.environmentChanged.emit({ scope, view })
        })
      cleanup(() => subscription.unsubscribe())
    })
  }
  openDiff(path?: string) {
    const environment = this.view()
    if (!environment?.changes) return
    this.dialog.open(ExchangeDiffComponent, {
      data: { scope: this.scope(), environment, path },
      width: '1200px',
      maxWidth: '96vw',
      height: '85vh',
      autoFocus: 'first-tabbable',
      restoreFocus: true,
    })
  }
}
