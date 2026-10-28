import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { from, of } from 'rxjs'
import { NamespaceItemComponent } from '../namespace-item/namespace-item.component'
import { NamespaceListComponent } from './namespace-list.component'

async function render() {
  const router = { navigate: jest.fn() }
  const deleteNamespace = jest.fn(() => of(undefined))
  TestBed.configureTestingModule({
    imports: [NamespaceListComponent],
    providers: [
      { provide: Router, useValue: router },
      {
        provide: NamespaceControllerService,
        useValue: {
          listAllNamespace: () => from(Promise.resolve([{ id: 'ns-1', name: 'Platform' }])),
          deleteNamespace,
        },
      },
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
    item: fixture.debugElement.query((node) => node.componentInstance instanceof NamespaceItemComponent)
      .componentInstance as NamespaceItemComponent,
  }
}

it('renders a namespace item whose kebab only exposes the delete action', async () => {
  const { item } = await render()
  expect(item['menuItems'].map((entry) => entry.label)).toEqual(['Delete namespace'])
})

it('navigates to the namespace integrations settings from the Configure action', async () => {
  const { item, router } = await render()
  item['onIntegrations']()
  expect(router.navigate).toHaveBeenCalledWith(['/agentos', 'ns-1', 'integrations'])
})

it('deletes the namespace once the item confirms the deletion', async () => {
  const { item, deleteNamespace } = await render()
  item['onMenuAction']('delete')
  item['onDeleteConfirmed']()
  expect(deleteNamespace).toHaveBeenCalledWith('ns-1')
})
