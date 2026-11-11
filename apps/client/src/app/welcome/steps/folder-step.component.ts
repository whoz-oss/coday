import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core'
import { errorMessage, SetupStateService } from '../setup-state.service'
import { BrowseResult } from '../setup.models'

/**
 * Step 3: where the Coday agent reads and writes your files. A server-backed folder browser (the browser
 * cannot see the local disk), with a "new folder" action. Defaults to ~/Coday, created when missing.
 * Opening a folder selects it: there is no separate "use this folder" step.
 */
@Component({
  selector: 'app-folder-step',
  templateUrl: './folder-step.component.html',
  styleUrls: ['./step.scss', './folder-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FolderStepComponent implements OnInit {
  protected readonly state = inject(SetupStateService)

  protected readonly listing = signal<BrowseResult | null>(null)
  protected readonly loading = signal(false)
  protected readonly browseError = signal<string | null>(null)
  protected readonly showHidden = signal(false)
  protected readonly creating = signal(false)
  protected readonly newName = signal('')

  ngOnInit(): void {
    void this.open(this.parentOfSelection())
  }

  /** Open at the closest existing ancestor of the selected folder (~/Coday may not exist yet). */
  private parentOfSelection(): string | undefined {
    const folder = this.state.folder()
    const cut = Math.max(folder.lastIndexOf('/'), folder.lastIndexOf('\\'))
    return cut > 0 ? folder.slice(0, cut) : undefined
  }

  /** Open a folder the user picked (entry, parent, home) and select it. */
  protected navigate(path?: string): Promise<void> {
    return this.open(path, true)
  }

  /**
   * List a folder. `select` makes it the selected folder once listed, under its resolved path; the initial listing
   * and the home fallback do not select, so the proposed default (which may not exist yet) is kept.
   */
  private async open(path?: string, select = false): Promise<void> {
    this.loading.set(true)
    this.browseError.set(null)
    try {
      const listing = await this.state.browse(path, this.showHidden())
      this.listing.set(listing)
      if (select) this.state.selectFolder(listing.path)
    } catch (err) {
      if (select) {
        this.browseError.set(errorMessage(err, 'Cannot open this folder.'))
        return
      }
      // The default parent may not exist either: fall back to the home directory once.
      if (path) return this.open(undefined)
      this.browseError.set(errorMessage(err, 'Cannot list this folder.'))
    } finally {
      this.loading.set(false)
    }
  }

  protected toggleHidden(): void {
    this.showHidden.update((v) => !v)
    void this.open(this.listing()?.path)
  }

  protected startCreate(): void {
    this.creating.set(true)
    this.newName.set('')
  }

  protected onName(event: Event): void {
    this.newName.set((event.target as HTMLInputElement).value)
  }

  /** Create the folder, open it and select it. */
  protected async create(event: Event): Promise<void> {
    event.preventDefault()
    const parent = this.listing()?.path
    const name = this.newName().trim()
    if (!parent || !name) return
    try {
      const created = await this.state.mkdir(parent, name)
      this.creating.set(false)
      this.state.selectFolder(created.path)
      await this.open(created.path, true)
    } catch (err) {
      this.browseError.set(errorMessage(err, 'Could not create this folder.'))
    }
  }

  protected submit(): void {
    void this.state.submitFolderAndApply()
  }
}
