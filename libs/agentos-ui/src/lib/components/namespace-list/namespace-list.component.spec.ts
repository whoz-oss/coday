import { signal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { IntegrationTypeControllerService, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { from, of, throwError } from 'rxjs'
import { UsageConfigurationService } from '../../services/usage-configuration.service'
import { NamespaceListComponent } from './namespace-list.component'

async function render(listTypesIntegrationType: jest.Mock, usageEnabled = false): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    imports: [NamespaceListComponent],
    providers: [
      { provide: Router, useValue: { navigate: jest.fn() } },
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
  return fixture.nativeElement
}

function chipLabels(element: HTMLElement): string[] {
  return Array.from(element.querySelectorAll('.ac-chip')).map((chip) => chip.textContent?.trim() ?? '')
}

function clickChip(element: HTMLElement, label: string): void {
  const chip = Array.from(element.querySelectorAll<HTMLButtonElement>('.ac-chip')).find(
    (button) => button.textContent?.trim() === label
  )
  expect(chip).toBeDefined()
  chip?.click()
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

it('navigates to both Git and usage settings when the plugin and usage tracking are enabled', async () => {
  const element = await render(
    jest.fn(() => of([{ type: 'GIT' }])),
    true
  )
  const router = TestBed.inject(Router)

  expect(chipLabels(element)).toEqual(expect.arrayContaining(['Git', 'Usage and costs']))
  clickChip(element, 'Git')
  clickChip(element, 'Usage and costs')

  expect(router.navigate).toHaveBeenCalledTimes(2)
  expect(router.navigate).toHaveBeenNthCalledWith(1, ['/agentos', 'ns-1', 'git'])
  expect(router.navigate).toHaveBeenNthCalledWith(2, ['/agentos', 'ns-1', 'usage'])
})

it('keeps Git navigation available when usage tracking is disabled', async () => {
  const element = await render(
    jest.fn(() => of([{ type: 'GIT' }])),
    false
  )
  const router = TestBed.inject(Router)

  expect(chipLabels(element)).toContain('Git')
  expect(chipLabels(element)).not.toContain('Usage and costs')
  clickChip(element, 'Git')

  expect(router.navigate).toHaveBeenCalledTimes(1)
  expect(router.navigate).toHaveBeenCalledWith(['/agentos', 'ns-1', 'git'])
})
