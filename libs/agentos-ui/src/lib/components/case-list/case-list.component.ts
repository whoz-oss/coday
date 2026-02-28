import { AsyncPipe } from '@angular/common'
import { HttpClient } from '@angular/common/http'
import { Component, inject, signal } from '@angular/core'
import { ActivatedRoute, Router } from '@angular/router'
import { CaseControllerService, CaseModel, Configuration } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * CaseListComponent — lists cases for the selected namespace.
 *
 * Appels directs HTTP sur /api/cases (préfixé /api/agentos pour le proxy).
 *
 * Pattern agentique : pas de bouton "Nouveau case" séparé.
 * Le premier message saisi crée le case et navigue vers le chat.
 */
@Component({
  selector: 'agentos-case-list',
  standalone: true,
  imports: [AsyncPipe],
  templateUrl: './case-list.component.html',
  styleUrl: './case-list.component.scss',
})
export class CaseListComponent {
  private readonly http = inject(HttpClient)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly config = inject(Configuration)
  private readonly caseController = inject(CaseControllerService)

  private readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected readonly cases$: Observable<CaseModel[]> = this.caseController.listByParent1(this.namespaceId)

  protected inputValue = signal('')

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.submit()
    }
  }

  protected submit(): void {
    const content = this.inputValue().trim()
    if (!content) return

    this.http
      .post<CaseModel>(`${this.config.basePath}/api/cases`, { projectId: this.namespaceId, metadata: {} })
      .subscribe((createdCase) => {
        this.inputValue.set('')
        this.router.navigate(['/agentos', this.namespaceId, 'cases', createdCase.id])
      })
  }

  protected trackById(_index: number, c: CaseModel): string {
    return c.id
  }
}
