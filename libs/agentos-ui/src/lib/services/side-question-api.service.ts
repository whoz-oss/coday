import { HttpClient } from '@angular/common/http'
import { inject, Injectable } from '@angular/core'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/** The body of `POST /api/cases/{caseId}/side-questions` answered. */
export interface SideQuestionAnswer {
  answer: string
}

/**
 * SideQuestionApiService — the HTTP layer of the /btw side questions: one method per endpoint, no state.
 * Components go through [SideQuestionStateService].
 */
@Injectable({ providedIn: 'root' })
export class SideQuestionApiService {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  /** POST /api/cases/{caseId}/side-questions: nothing is saved in the case. */
  ask(caseId: string, question: string): Observable<SideQuestionAnswer> {
    return this.http.post<SideQuestionAnswer>(`${this.config.basePath}/api/cases/${caseId}/side-questions`, {
      question,
    })
  }
}
