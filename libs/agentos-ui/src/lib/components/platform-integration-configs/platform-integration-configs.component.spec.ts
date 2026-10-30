import { TestBed } from '@angular/core/testing'
import { Router } from '@angular/router'
import { IntegrationConfigControllerService, IntegrationConfigExportService } from '@whoz-oss/agentos-api-client'
import { from } from 'rxjs'
import { UserStateService } from '../../services/user-state.service'
import { PlatformIntegrationConfigsComponent } from './platform-integration-configs.component'

it('renders only platform integrations through the real state service', async () => {
  const platform = { id: 'company-1', name: 'Company Jira', integrationType: 'JIRA' }
  const personal = { id: 'personal-1', name: 'Personal Jira', integrationType: 'JIRA', userId: 'user-1' }
  const listIntegrationConfig = jest.fn((namespaceId?: string, userId?: string) =>
    from(Promise.resolve(namespaceId === 'none' && userId === undefined ? [platform] : [personal]))
  )
  TestBed.configureTestingModule({
    imports: [PlatformIntegrationConfigsComponent],
    providers: [
      { provide: Router, useValue: { navigate: jest.fn() } },
      { provide: IntegrationConfigControllerService, useValue: { listIntegrationConfig } },
      { provide: IntegrationConfigExportService, useValue: {} },
      { provide: UserStateService, useValue: { currentUser: () => ({ id: 'user-1' }) } },
    ],
  })
  const fixture = TestBed.createComponent(PlatformIntegrationConfigsComponent)
  fixture.detectChanges()
  await fixture.whenStable()
  fixture.detectChanges()

  expect(fixture.nativeElement.textContent).toContain('Company Jira')
  expect(fixture.nativeElement.textContent).not.toContain('Personal Jira')
  expect(listIntegrationConfig.mock.calls.length).toBeGreaterThan(0)
  expect(listIntegrationConfig.mock.calls.every((args) => args.length === 1 && args[0] === 'none')).toBe(true)
})
