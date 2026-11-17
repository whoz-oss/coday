import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core'
import { toObservable, toSignal } from '@angular/core/rxjs-interop'
import { MatDialog } from '@angular/material/dialog'
import { ConfigFile, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, NoticeComponent } from '@whoz-oss/design-system'
import { of, switchMap } from 'rxjs'
import { ConfigFileKind, ConfigFilesService } from '../../services/config-files.service'
import type { ConfigFileDiffData } from './config-file-diff.component'

/**
 * On the form of a config: says when it is a database copy that masks a file of the namespace, and
 * opens the differences between the two.
 */
@Component({
  selector: 'agentos-config-file-banner',
  imports: [ButtonDirective, NoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .config-file-banner__actions {
      margin-top: 0.5rem;
    }
  `,
  template: `
    @if (entry(); as file) {
      <ds-notice tone="warning">
        This {{ noun() }} overrides the file <code>{{ file.path }}</code
        >: AgentOS uses this version and ignores the file.
        {{ file.modified ? 'It differs from the file.' : 'It is identical to the file.' }}
        <div class="config-file-banner__actions">
          <button dsButton type="button" (click)="showDifferences(file)">Show differences</button>
        </div>
      </ds-notice>
    }
  `,
})
export class ConfigFileBannerComponent {
  readonly namespaceId = input<string | undefined>()
  readonly kind = input.required<ConfigFileKind>()
  /** Id of the config the form edits. */
  readonly configId = input<string | undefined>()
  /** Singular noun of the config, e.g. `agent config`. */
  readonly noun = input.required<string>()

  private readonly service = inject(ConfigFilesService)
  private readonly dialog = inject(MatDialog)
  private readonly files = toSignal(
    toObservable(this.namespaceId).pipe(
      switchMap((namespaceId) => (namespaceId ? this.service.list(namespaceId) : of([])))
    ),
    { initialValue: [] as ConfigFile[] }
  )
  protected readonly entry = computed(() =>
    this.files().find(
      (file) =>
        file.kind === this.kind() && file.configId === this.configId() && file.state === ConfigFileStateEnum.OVERRIDDEN
    )
  )

  /** The dialog and its diff2html styles load on demand, out of the forms' bundle. */
  protected async showDifferences(file: ConfigFile): Promise<void> {
    const { ConfigFileDiffComponent } = await import('./config-file-diff.component')
    const data: ConfigFileDiffData = { kind: file.kind, id: file.configId, name: file.name }
    this.dialog.open(ConfigFileDiffComponent, {
      data,
      width: '1200px',
      maxWidth: '96vw',
      height: '85vh',
      autoFocus: 'first-tabbable',
      restoreFocus: true,
    })
  }
}
