import { inject, Injectable } from '@angular/core'
import { HttpClient, HttpParams } from '@angular/common/http'
import { Observable } from 'rxjs'

export interface ModelUsageSummaryDto {
  agentName: string
  providerName: string
  modelId: string
  /** null when token counts were not collected for this period */
  promptTokens: number | null
  /** null when token counts were not collected for this period */
  completionTokens: number | null
  /** null when token counts were not collected for this period */
  totalTokens: number | null
  callCount: number
  /** optional: legacy backend may not provide it */
  cost?: number
  /** true when some underlying events in this period had missing token counts */
  tokenDataPartial?: boolean
}

export interface TokenUsageAggregationDto {
  from: string | null
  to: string | null
  /** true when any event in the period had missing token counts */
  tokenDataPartial: boolean
  models: ModelUsageSummaryDto[]
  total: ModelUsageSummaryDto
}

export interface TimeSeriesPointDto {
  date: string // yyyy-MM-dd
  agentName: string
  providerName: string
  modelId: string
  /** null when token counts were not collected for this period */
  promptTokens: number | null
  /** null when token counts were not collected for this period */
  completionTokens: number | null
  /** null when token counts were not collected for this period */
  totalTokens: number | null
  callCount: number
  /** optional: legacy backend may not provide it */
  cost?: number
}

export interface TokenUsageSeriesDto {
  from: string | null
  to: string | null
  /** true when any event in the period had missing token counts */
  tokenDataPartial: boolean
  points: TimeSeriesPointDto[]
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

  getTokenUsageSeries(params: { from?: string | null; to?: string | null }): Observable<TokenUsageSeriesDto> {
    let httpParams = new HttpParams()
    if (params.from) httpParams = httpParams.set('from', params.from)
    if (params.to) httpParams = httpParams.set('to', params.to)

    return this.http.get<TokenUsageSeriesDto>('/api/token-usage/series', { params: httpParams })
  }
}
