import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { IntegrationTypeControllerService, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { from, of, throwError } from 'rxjs'
import { NamespaceListComponent } from './namespace-list.component'

async function render(listTypesIntegrationType: jest.Mock): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    imports: [NamespaceListComponent],
    providers: [
      { provide: Router, useValue: { navigate: jest.fn() } },
      {
        provide: NamespaceControllerService,
        useValue: { listAllNamespace: () => from(Promise.resolve([{ id: 'ns-1', name: 'Platform' }])) },
      },
      { provide: IntegrationTypeControllerService, useValue: { listTypesIntegrationType } },
    ],
  })
  const fixture = TestBed.createComponent(NamespaceListComponent)
  fixture.detectChanges()
  await fixture.whenStable()
  fixture.detectChanges()
  return fixture.nativeElement
}

function chipLabels(element: HTMLElement): string[] {
  return Array.from(element.querySelectorAll('.ac-chip')).map((chip) => chip.textContent?.trim() ?? '')
}

it('offers namespace Git settings when the GIT plugin is loaded', async () => {
  const element = await render(jest.fn(() => of([{ type: 'BASH' }, { type: 'GIT' }])))

  expect(chipLabels(element)).toContain('Git')
})

it('hides namespace Git settings without the GIT plugin', async () => {
  const element = await render(jest.fn(() => of([{ type: 'BASH' }])))

  expect(chipLabels(element)).toContain('Members')
  expect(chipLabels(element)).not.toContain('Git')
})

it('hides namespace Git settings when the integration catalogue cannot be read', async () => {
  const element = await render(jest.fn(() => throwError(() => new Error('offline'))))

  expect(chipLabels(element)).toContain('Members')
  expect(chipLabels(element)).not.toContain('Git')
})
