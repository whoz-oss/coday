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
import { ConfigFileDiff, ConfigFileDiffEntry } from '@whoz-oss/agentos-api-client'
import { NoticeComponent } from '@whoz-oss/design-system'
import { forkJoin, from, Subscription } from 'rxjs'
import { ConfigFileKind, ConfigFilesService } from '../../services/config-files.service'

export interface ConfigFileDiffData {
  readonly kind: ConfigFileKind
  /** Id of the database copy. */
  readonly id: string
  readonly name: string
}

/**
 * A database copy next to the file it masks, both in the file format, with the diff view of the
 * worktree changes (its styles included). Lines removed are the file's, lines added the copy's.
 */
@Component({
  selector: 'agentos-config-file-diff',
  imports: [NoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
  styleUrls: ['../exchange-environment/exchange-diff.component.scss', './config-file-diff.component.scss'],
  template: `
    <div class="exchange-diff config-file-diff">
      <header>
        <div>
          <strong>{{ data.name }}</strong>
          <small>
            In red, the file{{ diff()?.path ? ' ' + diff()?.path : '' }}. In green, the database copy, which AgentOS
            uses.
          </small>
        </div>
        <button type="button" (click)="dialog.close()" aria-label="Close differences">✕</button>
      </header>
      @if (loading()) {
        <p class="config-file-diff__status" role="status">Loading differences…</p>
      }
      @if (error()) {
        <p class="config-file-diff__status" role="alert">
          Couldn't load the differences. <button type="button" (click)="load()">Retry</button>
        </p>
      }
      @if (diff(); as result) {
        @for (note of result.notes ?? []; track note) {
          <ds-notice tone="warning">{{ note }}</ds-notice>
        }
        @if (!result.modified) {
          <p class="config-file-diff__status" role="status">The database copy is identical to the file.</p>
        }
        <div class="diff-layout">
          @if (result.files.length > 1) {
            <nav aria-label="Files">
              @for (entry of result.files; track entry.path) {
                <button
                  type="button"
                  [class.selected]="selected()?.path === entry.path"
                  [attr.aria-current]="selected()?.path === entry.path ? 'true' : null"
                  (click)="select(entry)"
                  [title]="entry.path"
                >
                  <span class="file-path">{{ entry.path }}</span>
                  <small>{{ statusOf(entry) }}</small>
                </button>
              }
            </nav>
          }
          <main aria-live="polite">
            @if (selected(); as entry) {
              <h3>{{ entry.path }}</h3>
              @if (entry.fileContent === entry.configContent) {
                <p role="status">{{ statusOf(entry) }}</p>
              }
            }
            <div class="diff-content" [innerHTML]="html()"></div>
          </main>
        </div>
      }
    </div>
  `,
})
export class ConfigFileDiffComponent {
  readonly data = inject<ConfigFileDiffData>(MAT_DIALOG_DATA)
  protected readonly dialog = inject(MatDialogRef<ConfigFileDiffComponent>)
  private readonly service = inject(ConfigFilesService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly diff = signal<ConfigFileDiff | null>(null)
  protected readonly selected = signal<ConfigFileDiffEntry | null>(null)
  protected readonly loading = signal(false)
  protected readonly error = signal(false)
  private readonly renderer = signal<((entry: ConfigFileDiffEntry) => string) | null>(null)
  protected readonly html = computed(() => {
    const entry = this.selected()
    const render = this.renderer()
    return entry && render && entry.fileContent !== entry.configContent ? render(entry) : ''
  })
  private pending?: Subscription

  constructor() {
    this.destroyRef.onDestroy(() => this.pending?.unsubscribe())
    this.load()
  }

  protected load(): void {
    this.pending?.unsubscribe()
    this.loading.set(true)
    this.error.set(false)
    this.pending = forkJoin([
      this.service.diff(this.data.kind, this.data.id),
      from(import('diff')),
      from(import('diff2html')),
    ]).subscribe({
      next: ([result, jsdiff, diff2html]) => {
        this.renderer.set((entry) =>
          // Keep Angular's HTML sanitizer: config contents are never trusted markup.
          diff2html.html(
            jsdiff.createTwoFilesPatch(entry.path, entry.path, entry.fileContent ?? '', entry.configContent ?? ''),
            { drawFileList: false, matching: 'none', outputFormat: 'line-by-line' }
          )
        )
        this.diff.set(result)
        this.selected.set(
          result.files.find((entry) => entry.fileContent !== entry.configContent) ?? result.files[0] ?? null
        )
        this.loading.set(false)
      },
      error: () => {
        this.error.set(true)
        this.loading.set(false)
      },
    })
  }

  protected select(entry: ConfigFileDiffEntry): void {
    this.selected.set(entry)
  }

  protected statusOf(entry: ConfigFileDiffEntry): string {
    if (entry.fileContent == null) return 'Only in the database copy'
    if (entry.configContent == null) return 'Only in the file'
    return entry.fileContent === entry.configContent ? 'Identical' : 'Differs'
  }
}
