import { AgentosThemeService } from './theme.service'

describe('AgentosThemeService', () => {
  const STORAGE_KEY_VARIANT = 'agentos.theme.variant'
  const STORAGE_KEY_MODE = 'agentos.theme.mode'
  const STORAGE_KEY_LEGACY = 'agentos.theme'
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

  it('defaults to industry/system when nothing is stored', () => {
    const state = new AgentosThemeService().theme()
    expect(state.variant).toBe('industry')
    expect(state.mode).toBe('system')
  })

  it('reads and applies the persisted theme on init', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'industry')
    localStorage.setItem(STORAGE_KEY_MODE, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('industry')
    expect(service.theme().mode).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')
  })

  it('persists and applies the selected theme', () => {
    const service = new AgentosThemeService()

    service.setTheme({ variant: 'industry', mode: 'dark' })
    expect(service.theme().mode).toBe('dark')
    expect(service.theme().variant).toBe('industry')
    expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY_VARIANT)).toBe('industry')
    expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')

    service.setTheme({ variant: 'industry', mode: 'light' })
    expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('light')
    // industry-light = pas d'attribut (défaut)
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('reflects theme changes on the theme signal', () => {
    const service = new AgentosThemeService()
    expect(service.theme().mode).toBe('system')
    service.setTheme({ variant: 'industry', mode: 'dark' })
    expect(service.theme().mode).toBe('dark')
  })

  it('resolves system to the OS preference (dark)', () => {
    mockMatchMedia(true) // OS prefers dark
    const service = new AgentosThemeService()
    service.setTheme({ variant: 'industry', mode: 'system' })
    expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')
  })

  it('resolves system to light when OS prefers light', () => {
    mockMatchMedia(false)
    const service = new AgentosThemeService()
    service.setTheme({ variant: 'industry', mode: 'system' })
    // industry-light = pas d'attribut
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('falls back to industry/system for an invalid stored value', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'ultraviolet')
    const state = new AgentosThemeService().theme()
    expect(state.variant).toBe('industry')
    expect(state.mode).toBe('system')
  })

  it('migrates legacy single-key storage to variant+mode keys', () => {
    localStorage.setItem(STORAGE_KEY_LEGACY, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('industry')
    expect(service.theme().mode).toBe('dark')
    // Clé legacy supprimée, nouvelles clés créées
    expect(localStorage.getItem(STORAGE_KEY_LEGACY)).toBeNull()
    expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY_VARIANT)).toBe('industry')
  })

  it('applies terminal-dark correctly', () => {
    const service = new AgentosThemeService()
    service.setTheme({ variant: 'terminal', mode: 'dark' })
    expect(document.documentElement.getAttribute('data-theme')).toBe('terminal-dark')
  })

  it('applies terminal-light correctly', () => {
    const service = new AgentosThemeService()
    service.setTheme({ variant: 'terminal', mode: 'light' })
    expect(document.documentElement.getAttribute('data-theme')).toBe('terminal-light')
  })

  it('removes its system-preference listener on destroy', () => {
    const service = new AgentosThemeService()
    service.ngOnDestroy()
    expect(removeSpy).toHaveBeenCalledTimes(1)
  })
})
