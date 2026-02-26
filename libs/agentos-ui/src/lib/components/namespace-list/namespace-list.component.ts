import { AsyncPipe } from '@angular/common'
import { HttpClient } from '@angular/common/http'
import { Component, inject } from '@angular/core'
import { Router } from '@angular/router'
import { Namespace } from '@whoz-oss/agentos-api-client'
import { Observable } from 'rxjs'

/**
 * NamespaceListComponent — lists available namespaces.
 *
 * Appel direct HTTP en attendant que le client généré (listAll) soit corrigé
 * (actuellement généré en POST /api/namespaces/list au lieu de GET /api/namespaces).
 */
@Component({
  selector: 'agentos-namespace-list',
  standalone: true,
  imports: [AsyncPipe],
  templateUrl: './namespace-list.component.html',
  styleUrl: './namespace-list.component.scss',
})
export class NamespaceListComponent {
  private readonly http = inject(HttpClient)
  private readonly router = inject(Router)

  protected readonly namespaces$: Observable<Namespace[]> = this.http.get<Namespace[]>(
    '/api/agentos/api/namespaces/list'
  )

  protected select(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'cases'])
  }

  protected trackById(_index: number, ns: Namespace): string {
    return ns.id
  }
}
