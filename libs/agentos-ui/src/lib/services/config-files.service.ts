import { computed, inject, Injectable, Signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import {
  ConfigFile,
  ConfigFileControllerService,
  ConfigFileDiff,
  ConfigFileKindEnum,
  ConfigFileStateEnum,
} from '@whoz-oss/agentos-api-client'
import { catchError, Observable, of, switchMap } from 'rxjs'

export type ConfigFileKind = ConfigFileKindEnum

/**
 * Which configs of a namespace come from the files of its configPath, and which database copies mask
 * those files (#1673).
 */
@Injectable({ providedIn: 'root' })
export class ConfigFilesService {
  private readonly api = inject(ConfigFileControllerService)

  /** The config files of a namespace. Empty when they cannot be read: a failure only hides the tags. */
  list(namespaceId: string): Observable<ConfigFile[]> {
    return this.api.listConfigFiles(namespaceId).pipe(catchError(() => of([] as ConfigFile[])))
  }

  /** A database copy and the file it masks, both in the file format. */
  diff(kind: ConfigFileKind, id: string): Observable<ConfigFileDiff> {
    switch (kind) {
      case ConfigFileKindEnum.AGENT:
        return this.api.agentConfigFileDiff(id)
      case ConfigFileKindEnum.INTEGRATION:
        return this.api.integrationConfigFileDiff(id)
      case ConfigFileKindEnum.SKILL:
        return this.api.skillFileDiffConfigFile(id)
      case ConfigFileKindEnum.PROMPT:
        return this.api.promptFileDiffConfigFile(id)
    }
  }
}

/** The config files of one kind of a namespace, as a list needs them. */
export interface ConfigFileStatuses {
  /** The entry of the config a list shows under [id], if a file defines that config. */
  of(id: string | undefined): ConfigFile | undefined
  /** True when the namespace has at least one config file: its other configs then live only in the database. */
  readonly any: Signal<boolean>
  /** The database copies of this kind that mask a file. */
  readonly overridden: Signal<readonly ConfigFile[]>
}

/**
 * Loads the config files of [namespaceId], again each time [reload$] emits, and keeps those of [kind].
 * Must run in an injection context, like a field initializer.
 */
export function injectConfigFileStatuses(
  namespaceId: string,
  kind: ConfigFileKind,
  reload$: Observable<unknown> = of(undefined)
): ConfigFileStatuses {
  const service = inject(ConfigFilesService)
  const files = toSignal(reload$.pipe(switchMap(() => (namespaceId ? service.list(namespaceId) : of([])))), {
    initialValue: [] as ConfigFile[],
  })
  const ofKind = computed(() => files().filter((file) => file.kind === kind))
  const byId = computed(() => new Map(ofKind().map((file) => [file.configId, file])))
  return {
    of: (id) => (id ? byId().get(id) : undefined),
    any: computed(() => files().length > 0),
    overridden: computed(() => ofKind().filter((file) => file.state === ConfigFileStateEnum.OVERRIDDEN)),
  }
}
