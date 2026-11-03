import {
  AgentosThemeService,
  isModeSupported,
  resolveEffectiveMode,
  supportsModeSwitch,
  THEME_VARIANTS,
  ThemeMode,
} from './theme.service'

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
    // industry-light = no attribute (default)
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
    // industry-light = no attribute
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('falls back to industry/system for an invalid stored value', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'ultraviolet')
    const state = new AgentosThemeService().theme()
    expect(state.variant).toBe('industry')
    expect(state.mode).toBe('system')
  })

  it('falls back to industry for an unknown stored variant id, keeping a valid stored mode', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('industry')
    expect(service.theme().mode).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')
  })

  it('migrates legacy single-key storage to variant+mode keys', () => {
    localStorage.setItem(STORAGE_KEY_LEGACY, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('industry')
    expect(service.theme().mode).toBe('dark')
    // Legacy key removed, new keys created
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

  describe('astra (light only)', () => {
    it('lists astra among the variants derived from the table', () => {
      expect(THEME_VARIANTS).toEqual(['industry', 'terminal', 'astra'])
    })

    it('applies astra-light for astra + light, and never leaves the attribute out', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'astra', mode: 'light' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('astra-light')
    })

    it('forces astra-light when the mode is dark', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'astra', mode: 'dark' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('astra-light')
    })

    it('forces astra-light when the mode is system and the OS prefers dark', () => {
      mockMatchMedia(true)
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'astra', mode: 'system' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('astra-light')
    })

    it('applies astra-light on init from a stored astra variant with a dark mode', () => {
      localStorage.setItem(STORAGE_KEY_VARIANT, 'astra')
      localStorage.setItem(STORAGE_KEY_MODE, 'dark')
      const service = new AgentosThemeService()
      expect(document.documentElement.getAttribute('data-theme')).toBe('astra-light')
      expect(service.theme().mode).toBe('dark')
      expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
    })

    it('keeps the stored mode untouched and gives industry-dark back when leaving astra', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'industry', mode: 'dark' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')

      service.setTheme({ variant: 'astra', mode: 'dark' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('astra-light')
      expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
      expect(service.theme().mode).toBe('dark')

      service.setTheme({ variant: 'industry', mode: service.theme().mode })
      expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')
    })
  })

  describe('resolveEffectiveMode', () => {
    it.each<{ variant: string; mode: ThemeMode; systemPrefersDark: boolean; expected: string }>([
      { variant: 'industry', mode: 'light', systemPrefersDark: true, expected: 'light' },
      { variant: 'industry', mode: 'dark', systemPrefersDark: false, expected: 'dark' },
      { variant: 'industry', mode: 'system', systemPrefersDark: true, expected: 'dark' },
      { variant: 'industry', mode: 'system', systemPrefersDark: false, expected: 'light' },
      { variant: 'terminal', mode: 'dark', systemPrefersDark: false, expected: 'dark' },
      { variant: 'terminal', mode: 'system', systemPrefersDark: true, expected: 'dark' },
      { variant: 'astra', mode: 'light', systemPrefersDark: false, expected: 'light' },
      { variant: 'astra', mode: 'dark', systemPrefersDark: false, expected: 'light' },
      { variant: 'astra', mode: 'system', systemPrefersDark: true, expected: 'light' },
      { variant: 'rainbow', mode: 'dark', systemPrefersDark: false, expected: 'dark' },
    ])('%j', ({ variant, mode, systemPrefersDark, expected }) => {
      expect(resolveEffectiveMode(variant, mode, systemPrefersDark)).toBe(expected)
    })
  })

  describe('isModeSupported / supportsModeSwitch', () => {
    it('accepts every mode for industry and terminal', () => {
      for (const variant of ['industry', 'terminal']) {
        expect(supportsModeSwitch(variant)).toBe(true)
        for (const mode of ['light', 'dark', 'system'] as const) {
          expect(isModeSupported(variant, mode)).toBe(true)
        }
      }
    })

    it('only accepts light for astra, with no light/dark switch', () => {
      expect(supportsModeSwitch('astra')).toBe(false)
      expect(isModeSupported('astra', 'light')).toBe(true)
      expect(isModeSupported('astra', 'dark')).toBe(false)
      expect(isModeSupported('astra', 'system')).toBe(false)
    })
  })

  it('removes its system-preference listener on destroy', () => {
    const service = new AgentosThemeService()
    service.ngOnDestroy()
    expect(removeSpy).toHaveBeenCalledTimes(1)
  })
})
