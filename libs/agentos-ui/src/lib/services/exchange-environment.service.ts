import { inject, Injectable } from '@angular/core'
import {
  CaseWorkspaceControllerService,
  ExchangeDiffFileStatusEnum,
  ExchangeEnvironment,
  ExchangeFileDiff,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

export type { ExchangeDiffFile as DiffFile, ExchangeEnvironment } from '@whoz-oss/agentos-api-client'
export type GitFileStatus = ExchangeDiffFileStatusEnum

/** Namespace files are shared documents; only case Exchanges have a Git environment. */
export interface EnvironmentScope {
  id: string
}

@Injectable({ providedIn: 'root' })
export class ExchangeEnvironmentService {
  private readonly api = inject(CaseWorkspaceControllerService)

  get(scope: EnvironmentScope): Observable<ExchangeEnvironment> {
    return this.api.changesCaseWorkspace(scope.id)
  }

  diff(scope: EnvironmentScope, path: string): Observable<ExchangeFileDiff> {
    return this.api.diffCaseWorkspace(scope.id, path)
  }
}
