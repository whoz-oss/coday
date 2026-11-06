import { ApplicationRef, ComponentRef, createComponent, EnvironmentInjector } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ComposerAttachmentsComponent } from './composer-attachments.component'
import { PendingAttachment } from './composer-attachments.utils'

/**
 * Instantiate the component inside an injection context and wire the signal inputs via
 * ComponentRef.setInput() (same recipe as case-drawer.component.spec.ts).
 */
function makeComponent(attachments: PendingAttachment[]): ComponentRef<ComposerAttachmentsComponent> {
  const environmentInjector = TestBed.inject(EnvironmentInjector)
  const appRef = TestBed.inject(ApplicationRef)

  const ref = createComponent(ComposerAttachmentsComponent, { environmentInjector })
  appRef.attachView(ref.hostView)
  ref.setInput('attachments', attachments)
  ref.changeDetectorRef.detectChanges()
  return ref
}

function attachment(name: string, status: PendingAttachment['status'] = 'pending', error?: string): PendingAttachment {
  return { id: `id-${name}`, file: new File(['x'], name), status, error }
}

describe('ComposerAttachmentsComponent', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('renders one chip per attachment with name and kind label', () => {
    const ref = makeComponent([attachment('report.pdf'), attachment('data.xlsx')])
    const host = ref.location.nativeElement as HTMLElement

    const chips = host.querySelectorAll('.composer-attachments__chip')
    expect(chips).toHaveLength(2)
    expect(chips[0]!.textContent).toContain('report.pdf')
    expect(chips[0]!.textContent).toContain('PDF')
    expect(chips[1]!.textContent).toContain('XLSX')
  })

  it('shows the mapped error on a failed chip', () => {
    const ref = makeComponent([attachment('dup.pdf', 'error', 'A file with this name already exists.')])
    const host = ref.location.nativeElement as HTMLElement

    expect(host.querySelector('.composer-attachments__chip--error')).not.toBeNull()
    expect(host.querySelector('.composer-attachments__error')?.textContent).toContain(
      'A file with this name already exists.'
    )
  })

  it('shows the namespace badge only when the input is set', () => {
    const ref = makeComponent([attachment('a.txt')])
    const host = ref.location.nativeElement as HTMLElement
    expect(host.querySelector('.composer-attachments__badge')).toBeNull()

    ref.setInput('namespaceBadge', true)
    ref.changeDetectorRef.detectChanges()

    expect(host.querySelector('.composer-attachments__badge')?.textContent).toContain('namespace')
  })

  it('shows the limit message', () => {
    const ref = makeComponent([])
    ref.setInput('limitError', 'You can attach up to 10 files per message.')
    ref.changeDetectorRef.detectChanges()

    const host = ref.location.nativeElement as HTMLElement
    expect(host.querySelector('.composer-attachments__limit')?.textContent).toContain('up to 10 files')
  })

  it('emits removed with the attachment id when the remove button is clicked', () => {
    const ref = makeComponent([attachment('a.txt')])
    const emitted: string[] = []
    ref.instance.removed.subscribe((id) => emitted.push(id))

    const removeButton = (ref.location.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.composer-attachments__chip button'
    )
    removeButton?.click()

    expect(emitted).toEqual(['id-a.txt'])
  })

  it('emits filesSelected and resets the input value on file selection', () => {
    const ref = makeComponent([])
    const emitted: File[][] = []
    ref.instance.filesSelected.subscribe((selected) => emitted.push(selected))
    const input = (ref.location.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '.composer-attachments__file-input'
    )!

    const file = new File(['x'], 'picked.txt')
    Object.defineProperty(input, 'files', { value: [file], configurable: true })
    input.dispatchEvent(new Event('change'))

    expect(emitted).toEqual([[file]])
    expect(input.value).toBe('')
  })

  it('openPicker clicks the hidden file input', () => {
    const ref = makeComponent([])
    const input = (ref.location.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '.composer-attachments__file-input'
    )!
    const clickSpy = jest.spyOn(input, 'click')

    ref.instance.openPicker()

    expect(clickSpy).toHaveBeenCalled()
  })

  it('disables the remove buttons while uploading', () => {
    const ref = makeComponent([attachment('a.txt')])
    ref.setInput('disabled', true)
    ref.changeDetectorRef.detectChanges()

    const removeButton = (ref.location.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(
      '.composer-attachments__chip button'
    )
    expect(removeButton?.disabled).toBe(true)
  })
})

describe('ComposerAttachmentsComponent — image thumbnails', () => {
  const originalCreate = URL.createObjectURL
  const originalRevoke = URL.revokeObjectURL
  let create: jest.Mock
  let revoke: jest.Mock

  beforeEach(() => {
    let counter = 0
    create = jest.fn(() => `blob:preview-${counter++}`)
    revoke = jest.fn()
    URL.createObjectURL = create
    URL.revokeObjectURL = revoke
  })

  afterEach(() => {
    // Destroy the components while the mocks are still installed (their destroy hook revokes).
    TestBed.resetTestingModule()
    URL.createObjectURL = originalCreate
    URL.revokeObjectURL = originalRevoke
  })

  function image(id: string, status: PendingAttachment['status'] = 'pending'): PendingAttachment {
    return { id, file: new File(['x'], `${id}.png`, { type: 'image/png' }), status }
  }

  it('renders a thumbnail instead of the kind icon for an image attachment', () => {
    const ref = makeComponent([image('shot'), attachment('doc.pdf')])
    const host = ref.location.nativeElement as HTMLElement

    const thumbnails = host.querySelectorAll<HTMLImageElement>('.composer-attachments__thumbnail')
    expect(thumbnails).toHaveLength(1)
    expect(thumbnails[0]!.getAttribute('src')).toBe('blob:preview-0')
    // The non-image chip keeps its kind icon.
    expect(host.querySelectorAll('.composer-attachments__icon')).toHaveLength(1)
    expect(create).toHaveBeenCalledTimes(1)
  })

  it('revokes the object URL once the attachment disappears, and the rest on destroy', () => {
    const ref = makeComponent([image('one'), image('two')])
    expect(create).toHaveBeenCalledTimes(2)

    ref.setInput('attachments', [image('two')])
    ref.changeDetectorRef.detectChanges()
    expect(revoke).toHaveBeenCalledTimes(1)
    expect(revoke).toHaveBeenCalledWith('blob:preview-0')
    // The surviving image keeps its URL: no re-creation.
    expect(create).toHaveBeenCalledTimes(2)

    ref.destroy()
    expect(revoke).toHaveBeenCalledWith('blob:preview-1')
  })

  it('shows the spinner instead of the thumbnail while uploading', () => {
    const ref = makeComponent([image('shot', 'uploading')])
    const host = ref.location.nativeElement as HTMLElement

    expect(host.querySelector('.composer-attachments__thumbnail')).toBeNull()
    expect(host.querySelector('.composer-attachments__icon')?.textContent).toContain('progress_activity')
  })
})

describe('ComposerAttachmentsComponent — status rendering', () => {
  afterEach(() => TestBed.resetTestingModule())

  it('shows a spinner icon while uploading and the kind icon otherwise', () => {
    const ref = makeComponent([
      { id: 'u', file: new File(['x'], 'up.pdf'), status: 'uploading' },
      { id: 'p', file: new File(['x'], 'wait.pdf'), status: 'pending' },
    ])
    const icons = (ref.location.nativeElement as HTMLElement).querySelectorAll('.composer-attachments__icon')

    expect(icons[0]!.textContent).toContain('progress_activity')
    expect(icons[1]!.textContent).toContain('picture_as_pdf')
  })
})
