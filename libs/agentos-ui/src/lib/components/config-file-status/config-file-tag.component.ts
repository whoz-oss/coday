import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { ConfigFile, ConfigFileStateEnum } from '@whoz-oss/agentos-api-client'
import { TagComponent } from '@whoz-oss/design-system'

/**
 * Where a config of a namespace comes from, in short tags next to its scope:
 * - its file: `YAML` (or `SKILL.md`);
 * - a database copy that masks its file: the file's tag, then `Out of sync` when the copy differs from the
 *   file, or `Overridden` when both say the same;
 * - the database alone: `Database`, only said when the namespace has config files.
 */
@Component({
  selector: 'agentos-config-file-tag',
  imports: [TagComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: contents;
    }
  `,
  template: `
    @if (file(); as entry) {
      <ds-tag variant="neutral" [attr.title]="entry.path">{{ fileLabel() }}</ds-tag>
      @if (entry.state !== fileState) {
        @if (entry.modified) {
          <ds-tag
            variant="warning"
            [attr.title]="
              'This database copy differs from ' + entry.path + ' and masks it. Edit it to see the differences.'
            "
          >
            Out of sync
          </ds-tag>
        } @else {
          <ds-tag variant="outline" [attr.title]="'This database copy masks ' + entry.path + ', which says the same'">
            Overridden
          </ds-tag>
        }
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
