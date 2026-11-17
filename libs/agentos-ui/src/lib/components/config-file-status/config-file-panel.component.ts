import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core'
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop'
import { HttpErrorResponse } from '@angular/common/http'
import { ConfigFile, ConfigFileDiff, ConfigFileDiffEntry, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import {
  ButtonDirective,
  CardComponent,
  ConfirmDialogComponent,
  DisclosureComponent,
  FormActionsComponent,
  NoticeComponent,
} from '@whoz-oss/design-system'
import {
  catchError,
  combineLatest,
  distinctUntilChanged,
  forkJoin,
  from,
  map,
  of,
  startWith,
  Subject,
  switchMap,
} from 'rxjs'
import { ConfigFilesService, downloadName } from '../../services/config-files.service'
import { ConfigFileDiffRow, configFileDiffRows } from './config-file-diff-rows'

type Action = 'write' | 'discard'

interface FileView {
  readonly path: string
  readonly heading: string
  readonly rows: readonly ConfigFileDiffRow[]
}

const PREFIX: Record<ConfigFileDiffRow['kind'], string> = { same: '  ', removed: '− ', added: '+ ', skipped: '⋯ ' }

/**
 * On the form of a config defined by a file of the namespace (#1673):
 * - read from its file: says that saving it here creates a database copy that masks the file;
 * - a database copy that masks its file: shows how the copy differs from the file, and lets the user
 *   write the copy to the file, download it as that file, or discard it. Once the copy is gone it emits
 *   [settled], for the form to leave a page whose config no longer exists.
 */
@Component({
  selector: 'agentos-config-file-panel',
  imports: [
    ButtonDirective,
    CardComponent,
    ConfirmDialogComponent,
    DisclosureComponent,
    FormActionsComponent,
    NoticeComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './config-file-panel.component.html',
  styleUrl: './config-file-panel.component.scss',
})
export class ConfigFilePanelComponent {
  /** The entry of the edited config in the namespace's config files, if a file defines it. */
  readonly file = input<ConfigFile | undefined>()
  /** Singular noun of the config, e.g. `agent config`. */
  readonly noun = input.required<string>()
  /** True when saving a config read from its file creates a database copy (agents): the form then says so. */
  readonly savingCreatesCopy = input(false, { transform: booleanAttribute })
  /** The database copy is gone: AgentOS reads the file again. */
  readonly settled = output<void>()

  private readonly service = inject(ConfigFilesService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly fileState = ConfigFileStateEnum.FILE
  protected readonly diff = signal<ConfigFileDiff | null>(null)
  protected readonly loading = signal(false)
  protected readonly loadError = signal(false)
  protected readonly pending = signal<Action | null>(null)
  protected readonly busy = signal(false)
  protected readonly actionError = signal<string | null>(null)
  private readonly lineDiff = signal<((before: string, after: string) => ConfigFileDiffRow[]) | null>(null)
  private readonly opened = signal<ReadonlySet<string>>(new Set())
  private readonly reload$ = new Subject<void>()

  /** One block per file that differs: the config's file, and the resources of a skill. */
  protected readonly views = computed<FileView[]>(() => {
    const result = this.diff()
    const rows = this.lineDiff()
    if (!result || !rows) return []
    return result.files
      .filter((entry) => entry.fileContent !== entry.configContent)
      .map((entry) => ({
        path: entry.path,
        // A single file is already named by the card; a skill's files need their own names.
        heading: result.files.length > 1 ? `${entry.path} · ${this.statusOf(entry)}` : 'Differences with the file',
        rows: rows(entry.fileContent ?? '', entry.configContent ?? ''),
      }))
  })
  protected readonly canWrite = computed(() => (this.diff()?.cannotWrite ?? []).length === 0)
  protected readonly canDiscard = computed(() => (this.diff()?.cannotDiscard ?? []).length === 0)
  protected readonly confirmation = computed(() => this.confirmationOf(this.pending()))

  constructor() {
    const overriding = toObservable(this.file).pipe(
      map((file) => (file?.state === ConfigFileStateEnum.OVERRIDDEN ? file : undefined)),
      distinctUntilChanged((a, b) => a?.configId === b?.configId && a?.path === b?.path)
    )
    combineLatest([overriding, this.reload$.pipe(startWith(undefined))])
      .pipe(
        switchMap(([file]) => (file ? this.load(file) : of(null))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe()
  }

  protected isOpen(path: string): boolean {
    return this.opened().has(path)
  }

  protected toggle(path: string): void {
    const next = new Set(this.opened())
    if (!next.delete(path)) next.add(path)
    this.opened.set(next)
  }

  protected prefix(row: ConfigFileDiffRow): string {
    return PREFIX[row.kind]
  }

  protected run(): void {
    const action = this.pending()
    const file = this.file()
    const result = this.diff()
    this.pending.set(null)
    if (!action || !file || !result) return
    this.busy.set(true)
    this.actionError.set(null)
    const request =
      action === 'write' && result.modified
        ? this.service.writeToFile(file.kind, file.configId, result.fileHash)
        : this.service.discard(file.kind, file.configId)
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (outcome) => {
        this.busy.set(false)
        if (outcome.copyDeleted) this.settled.emit()
        else this.actionError.set(outcome.message ?? 'The database copy could not be deleted.')
      },
      error: (error: unknown) => {
        this.busy.set(false)
        this.actionError.set(messageOf(error))
        // The file changed: show the differences with what it holds now.
        if (error instanceof HttpErrorResponse && error.status === 409) this.reload$.next()
      },
    })
  }

  protected download(): void {
    const file = this.file()
    const result = this.diff()
    if (!file || !result) return
    this.service
      .download(file.kind, file.configId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob)
          const anchor = document.createElement('a')
          anchor.href = url
          anchor.download = downloadName(file.kind, result.path)
          anchor.click()
          URL.revokeObjectURL(url)
        },
        error: (error: unknown) => this.actionError.set(messageOf(error)),
      })
  }

  protected statusOf(entry: ConfigFileDiffEntry): string {
    if (entry.fileContent == null) return 'only in this copy'
    if (entry.configContent == null) return 'only in the file'
    return 'differs'
  }

  private load(file: ConfigFile) {
    this.loading.set(true)
    this.loadError.set(false)
    return forkJoin([this.service.diff(file.kind, file.configId), from(import('diff'))]).pipe(
      map(([result, jsdiff]) => {
        this.lineDiff.set((before, after) => configFileDiffRows(jsdiff.diffLines(before, after)))
        this.diff.set(result)
        const first = result.files.find((entry) => entry.fileContent !== entry.configContent)
        this.opened.set(new Set(first ? [first.path] : []))
        this.loading.set(false)
        return result
      }),
      catchError(() => {
        this.loadError.set(true)
        this.loading.set(false)
        return of(null)
      })
    )
  }

  private confirmationOf(action: Action | null): { title: string; message: string; label: string } {
    const result = this.diff()
    const path = result?.path ?? 'the file'
    const others = result?.alsoReadBy?.length
      ? ` The namespaces ${result.alsoReadBy.join(', ')} read the same folder: they get the new file too.`
      : ''
    if (action === 'write' && result?.modified) {
      return {
        title: `Write this ${this.noun()} to ${path}?`,
        message:
          `The file is replaced by this copy, then the copy is deleted: AgentOS reads the file again. ` +
          `The file's comments and formatting are not kept. Commit the file as you usually do.${others}`,
        label: 'Write to file',
      }
    }
    if (result?.modified) {
      return {
        title: 'Discard this copy?',
        message: `AgentOS reads ${path} again and the changes made here are lost. Download the file first to keep them.`,
        label: 'Discard',
      }
    }
    return {
      title: 'Use the file again?',
      message: `This copy says the same as ${path}. It is deleted and AgentOS reads the file again.`,
      label: 'Use the file',
    }
  }
}

/** The server's explanation of a failed action, which is written for the user. */
function messageOf(error: unknown): string {
  const body = error instanceof HttpErrorResponse ? error.error : undefined
  const message = typeof body === 'object' && body && 'message' in body ? String(body.message) : undefined
  return message || 'The action failed. Try again.'
}
