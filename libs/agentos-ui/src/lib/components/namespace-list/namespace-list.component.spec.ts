import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { IntegrationTypeControllerService, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { from, of, throwError } from 'rxjs'
import { UsageConfigurationService } from '../../services/usage-configuration.service'
import { NamespaceItemComponent } from '../namespace-item/namespace-item.component'
import { NamespaceListComponent } from './namespace-list.component'

async function render(listTypesIntegrationType: jest.Mock, usageEnabled = false) {
  const router = { navigate: jest.fn() }
  TestBed.configureTestingModule({
    imports: [NamespaceListComponent],
    providers: [
      { provide: Router, useValue: router },
      {
        provide: NamespaceControllerService,
        useValue: { listAllNamespace: () => from(Promise.resolve([{ id: 'ns-1', name: 'Platform' }])) },
      },
      { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType } },
      { provide: UsageConfigurationService, useValue: { enabled: signal(usageEnabled) } },
    ],
  })
  const fixture = TestBed.createComponent(NamespaceListComponent)
  fixture.detectChanges()
  await fixture.whenStable()
  fixture.detectChanges()
  return {
    fixture,
    router,
    item: fixture.debugElement.query((node) => node.componentInstance instanceof NamespaceItemComponent)
      .componentInstance as NamespaceItemComponent,
  }
}

it('passes Git availability to the namespace kebab when the plugin is loaded', async () => {
  const { item } = await render(jest.fn(() => of([{ type: 'BASH' }, { type: 'GIT' }])))
  expect(item.gitAvailable()).toBe(true)
  expect(item['menuItems']().map((entry) => entry.label)).toEqual(expect.arrayContaining(['Members', 'Git']))
})

it.each([
  ['without the plugin', () => of([{ type: 'BASH' }])],
  ['when the catalogue fails', () => throwError(() => new Error('offline'))],
])('keeps Members but hides Git %s', async (_label, catalogue) => {
  const { item } = await render(jest.fn(catalogue))
  const labels = item['menuItems']().map((entry) => entry.label)
  expect(labels).toContain('Members')
  expect(labels).not.toContain('Git')
})

it('routes Git and Usage kebab actions when both capabilities are available', async () => {
  const { item, router } = await render(
    jest.fn(() => of([{ type: 'GIT' }])),
    true
  )
  item['onMenuAction']('git')
  item['onMenuAction']('usage')
  expect(router.navigate).toHaveBeenNthCalledWith(1, ['/agentos', 'ns-1', 'git'])
  expect(router.navigate).toHaveBeenNthCalledWith(2, ['/agentos', 'ns-1', 'usage'])
})

it('keeps Git navigation available while Usage is disabled', async () => {
  const { item, router } = await render(
    jest.fn(() => of([{ type: 'GIT' }])),
    false
  )
  expect(item['menuItems']().map((entry) => entry.label)).not.toContain('Usage and costs')
  item['onMenuAction']('git')
  expect(router.navigate).toHaveBeenCalledWith(['/agentos', 'ns-1', 'git'])
})
