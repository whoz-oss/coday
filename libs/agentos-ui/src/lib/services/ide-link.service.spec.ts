import { signal, WritableSignal } from '@angular/core'
import { TestBed } from '@angular/core/testing'
import { IDE_PROJECT_ROOT_PROMPT, IdeLinkService } from './ide-link.service'
import { DEFAULT_IDE_URL_TEMPLATE, USER_PREFERENCES_PORT } from './user-preferences.service'

describe('IdeLinkService', () => {
  let root: WritableSignal<string>
  let template: WritableSignal<string>
  let setIdeProjectRoot: jest.Mock<void, [string]>
  let service: IdeLinkService

  beforeEach(() => {
    root = signal('')
    template = signal(DEFAULT_IDE_URL_TEMPLATE)
    setIdeProjectRoot = jest.fn((value: string): void => root.set(value))
    TestBed.configureTestingModule({
      providers: [
        {
          provide: USER_PREFERENCES_PORT,
          useValue: { ideProjectRoot: root, ideUrlTemplate: template, setIdeProjectRoot },
        },
      ],
    })
    service = TestBed.inject(IdeLinkService)
  })

  afterEach(() => {
    jest.restoreAllMocks()
    TestBed.resetTestingModule()
  })

  it('builds the URL of an absolute path without asking for the project root', () => {
    const prompt = jest.spyOn(window, 'prompt')

    expect(service.resolveIdeUrl({ path: '/abs/x.kt', line: 10, column: 5 })).toBe(
      'idea://open?file=%2Fabs%2Fx.kt&line=10'
    )
    expect(prompt).not.toHaveBeenCalled()
  })

  it('resolves a relative path against the stored project root', () => {
    const prompt = jest.spyOn(window, 'prompt')
    root.set('/Users/ali/proj')

    expect(service.resolveIdeUrl({ path: 'src/a.ts', line: 42 })).toBe(
      'idea://open?file=%2FUsers%2Fali%2Fproj%2Fsrc%2Fa.ts&line=42'
    )
    expect(prompt).not.toHaveBeenCalled()
  })

  it('asks once for the project root when a relative path has none, and saves it', () => {
    const prompt = jest.spyOn(window, 'prompt').mockReturnValue('  /Users/ali/my project  ')

    expect(service.resolveIdeUrl({ path: 'src/a.ts' })).toBe(
      'idea://open?file=%2FUsers%2Fali%2Fmy%20project%2Fsrc%2Fa.ts&line=1'
    )
    expect(prompt).toHaveBeenCalledWith(IDE_PROJECT_ROOT_PROMPT)
    expect(setIdeProjectRoot).toHaveBeenCalledWith('/Users/ali/my project')

    service.resolveIdeUrl({ path: 'x.md' })
    expect(prompt).toHaveBeenCalledTimes(1)
  })

  it('returns null and saves nothing when the prompt is cancelled', () => {
    jest.spyOn(window, 'prompt').mockReturnValue(null)

    expect(service.resolveIdeUrl({ path: 'src/a.ts' })).toBeNull()
    expect(setIdeProjectRoot).not.toHaveBeenCalled()
  })

  it('returns null and saves nothing when the prompt is left blank', () => {
    jest.spyOn(window, 'prompt').mockReturnValue('   ')

    expect(service.resolveIdeUrl({ path: 'src/a.ts' })).toBeNull()
    expect(setIdeProjectRoot).not.toHaveBeenCalled()
  })

  it('fills the column of a custom template', () => {
    root.set('/root')
    template.set('idea://open?file={file}&line={line}&column={column}')

    expect(service.resolveIdeUrl({ path: './src/a.ts', line: 3, column: 7 })).toBe(
      'idea://open?file=%2Froot%2Fsrc%2Fa.ts&line=3&column=7'
    )
  })
})
