import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { Namespace, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { defer, from, of, throwError } from 'rxjs'
import { NamespaceListComponent } from './namespace-list.component'

type Internals = {
  open: (ns: Namespace) => void
  configure: (ns: Namespace) => void
  openCreateForm: () => void
  confirmDelete: (ns: Namespace) => void
  deletion: { request: (id: string) => void; error: () => string | null }
}

const platform = { id: 'ns-1', name: 'Platform', description: 'Main namespace', defaultAgentName: 'coday' } as Namespace

async function render(options: { namespaces?: Namespace[]; deleteResult?: unknown } = {}) {
  const router = { navigate: jest.fn() }
  const deleteNamespace = jest.fn(() => options.deleteResult ?? of(undefined))
  const listAllNamespace = jest.fn(() => from(Promise.resolve(options.namespaces ?? [platform])))
  TestBed.configureTestingModule({
    imports: [NamespaceListComponent],
    providers: [
      { provide: Router, useValue: router },
      { provide: NamespaceControllerService, useValue: { listAllNamespace, deleteNamespace } },
    ],
  })
  const fixture = TestBed.createComponent(NamespaceListComponent)
  fixture.detectChanges()
  await fixture.whenStable()
  fixture.detectChanges()
  return {
    fixture,
    router,
    deleteNamespace,
    listAllNamespace,
    component: fixture.componentInstance as unknown as Internals,
  }
}

it('renders one table row per namespace, with its description and default agent', async () => {
  const { fixture } = await render()
  const rows = fixture.nativeElement.querySelectorAll('tbody tr')

  expect(rows).toHaveLength(1)
  expect(rows[0].textContent).toContain('Platform')
  expect(rows[0].textContent).toContain('Main namespace')
  expect(rows[0].textContent).toContain('coday')
})

it('says so when there is no namespace yet', async () => {
  const { fixture } = await render({ namespaces: [] })

  expect(fixture.nativeElement.querySelector('table')).toBeNull()
  expect(fixture.nativeElement.textContent).toContain('No namespaces yet')
})

it('lists the namespaces in alphabetical order, whatever the order of the response', async () => {
  const { fixture } = await render({
    namespaces: [
      { id: 'b', name: 'beta' },
      { id: 'c', name: 'ns 10' },
      { id: 'a', name: 'Alpha' },
      { id: 'd', name: 'ns 2' },
    ] as Namespace[],
  })
  const names = Array.from<HTMLElement>(fixture.nativeElement.querySelectorAll('tbody tr td:first-child')).map((cell) =>
    cell.textContent?.trim()
  )

  expect(names).toEqual(['Alpha', 'beta', 'ns 2', 'ns 10'])
})

it('opens the namespace when its row is clicked', async () => {
  const { fixture, router } = await render()

  fixture.nativeElement.querySelector('tbody tr').click()

  expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], { queryParams: { ns: 'ns-1' } })
})

it('does not open the namespace when an action of its row is clicked', async () => {
  const { fixture, router } = await render()
  const configure = Array.from<HTMLButtonElement>(fixture.nativeElement.querySelectorAll('tbody button')).find(
    (button) => button.textContent?.trim() === 'Configure'
  )

  configure?.click()

  expect(router.navigate).toHaveBeenCalledTimes(1)
  expect(router.navigate).toHaveBeenCalledWith(['/agentos', 'ns-1', 'edit'])
})

it('opens the cases of a namespace from the Open action', async () => {
  const { component, router } = await render()

  component.open(platform)

  expect(router.navigate).toHaveBeenCalledWith(['/agentos/home'], { queryParams: { ns: 'ns-1' } })
})

it('navigates to the edit namespace page from the Configure action', async () => {
  const { component, router } = await render()

  component.configure(platform)

  expect(router.navigate).toHaveBeenCalledWith(['/agentos', 'ns-1', 'edit'])
})

it('navigates to the create page from the New namespace button', async () => {
  const { fixture, router } = await render()

  fixture.nativeElement.querySelector('button.ds-button--primary').click()

  expect(router.navigate).toHaveBeenCalledWith(['/agentos/namespaces/new'])
})

describe('deletion', () => {
  it('does not delete before the inline confirmation was requested', async () => {
    // The request is built eagerly by the component, so what matters is whether it is subscribed to.
    const sent = jest.fn()
    const { component } = await render({
      deleteResult: defer(() => {
        sent()
        return of(undefined)
      }),
    })

    component.confirmDelete(platform)

    expect(sent).not.toHaveBeenCalled()
  })

  it('deletes the namespace once the confirmation was requested, then reloads the list', async () => {
    const { component, deleteNamespace, listAllNamespace } = await render()

    component.deletion.request('ns-1')
    component.confirmDelete(platform)

    expect(deleteNamespace).toHaveBeenCalledWith('ns-1')
    expect(listAllNamespace).toHaveBeenCalledTimes(2)
  })

  it('shows the failure instead of swallowing it', async () => {
    const { component } = await render({ deleteResult: throwError(() => ({ status: 500 })) })

    component.deletion.request('ns-1')
    component.confirmDelete(platform)

    expect(component.deletion.error()).toBe('Deleting "Platform" failed. Try again.')
  })
})
