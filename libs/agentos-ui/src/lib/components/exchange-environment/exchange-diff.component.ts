import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core'
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog'
import { FormsModule } from '@angular/forms'
import { forkJoin, from, map, Subscription } from 'rxjs'
import {
  DiffFile,
  EnvironmentScope,
  ExchangeEnvironment,
  ExchangeEnvironmentService,
} from '../../services/exchange-environment.service'

@Component({
  selector: 'agentos-exchange-diff',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrl: './exchange-diff.component.scss',
  template: `
    <div class="exchange-diff">
      <header>
        <div>
          <strong>{{ data.environment.branch || 'Changes' }}</strong>
          <span class="added"> +{{ data.environment.changes?.additions }}</span>
          <span class="removed"> −{{ data.environment.changes?.deletions }}</span>
          <small>Branch changes + local changes · {{ data.environment.changes?.base?.slice(0, 8) }}</small>
        </div>
        <button type="button" (click)="dialog.close()" aria-label="Close diff">✕</button>
      </header>
      <div class="diff-layout">
        <nav aria-label="Changed files">
          <input
            type="search"
            placeholder="Filter files…"
            aria-label="Filter changed files"
            [ngModel]="filter()"
            (ngModelChange)="filter.set($event)"
          />
          @for (file of filtered(); track file.path) {
            <button
              type="button"
              [class.selected]="selected()?.path === file.path"
              [attr.aria-current]="selected()?.path === file.path ? 'true' : null"
              (click)="select(file)"
              [title]="file.path"
            >
              <span class="file-path">{{ file.path }}</span>
              @if (file.additions !== null && file.additions !== undefined) {
                <small
                  ><span class="added">+{{ file.additions }}</span>
                  <span class="removed">−{{ file.deletions }}</span></small
                >
              } @else {
                <small>binary</small>
              }
            </button>
          } @empty {
            <p>No changed files</p>
          }
        </nav>
        <main aria-live="polite">
          @if (selected(); as file) {
            <h3>{{ file.path }}</h3>
          }
          @if (loading()) {
            <p role="status">Loading diff…</p>
          }
          @if (message()) {
            <p role="status">{{ message() }}</p>
          }
          @if (error()) {
            <p role="alert">Couldn't load this diff. <button type="button" (click)="retry()">Retry</button></p>
          }
          <div class="diff-content" [innerHTML]="html()"></div>
        </main>
      </div>
    </div>
  `,
})
export class ExchangeDiffComponent {
  readonly data = inject<{ scope: EnvironmentScope; environment: ExchangeEnvironment; path?: string }>(MAT_DIALOG_DATA)
  protected readonly dialog = inject(MatDialogRef<ExchangeDiffComponent>)
  private readonly service = inject(ExchangeEnvironmentService)
  protected readonly filter = signal('')
  protected readonly selected = signal<DiffFile | null>(null)
  protected readonly filtered = computed(() =>
    (this.data.environment.changes?.files ?? []).filter((f) =>
      f.path.toLowerCase().includes(this.filter().toLowerCase())
    )
  )
  protected readonly loading = signal(false)
  protected readonly html = signal('')
  protected readonly message = signal<string | null>(null)
  protected readonly error = signal(false)
  private pending?: Subscription
  private readonly destroyRef = inject(DestroyRef)
  constructor() {
    this.destroyRef.onDestroy(() => this.pending?.unsubscribe())
    const files = this.data.environment.changes?.files ?? []
    const file = files.find((entry) => entry.path === this.data.path) ?? files[0]
    if (file) this.select(file)
  }
  protected retry() {
    const file = this.selected()
    if (file) this.select(file)
  }
  protected select(file: DiffFile) {
    this.pending?.unsubscribe()
    this.selected.set(file)
    this.loading.set(true)
    this.html.set('')
    this.message.set(null)
    this.error.set(false)
    this.pending = forkJoin([this.service.diff(this.data.scope, file.path), from(import('diff2html'))])
      .pipe(
        map(([result, library]) => ({
          message: result.message,
          // Keep Angular's HTML sanitizer. File names and source code are never trusted markup.
          html: result.patch
            ? library.html(result.patch, { drawFileList: false, matching: 'none', outputFormat: 'line-by-line' })
            : '',
        }))
      )
      .subscribe({
        next: ({ message, html }) => {
          this.message.set(message ?? null)
          this.html.set(html)
          this.loading.set(false)
        },
        error: () => {
          this.error.set(true)
          this.loading.set(false)
        },
      })
  }
}
