import { Injectable, inject } from '@angular/core'
import { Observable, catchError, of, switchMap, tap } from 'rxjs'
import { AgentOsApiService } from './agentos-api.service'
import { AGENTOS_BASE_URL, buildAgentOsCaseUrl } from './factory.store'
import { FactoryRun, SupervisorCaseResult } from './models'
import { SupervisorCaseStoreService } from './supervisor-case-store.service'

/** Owns creation and browser persistence of supervisor assistance cases. */
@Injectable({ providedIn: 'root' })
export class SupervisorCaseFacade {
  private readonly agentOs = inject(AgentOsApiService)
  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)
  private readonly store = inject(SupervisorCaseStoreService)

  restore(run: FactoryRun): SupervisorCaseResult | undefined {
    const namespaceId = run.namespaceId
    const workflowId = run.id
    if (!namespaceId || !workflowId) return undefined
    const entry = this.store.load({ workflowId, namespaceId })
    if (!entry) return undefined
    return {
      caseId: entry.caseId,
      namespaceId: entry.namespaceId,
      agentOsUrl: buildAgentOsCaseUrl(entry.caseId, entry.namespaceId, this.agentOsBaseUrl),
    }
  }

  open(run: FactoryRun): Observable<SupervisorCaseResult> {
    const namespaceId = run.namespaceId!
    const workflowId = run.id
    const title = `Supervisor assistance — ${run.title}`
    const initialMessage = buildSupervisorCaseMessage(run.title, workflowId, run.ticket, run.workflowType)
    return this.agentOs.createCase({ namespaceId, title }).pipe(
      tap((createdCase) => {
        this.store.save(
          { workflowId, namespaceId },
          { caseId: createdCase.id, namespaceId, createdAt: new Date().toISOString() }
        )
      }),
      switchMap((createdCase) =>
        this.agentOs.addMessage(createdCase.id, initialMessage).pipe(
          catchError(() => of(undefined)),
          switchMap(() =>
            of<SupervisorCaseResult>({
              caseId: createdCase.id,
              namespaceId,
              agentOsUrl: buildAgentOsCaseUrl(createdCase.id, namespaceId, this.agentOsBaseUrl),
            })
          )
        )
      )
    )
  }
}

function buildSupervisorCaseMessage(
  sandboxName: string,
  workflowId: string,
  ticket?: string,
  workflowType?: string
): string {
  return [
    `@Heimdall Hello. I am opening this case from the Factory Cockpit for sandbox **${sandboxName}**.`,
    '',
    'Available identifiers:',
    `- Workflow ID: \`${workflowId}\``,
    ...(ticket ? [`- Ticket: \`${ticket}\``] : []),
    ...(workflowType ? [`- Workflow type: \`${workflowType}\``] : []),
    '',
    'Could you check the state of this workflow and let me know what is happening?',
  ].join('\n')
}
