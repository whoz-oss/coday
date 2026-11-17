import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { ConfigFile, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { TagComponent } from '@whoz-oss/design-system'

/**
 * Where a config of a namespace comes from: its file, a database copy that masks its file, or the
 * database alone. The last one is only said when the namespace has config files, so a namespace
 * without any keeps its lists as they were.
 */
@Component({
  selector: 'agentos-config-file-tag',
  imports: [TagComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (file(); as entry) {
      @if (entry.state === fileState) {
        <ds-tag variant="neutral" [attr.title]="'Read from ' + entry.path">{{ fileLabel() }}</ds-tag>
      } @else {
        <ds-tag
          variant="outline"
          [attr.title]="'This database copy masks ' + entry.path + ': AgentOS uses the copy and ignores the file'"
        >
          Overrides {{ fileLabel() }} · {{ entry.modified ? 'modified' : 'identical' }}
        </ds-tag>
      }
    } @else if (showDatabase()) {
      <ds-tag variant="outline" title="Stored in the database only: no file of this namespace defines it">
        Database
      </ds-tag>
    }
  `,
})
export class ConfigFileTagComponent {
  /** The entry of this config in the namespace's config files, if a file defines it. */
  readonly file = input<ConfigFile | undefined>()
  /** How the lists already name the file of this kind: `YAML`, or `SKILL.md` for a skill. */
  readonly fileLabel = input('YAML')
  readonly showDatabase = input(false)

  protected readonly fileState = ConfigFileStateEnum.FILE
}
