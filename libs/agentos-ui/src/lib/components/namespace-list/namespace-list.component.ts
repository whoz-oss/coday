import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, PageComponent, SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { ShellContextService } from '../../services/shell-context.service'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/** Alphabetical, regardless of case and accents, with numbers in numeric order ("ns 2" before "ns 10"). */
const byName = (a: Namespace, b: Namespace): number =>
  a.name.localeCompare(b.name, undefined, { sensitivity: 'base', numeric: true })

/**
 * NamespaceListComponent — table view of the namespaces the user can reach.
 *
 * Responsibilities:
 * - Load and display the namespaces in alphabetical order, with a local filter
 * - Open a namespace (its cases) from its row or its Open action, configure it (its settings shell) or create one
 * - Delete with the inline two-step confirmation shared by every list
 *
 * Create and edit logic live in NamespaceFormComponent (edit is reached from the namespace settings).
 */
@Component({
  selector: 'agentos-namespace-list',
  imports: [
    ButtonDirective,
    PageComponent,
    SectionHeaderComponent,
    TableDirective,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './namespace-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceListComponent {
  private readonly router = inject(Router)
  private readonly namespaceController = inject(NamespaceControllerService)
  private readonly shellContext = inject(ShellContextService)
  private readonly destroyRef = inject(DestroyRef)

  /** Trigger to refresh the list (emitting a new value forces re-subscription). */
  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly namespaces = signal<Namespace[]>([])
  protected readonly rowFilter = createRowFilter(this.namespaces, (ns) =>
    [ns.name, ns.description, ns.defaultAgentName].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.namespaceController.listAllNamespace()
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (namespaces) => {
          this.namespaces.set([...namespaces].sort(byName))
          this.shellContext.rememberNamespaceNames(namespaces)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  // --- Navigation ---

  protected open(ns: Namespace): void {
    void this.router.navigate(['/agentos/home'], { queryParams: { ns: ns.id } })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos/namespaces/new'])
  }

  protected configure(ns: Namespace): void {
    void this.router.navigate(['/agentos', ns.id, 'edit'])
  }

  // --- Delete ---

  protected confirmDelete(ns: Namespace): void {
    if (!ns.id) return
    this.deletion.execute(ns.id, {
      request$: this.namespaceController.deleteNamespace(ns.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${ns.name}" failed. Try again.`,
    })
  }
}
