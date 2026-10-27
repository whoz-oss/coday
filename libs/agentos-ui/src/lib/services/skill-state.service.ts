import { inject, Injectable } from '@angular/core'
import { Skill, SkillControllerService, SkillSummary } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * SkillStateService — API layer facade for Skill entities.
 *
 * Follows the two-layer pattern: components never inject SkillControllerService
 * directly. All HTTP calls go through this service.
 *
 * Intentionally thin — it delegates 1:1 to the controller. State management
 * (refresh$, caching) lives in the consuming components, consistent with the
 * NamespacePromptsComponent / NamespaceSkillsComponent pattern.
 *
 * ## Storage asymmetry to keep in mind
 *
 * Skills have hybrid storage: Neo4j primary, filesystem secondary (a `SKILL.md`
 * under `<namespace.configPath>/skills/<name>/`). [listByNamespace] merges both
 * sources — persisted entries win on a name collision — whereas [listPlatform]
 * is database-only, because filesystem discovery is namespace-scoped and has no
 * platform scope. A namespace list can therefore contain filesystem skills while
 * the platform list never does.
 *
 * `Skill.readOnly` tells the two apart: it is true for filesystem-backed skills,
 * which the API refuses to update or delete.
 */
@Injectable({ providedIn: 'root' })
export class SkillStateService {
  private readonly skillController = inject(SkillControllerService)

  getById(id: string): Observable<Skill> {
    return this.skillController.getByIdSkill(id)
  }

  /** Platform-level skills (namespaceId = null). Database-backed only. */
  listPlatform(): Observable<Skill[]> {
    return this.skillController.listPlatformSkill()
  }

  /** Namespace-scoped skills: persisted entries merged with filesystem discovery. */
  listByNamespace(namespaceId: string): Observable<Skill[]> {
    return this.skillController.listByParentSkill(namespaceId)
  }

  /**
   * The catalogue a `*` selector resolves in this namespace, as summaries
   * (no body, no resources): namespace skills merged with platform skills,
   * namespace shadowing platform on a name collision.
   *
   * This is the candidate set for `AgentConfig.skillSelectors`. It is resolved
   * server-side by the same function the agent runtime uses, so a name offered
   * here cannot resolve differently at run time.
   */
  listEffective(namespaceId: string): Observable<SkillSummary[]> {
    return this.skillController.listEffectiveSkill(namespaceId)
  }

  create(payload: Skill): Observable<Skill> {
    return this.skillController.createSkill(payload)
  }

  update(id: string, payload: Skill): Observable<Skill> {
    return this.skillController.updateSkill(id, payload)
  }

  delete(id: string): Observable<unknown> {
    return this.skillController.deleteSkill(id)
  }
}
