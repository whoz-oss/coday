import { AgentosThemeService } from './theme.service'

describe('AgentosThemeService', () => {
  const STORAGE_KEY = 'agentos.theme'
  let removeSpy: jest.Mock

  const mockMatchMedia = (matches: boolean): void => {
    removeSpy = jest.fn()
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: jest.fn().mockImplementation((query: string) => ({
        matches,
        media: query,
        onchange: null,
        addEventListener: jest.fn(),
        removeEventListener: removeSpy,
        addListener: jest.fn(),
        removeListener: jest.fn(),
        dispatchEvent: jest.fn(),
      })),
    })
  }

  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    mockMatchMedia(false)
  })

  it('defaults to system when nothing is stored', () => {
    expect(new AgentosThemeService().theme()).toBe('system')
  })

  it('reads and applies the persisted theme on init', () => {
    localStorage.setItem(STORAGE_KEY, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme()).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  it('persists and applies the selected theme', () => {
    const service = new AgentosThemeService()

    service.setTheme('dark')
    expect(service.theme()).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')

    service.setTheme('light')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('light')
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('reflects theme changes on the theme signal', () => {
    const service = new AgentosThemeService()
    expect(service.theme()).toBe('system')
    service.setTheme('dark')
    expect(service.theme()).toBe('dark')
  })

  it('resolves system to the OS preference', () => {
    mockMatchMedia(true) // OS prefers dark
    const service = new AgentosThemeService()
    service.setTheme('system')
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
  })

  it('falls back to system for an invalid stored value', () => {
    localStorage.setItem(STORAGE_KEY, 'rainbow')
    expect(new AgentosThemeService().theme()).toBe('system')
  })

  it('removes its system-preference listener on destroy', () => {
    const service = new AgentosThemeService()
    service.ngOnDestroy()
    expect(removeSpy).toHaveBeenCalledTimes(1)
  })
})
