import { inject, Injectable } from '@angular/core'
import { AiModel, AiModelControllerService } from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, map, Observable, of } from 'rxjs'

/**
 * AiModelStateService — facade over AiModelControllerService for the components that only need to
 * read models (the agent-config form). The CRUD screens of the models themselves predate it.
 */
@Injectable({ providedIn: 'root' })
export class AiModelStateService {
  private readonly aiModelController = inject(AiModelControllerService)

  /**
   * The models an agent of this scope can name in `AgentConfig.modelName`: the platform models, plus
   * the namespace's own when [namespaceId] is given. This mirrors `AiModelService.findAiModel`, which
   * resolves a name among `findAllForNamespace` (namespace + platform).
   *
   * Fail-safe per call, so a user who cannot read one scope still gets the other: a refused list is
   * logged and read as empty. The caller treats an empty result as "nothing to pick from" and falls
   * back to free text, so a failure never blocks the form.
   */
  listAvailable(namespaceId?: string): Observable<AiModel[]> {
    const platform$ = this.aiModelController.listPlatformLevelAiModel().pipe(this.orEmpty('platform'))
    const namespace$ = namespaceId
      ? this.aiModelController.listByNamespaceIdAiModel(namespaceId).pipe(this.orEmpty('namespace'))
      : of([] as AiModel[])
    return forkJoin({ platform: platform$, namespace: namespace$ }).pipe(
      map(({ platform, namespace }) => [...platform, ...namespace])
    )
  }

  private orEmpty(scope: string): (source: Observable<AiModel[]>) => Observable<AiModel[]> {
    return catchError((err) => {
      console.error(`[ai-model-state] failed to load the ${scope} AI models`, err)
      return of([] as AiModel[])
    })
  }
}
