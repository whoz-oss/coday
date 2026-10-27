import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import { IntegrationConfig } from '@whoz-oss/agentos-api-client'
import { TagComponent } from '@whoz-oss/design-system'
import { IntegrationConfigStateService, IntegrationScope } from '../../services/integration-config-state.service'

type IntegrationConfigSource = 'NEO4J' | 'FILESYSTEM'
type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }

const REDACTED_VALUE = '••••••'
const SENSITIVE_KEY =
  /(?:api[-_]?key|token|secret|password|passphrase|credential|private[-_]?key|client[-_]?secret|authorization|cookie)/i

@Component({
  selector: 'agentos-integration-config-view',
  imports: [TagComponent],
  templateUrl: './integration-config-view.component.html',
  styleUrl: './integration-config-view.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationConfigViewComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly destroyRef = inject(DestroyRef)
  private readonly state = inject(IntegrationConfigStateService)

  protected readonly integrationId = this.route.snapshot.params['integrationId'] as string
  protected readonly isLoading = signal(true)
  protected readonly hasError = signal(false)
  protected readonly config = signal<IntegrationConfig | null>(null)
  protected readonly source = signal<IntegrationConfigSource>('NEO4J')
  protected readonly parameters = signal('{}')

  ngOnInit(): void {
    this.state
      .getById(this.integrationId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (config) => {
          this.config.set(config)
          this.source.set(this.sourceOf(config))
          this.parameters.set(JSON.stringify(this.redact(config.parameters), null, 2))
          this.isLoading.set(false)
        },
        error: () => {
          this.hasError.set(true)
          this.isLoading.set(false)
        },
      })
  }

  protected scopeOf(config: IntegrationConfig): string {
    const scope: IntegrationScope = config.userId
      ? config.namespaceId
        ? 'userOnNs'
        : 'userGlobal'
      : config.namespaceId
        ? 'namespace'
        : 'platform'
    return {
      platform: 'Platform',
      namespace: 'Namespace',
      userOnNs: 'Personal on namespace',
      userGlobal: 'Personal global',
    }[scope]
  }

  private sourceOf(config: IntegrationConfig): IntegrationConfigSource {
    return (config as IntegrationConfig & { source?: IntegrationConfigSource }).source ?? 'NEO4J'
  }

  private redact(value: unknown, key = ''): JsonValue {
    if (SENSITIVE_KEY.test(key)) return REDACTED_VALUE
    if (value === null || typeof value === 'boolean' || typeof value === 'number') return value
    if (typeof value === 'string') return this.containsCredential(value) ? REDACTED_VALUE : value
    if (Array.isArray(value)) return value.map((item) => this.redact(item, key))
    if (typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value as Record<string, unknown>).map(([entryKey, entryValue]) => [
          entryKey,
          this.redact(entryValue, entryKey),
        ])
      )
    }
    return String(value ?? '')
  }

  private containsCredential(value: string): boolean {
    return /^(?:bearer\s+|basic\s+|sk[-_]|gh[opusr]_|xox[baprs]-|-----begin (?:rsa |ec |openssh )?private key-----)/i.test(
      value.trim()
    )
  }
}
