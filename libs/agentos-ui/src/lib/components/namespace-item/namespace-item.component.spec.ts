import { TestBed } from '@angular/core/testing'
import { Namespace, UsageConfigurationControllerService } from '@whoz-oss/agentos-api-client'
import { of, Subject } from 'rxjs'
import { NamespaceItemComponent } from './namespace-item.component'

describe('NamespaceItemComponent usage link', () => {
  let configuration: { getUsageConfiguration: jest.Mock }

  beforeEach(() => {
    configuration = { getUsageConfiguration: jest.fn() }
    TestBed.configureTestingModule({
      imports: [NamespaceItemComponent],
      providers: [{ provide: UsageConfigurationControllerService, useValue: configuration }],
    })
  })

  function render() {
    const fixture = TestBed.createComponent(NamespaceItemComponent)
    fixture.componentRef.setInput('namespace', { id: 'ns', name: 'Test namespace' } as Namespace)
    fixture.detectChanges()
    return fixture
  }

  it('hides usage while settings are loading and when tracking is disabled', () => {
    const settings = new Subject<{ enabled: boolean }>()
    configuration.getUsageConfiguration.mockReturnValue(settings)
    const fixture = render()
    expect(fixture.nativeElement.textContent).not.toContain('Usage and costs')
    settings.next({ enabled: false })
    settings.complete()
    fixture.detectChanges()
    expect(fixture.nativeElement.textContent).not.toContain('Usage and costs')
  })

  it('shares settings across namespace cards and exposes usage when enabled', () => {
    configuration.getUsageConfiguration.mockReturnValue(of({ enabled: true }))
    const first = render()
    const second = render()
    expect(first.nativeElement.textContent).toContain('Usage and costs')
    expect(second.nativeElement.textContent).toContain('Usage and costs')
    expect(configuration.getUsageConfiguration).toHaveBeenCalledTimes(1)
    const emit = jest.spyOn(first.componentInstance.usageRequested, 'emit')
    const button = Array.from(first.nativeElement.querySelectorAll('button')).find((element) =>
      (element as HTMLButtonElement).textContent?.includes('Usage and costs')
    ) as HTMLButtonElement
    button.click()
    expect(emit).toHaveBeenCalledWith({ id: 'ns', name: 'Test namespace' })
  })
})
