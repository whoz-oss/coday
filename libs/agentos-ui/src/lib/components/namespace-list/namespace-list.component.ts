import { AsyncPipe } from '@angular/common'
import { Component, inject } from '@angular/core'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'

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
  // private readonly http = inject(HttpClient)
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)

  // protected readonly namespaces$: Observable<Namespace[]> = this.http.get<Namespace[]>(
  //   '/api/agentos/api/namespaces/list'
  // )

  protected readonly namespaces$ = this.namespaceController.listAll()

  protected select(ns: Namespace): void {
    this.router.navigate(['/agentos', ns.id, 'cases'])
  }

  protected trackById(_index: number, ns: Namespace): string {
    return ns.id
  }
}
