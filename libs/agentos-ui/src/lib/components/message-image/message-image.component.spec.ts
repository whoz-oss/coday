import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { of, throwError } from 'rxjs'
import { ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import { MessageImageComponent } from './message-image.component'

describe('MessageImageComponent', () => {
  let getBlobIn: jest.Mock
  let revokeObjectURL: jest.Mock

  beforeEach(() => {
    getBlobIn = jest.fn().mockReturnValue(of(new Blob(['x'], { type: 'image/png' })))
    revokeObjectURL = jest.fn()
    // jsdom has no object URLs.
    URL.createObjectURL = jest.fn(() => 'blob:thumb')
    URL.revokeObjectURL = revokeObjectURL
    TestBed.configureTestingModule({ providers: [{ provide: ExchangeStateService, useValue: { getBlobIn } }] })
  })

  afterEach(() => TestBed.resetTestingModule())

  function make(scope: ExchangeScope = ExchangeFileEntryScopeEnum.CASE): ComponentRef<MessageImageComponent> {
    const ref = createComponent(MessageImageComponent, { environmentInjector: TestBed.inject(EnvironmentInjector) })
    TestBed.inject(ApplicationRef).attachView(ref.hostView)
    ref.setInput('caseId', 'c-1')
    ref.setInput('namespaceId', 'ns-1')
    ref.setInput('scope', scope)
    ref.setInput('path', 'pasted.png')
    ref.setInput('name', 'pasted.png')
    // The effect reads the image, the second pass renders it.
    ref.changeDetectorRef.detectChanges()
    ref.changeDetectorRef.detectChanges()
    return ref
  }

  const host = (ref: ComponentRef<MessageImageComponent>) => ref.location.nativeElement as HTMLElement

  it('shows the image of the case as a thumbnail, read from the case it is in', () => {
    const ref = make()

    expect(getBlobIn).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.CASE, 'c-1', 'pasted.png')
    expect(host(ref).querySelector('.message-image__thumb img')?.getAttribute('src')).toBe('blob:thumb')
  })

  it('reads an image of the namespace from the namespace', () => {
    make(ExchangeFileEntryScopeEnum.NAMESPACE)

    expect(getBlobIn).toHaveBeenCalledWith(ExchangeFileEntryScopeEnum.NAMESPACE, 'ns-1', 'pasted.png')
  })

  it('opens full size on click, and closes with Escape or the backdrop', () => {
    const ref = make()
    expect(host(ref).querySelector('[role="dialog"]')).toBeNull()
    ;(host(ref).querySelector('.message-image__thumb') as HTMLButtonElement).click()
    ref.changeDetectorRef.detectChanges()
    expect(host(ref).querySelector('[role="dialog"] .message-image__full')).not.toBeNull()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    ref.changeDetectorRef.detectChanges()
    expect(host(ref).querySelector('[role="dialog"]')).toBeNull()
    ;(host(ref).querySelector('.message-image__thumb') as HTMLButtonElement).click()
    ref.changeDetectorRef.detectChanges()
    ;(host(ref).querySelector('.message-image__backdrop') as HTMLButtonElement).click()
    ref.changeDetectorRef.detectChanges()
    expect(host(ref).querySelector('[role="dialog"]')).toBeNull()
  })

  it('falls back on the file chip when the image cannot be read', () => {
    getBlobIn.mockReturnValue(throwError(() => ({ status: 404 })))

    const ref = make()

    expect(host(ref).querySelector('.message-image__thumb')).toBeNull()
    expect(host(ref).querySelector('agentos-file-chip')).not.toBeNull()
  })

  it('releases the image when the message goes away', () => {
    const ref = make()

    ref.destroy()

    expect(revokeObjectURL).toHaveBeenCalledWith('blob:thumb')
  })
})
