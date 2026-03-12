import { inject, Injectable } from '@angular/core'
import { HttpClient, HttpParams } from '@angular/common/http'
import { Observable } from 'rxjs'

export interface ModelUsageSummaryDto {
  modelName: string
  providerName: string
  modelId: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  callCount: number
  /** optional: legacy backend may not provide it */
  cost?: number
}

export interface TokenUsageAggregationDto {
  from: string | null
  to: string | null
  models: ModelUsageSummaryDto[]
  total: ModelUsageSummaryDto
}

export interface TimeSeriesPointDto {
  date: string // yyyy-MM-dd
  modelName: string
  providerName: string
  modelId: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  callCount: number
  /** optional: legacy backend may not provide it */
  cost?: number
}

@Injectable({ providedIn: 'root' })
export class TokenUsageApiService {
  private readonly http = inject(HttpClient)

  getTokenUsage(params: { from?: string | null; to?: string | null }): Observable<TokenUsageAggregationDto> {
    let httpParams = new HttpParams()
    if (params.from) httpParams = httpParams.set('from', params.from)
    if (params.to) httpParams = httpParams.set('to', params.to)

    return this.http.get<TokenUsageAggregationDto>('/api/token-usage', { params: httpParams })
  }

  getTokenUsageSeries(params: { from?: string | null; to?: string | null }): Observable<TimeSeriesPointDto[]> {
    let httpParams = new HttpParams()
    if (params.from) httpParams = httpParams.set('from', params.from)
    if (params.to) httpParams = httpParams.set('to', params.to)

    return this.http.get<TimeSeriesPointDto[]>('/api/token-usage/series', { params: httpParams })
  }
}
