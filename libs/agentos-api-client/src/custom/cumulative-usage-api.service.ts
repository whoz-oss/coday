import { HttpClient, HttpParams } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable } from 'rxjs'
import { Configuration } from '../lib/configuration'

/** Lifetime union of case trees; cost is a USD lower bound when unknownCostCount > 0. */
export interface CumulativeUsageDto {
  cost: number
  unknownCostCount: number
  totalTokens: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  liveTokens: number
}

/** Additive client kept outside generated files until the next OpenAPI regeneration. */
@Injectable({ providedIn: 'root' })
export class CumulativeUsageApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  cumulativeUsage(caseIds: string[]): Observable<CumulativeUsageDto> {
    return this.http.get<CumulativeUsageDto>(`${this.config.basePath}/api/usage-records/aggregate/case-trees`, {
      params: new HttpParams().set('caseIds', caseIds.join(',')),
    })
  }
}
