import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { ConfigFile } from '@whoz-oss/agentos-api-client'
import { NoticeComponent } from '@whoz-oss/design-system'

/** Above a list: how many of its configs mask their file, and which ones. */
@Component({
  selector: 'agentos-config-file-overrides-notice',
  imports: [NoticeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (overridden().length > 0) {
      <ds-notice tone="warning">{{ message() }}</ds-notice>
    }
  `,
})
export class ConfigFileOverridesNoticeComponent {
  readonly overridden = input.required<readonly ConfigFile[]>()
  /** Singular noun of the configs, e.g. `agent config`. */
  readonly noun = input.required<string>()

  protected readonly message = computed(() => {
    const files = this.overridden()
    const many = files.length > 1
    const names = files.map((file) => file.name).join(', ')
    return (
      `${files.length} ${this.noun()}${many ? 's' : ''} of this namespace ${many ? 'override their files' : 'overrides its file'} ` +
      `(${names}): AgentOS uses the database ${many ? 'copies' : 'copy'} and ignores the ` +
      `${many ? 'files' : 'file'}. Edit ${many ? 'one' : 'it'} to see the differences.`
    )
  })
}
