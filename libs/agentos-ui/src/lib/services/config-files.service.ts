import { computed, inject, Injectable, Signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import {
  ConfigFile,
  ConfigFileControllerService,
  ConfigFileDiff,
  ConfigFileKindEnum,
  ConfigFileWriteResult,
} from '@whoz-oss/agentos-api-client'
import { catchError, map, Observable, of, switchMap } from 'rxjs'

export type ConfigFileKind = ConfigFileKindEnum

const OCTET_STREAM = { httpHeaderAccept: 'application/octet-stream' } as never

/**
 * Which configs of a namespace come from the files of its configPath, which database copies mask those
 * files, and the actions on such a copy: write it to its file, download it as that file, discard it (#1673).
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

  /** Writes the copy to its file, refused if the file no longer has [expectedFileHash], then deletes the copy. */
  writeToFile(kind: ConfigFileKind, id: string, expectedFileHash: string): Observable<ConfigFileWriteResult> {
    const request = { expectedFileHash }
    switch (kind) {
      case ConfigFileKindEnum.AGENT:
        return this.api.agentConfigWriteToFileConfigFile(id, request)
      case ConfigFileKindEnum.INTEGRATION:
        return this.api.integrationConfigWriteToFileConfigFile(id, request)
      case ConfigFileKindEnum.SKILL:
        return this.api.skillWriteToFileConfigFile(id, request)
      case ConfigFileKindEnum.PROMPT:
        return this.api.promptWriteToFileConfigFile(id, request)
    }
  }

  /** Deletes the copy without writing anything: AgentOS reads its file again. */
  discard(kind: ConfigFileKind, id: string): Observable<ConfigFileWriteResult> {
    switch (kind) {
      case ConfigFileKindEnum.AGENT:
        return this.api.agentConfigDiscardCopyConfigFile(id)
      case ConfigFileKindEnum.INTEGRATION:
        return this.api.integrationConfigDiscardCopyConfigFile(id)
      case ConfigFileKindEnum.SKILL:
        return this.api.skillDiscardCopyConfigFile(id)
      case ConfigFileKindEnum.PROMPT:
        return this.api.promptDiscardCopyConfigFile(id)
    }
  }

  /** The copy as the file a write would write: one file, or a zip of a skill's folder. */
  download(kind: ConfigFileKind, id: string): Observable<Blob> {
    const blob = (request: Observable<unknown>) => request.pipe(map((body) => body as Blob))
    switch (kind) {
      case ConfigFileKindEnum.AGENT:
        return blob(this.api.agentConfigFile(id, 'body', false, OCTET_STREAM))
      case ConfigFileKindEnum.INTEGRATION:
        return blob(this.api.integrationConfigFile(id, 'body', false, OCTET_STREAM))
      case ConfigFileKindEnum.SKILL:
        return blob(this.api.skillFileConfigFile(id, 'body', false, OCTET_STREAM))
      case ConfigFileKindEnum.PROMPT:
        return blob(this.api.promptFileConfigFile(id, 'body', false, OCTET_STREAM))
    }
  }
}

/** The name the download of the file at [path] is saved under: the file itself, or its folder zipped for a skill. */
export function downloadName(kind: ConfigFileKind, path: string): string {
  const segments = path.split('/')
  return kind === ConfigFileKindEnum.SKILL ? `${segments.at(-2) ?? 'skill'}.zip` : (segments.at(-1) ?? path)
}

/** The config files of one kind of a namespace, as a list needs them. */
export interface ConfigFileStatuses {
  /** The entry of the config a list shows under [id], if a file defines that config. */
  of(id: string | undefined): ConfigFile | undefined
  /** True when the namespace has at least one config file: its other configs then live only in the database. */
  readonly any: Signal<boolean>
}

/**
 * Loads the config files of [namespaceId], again each time [reload$] emits, and keeps those of [kind].
 * Must run in an injection context, like a field initializer.
 */
export function injectConfigFileStatuses(
  namespaceId: string | undefined,
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
  }
}

/** The entry of the config [configId] in the config files of [namespaceId], if a file defines it. */
export function injectConfigFile(
  namespaceId: string | undefined,
  kind: ConfigFileKind,
  configId: string | undefined
): Signal<ConfigFile | undefined> {
  const statuses = injectConfigFileStatuses(configId ? namespaceId : undefined, kind)
  return computed(() => statuses.of(configId))
}
