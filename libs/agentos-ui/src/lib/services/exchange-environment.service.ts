import { inject, Injectable } from '@angular/core'
import {
  ExchangeEnvironmentControllerService,
  ExchangeDiffFileStatusEnum,
  ExchangeEnvironment,
  ExchangeFileDiff,
} from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

export type { ExchangeDiffFile as DiffFile, ExchangeEnvironment } from '@whoz-oss/agentos-api-client'
export type GitFileStatus = ExchangeDiffFileStatusEnum

/** Namespace files are shared documents; only case Exchanges have a Git environment. */
export interface EnvironmentScope {
  kind: 'cases'
  id: string
}

@Injectable({ providedIn: 'root' })
export class ExchangeEnvironmentService {
  private readonly api = inject(ExchangeEnvironmentControllerService)

  get(scope: EnvironmentScope): Observable<ExchangeEnvironment> {
    return this.api.caseEnvironmentExchangeEnvironment(scope.id)
  }

  diff(scope: EnvironmentScope, path: string): Observable<ExchangeFileDiff> {
    return this.api.caseDiffExchangeEnvironment(scope.id, path)
  }
}
