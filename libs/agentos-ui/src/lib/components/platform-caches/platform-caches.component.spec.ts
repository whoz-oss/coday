import { ComponentFixture, TestBed } from '@angular/core/testing'
import { FilesystemCacheClearResult, FilesystemCacheControllerService } from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { PlatformCachesComponent } from './platform-caches.component'

describe('PlatformCachesComponent', () => {
  let controller: { clearFilesystemCache: jest.Mock }
  let response$: Subject<FilesystemCacheClearResult>
  let fixture: ComponentFixture<PlatformCachesComponent>

  const button = (): HTMLButtonElement => fixture.nativeElement.querySelector('button')
  const status = (): string =>
    fixture.nativeElement.querySelector('[role="status"]').textContent.replace(/\s+/g, ' ').trim()
  const alert = (): HTMLElement | null => fixture.nativeElement.querySelector('[role="alert"]')

  beforeEach(() => {
    response$ = new Subject<FilesystemCacheClearResult>()
    controller = { clearFilesystemCache: jest.fn().mockReturnValue(response$) }
    TestBed.configureTestingModule({
      imports: [PlatformCachesComponent],
      providers: [{ provide: FilesystemCacheControllerService, useValue: controller }],
    })
    fixture = TestBed.createComponent(PlatformCachesComponent)
    fixture.detectChanges()
  })

  it('does not call the server until the button is clicked', () => {
    expect(controller.clearFilesystemCache).not.toHaveBeenCalled()
    expect(button().textContent).toContain('Reload configuration files')
    expect(button().disabled).toBe(false)
  })

  it('calls the server once on click and disables the button while it waits', () => {
    button().click()
    fixture.detectChanges()

    expect(controller.clearFilesystemCache).toHaveBeenCalledTimes(1)
    expect(button().disabled).toBe(true)
    expect(button().textContent).toContain('Reloading…')
  })

  it('shows how many directories were cleared and enables the button again', () => {
    button().click()
    response$.next({ clearedDirectories: 7 })
    fixture.detectChanges()

    expect(status()).toBe('7 directories cleared. Files will be read again on next access.')
    expect(button().disabled).toBe(false)
    expect(alert()).toBeNull()
  })

  it('uses the singular for one directory', () => {
    button().click()
    response$.next({ clearedDirectories: 1 })
    fixture.detectChanges()

    expect(status()).toContain('1 directory cleared.')
  })

  it('shows an error when the call fails, without any success message', () => {
    button().click()
    response$.error({ status: 403 })
    fixture.detectChanges()

    expect(alert()?.textContent).toContain('Clearing the caches failed. Try again.')
    expect(status()).toBe('')
    expect(button().disabled).toBe(false)
  })

  it('clears the previous result and error when it is run again', () => {
    button().click()
    response$.error({ status: 500 })
    fixture.detectChanges()
    expect(alert()).not.toBeNull()

    response$ = new Subject<FilesystemCacheClearResult>()
    controller.clearFilesystemCache.mockReturnValue(response$)
    button().click()
    fixture.detectChanges()

    expect(alert()).toBeNull()
    expect(controller.clearFilesystemCache).toHaveBeenCalledTimes(2)
  })
})
