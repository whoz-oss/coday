import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core'
import { errorMessage, SetupStateService } from '../setup-state.service'
import { BrowseResult } from '../setup.models'

/**
 * Step 3: where the Coday agent reads and writes your files. A server-backed folder browser (the browser
 * cannot see the local disk), with a "new folder" action. Defaults to ~/Coday, created when missing.
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

  protected async open(path?: string): Promise<void> {
    this.loading.set(true)
    this.browseError.set(null)
    try {
      this.listing.set(await this.state.browse(path, this.showHidden()))
    } catch (err) {
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

  protected chooseCurrent(): void {
    const current = this.listing()?.path
    if (current) this.state.selectFolder(current)
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
      await this.open(created.path)
    } catch (err) {
      this.browseError.set(errorMessage(err, 'Could not create this folder.'))
    }
  }

  protected submit(): void {
    void this.state.submitFolderAndApply()
  }
}
