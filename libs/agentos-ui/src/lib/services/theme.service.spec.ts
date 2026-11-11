import {
  AgentosThemeService,
  DEFAULT_THEME_VARIANT,
  getThemeVariantDefinition,
  isModeSupported,
  resolveEffectiveMode,
  resolveSupportedMode,
  ResolvedThemeMode,
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

  it('defaults to whoz-oss/system when nothing is stored, and still sets a data-theme attribute', () => {
    const service = new AgentosThemeService()
    const state = service.theme()
    expect(state.variant).toBe('whoz-oss')
    expect(state.mode).toBe('system')
    expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-light')
  })

  it('defaults to whoz-oss-dark when nothing is stored and the OS prefers dark', () => {
    mockMatchMedia(true)
    new AgentosThemeService()
    expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
  })

  it('keeps a stored industry variant: users who chose Industry are not switched', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'industry')
    localStorage.setItem(STORAGE_KEY_MODE, 'light')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('industry')
    // industry-light = no attribute
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('exposes whoz-oss as the default variant, and falls back to it for an unknown id', () => {
    expect(DEFAULT_THEME_VARIANT).toBe('whoz-oss')
    expect(getThemeVariantDefinition('rainbow').id).toBe('whoz-oss')
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

  it('falls back to whoz-oss/system for an invalid stored value', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'ultraviolet')
    const state = new AgentosThemeService().theme()
    expect(state.variant).toBe('whoz-oss')
    expect(state.mode).toBe('system')
  })

  it('falls back to whoz-oss for an unknown stored variant id, keeping a valid stored mode', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('whoz-oss')
    expect(service.theme().mode).toBe('dark')
    expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
  })

  it('migrates legacy single-key storage to variant+mode keys, on the default variant', () => {
    localStorage.setItem(STORAGE_KEY_LEGACY, 'dark')
    const service = new AgentosThemeService()
    expect(service.theme().variant).toBe('whoz-oss')
    expect(service.theme().mode).toBe('dark')
    // Legacy key removed, new keys created
    expect(localStorage.getItem(STORAGE_KEY_LEGACY)).toBeNull()
    expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
    expect(localStorage.getItem(STORAGE_KEY_VARIANT)).toBe('whoz-oss')
    expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
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

  describe('whoz-oss (light and dark)', () => {
    it('lists whoz-oss among the variants derived from the table', () => {
      expect(THEME_VARIANTS).toEqual(['whoz-oss', 'industry', 'terminal'])
    })

    it('applies whoz-oss-light for whoz-oss + light, and never leaves the attribute out', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'whoz-oss', mode: 'light' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-light')
    })

    it('applies whoz-oss-dark for whoz-oss + dark', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'whoz-oss', mode: 'dark' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
    })

    it('follows the OS for whoz-oss + system: whoz-oss-dark when the OS prefers dark', () => {
      mockMatchMedia(true)
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'whoz-oss', mode: 'system' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
    })

    it('follows the OS for whoz-oss + system: whoz-oss-light when the OS prefers light', () => {
      mockMatchMedia(false)
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'whoz-oss', mode: 'system' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-light')
    })

    it('applies whoz-oss-dark on init from a stored whoz-oss variant with a dark mode', () => {
      localStorage.setItem(STORAGE_KEY_VARIANT, 'whoz-oss')
      localStorage.setItem(STORAGE_KEY_MODE, 'dark')
      const service = new AgentosThemeService()
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
      expect(service.theme().mode).toBe('dark')
    })

    it('keeps the stored mode untouched across variant changes', () => {
      const service = new AgentosThemeService()
      service.setTheme({ variant: 'industry', mode: 'dark' })
      expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')

      service.setTheme({ variant: 'whoz-oss', mode: service.theme().mode })
      expect(document.documentElement.getAttribute('data-theme')).toBe('whoz-oss-dark')
      expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')

      service.setTheme({ variant: 'industry', mode: service.theme().mode })
      expect(document.documentElement.getAttribute('data-theme')).toBe('industry-dark')
      expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
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
      { variant: 'whoz-oss', mode: 'light', systemPrefersDark: true, expected: 'light' },
      { variant: 'whoz-oss', mode: 'dark', systemPrefersDark: false, expected: 'dark' },
      { variant: 'whoz-oss', mode: 'system', systemPrefersDark: true, expected: 'dark' },
      { variant: 'whoz-oss', mode: 'system', systemPrefersDark: false, expected: 'light' },
      { variant: 'rainbow', mode: 'dark', systemPrefersDark: false, expected: 'dark' },
    ])('%j', ({ variant, mode, systemPrefersDark, expected }) => {
      expect(resolveEffectiveMode(variant, mode, systemPrefersDark)).toBe(expected)
    })
  })

  // The table has no light-only variant any more, so the guard is proven on explicit lists of supported modes.
  describe('resolveSupportedMode (fallback guard for a variant that does not support every mode)', () => {
    it.each<{ modes: ResolvedThemeMode[]; mode: ThemeMode; systemPrefersDark: boolean; expected: string }>([
      { modes: ['light'], mode: 'light', systemPrefersDark: false, expected: 'light' },
      { modes: ['light'], mode: 'dark', systemPrefersDark: false, expected: 'light' },
      { modes: ['light'], mode: 'system', systemPrefersDark: true, expected: 'light' },
      { modes: ['dark'], mode: 'light', systemPrefersDark: false, expected: 'dark' },
      { modes: ['dark'], mode: 'system', systemPrefersDark: false, expected: 'dark' },
      { modes: ['light', 'dark'], mode: 'dark', systemPrefersDark: false, expected: 'dark' },
      { modes: [], mode: 'dark', systemPrefersDark: true, expected: 'light' },
    ])('%j', ({ modes, mode, systemPrefersDark, expected }) => {
      expect(resolveSupportedMode(modes, mode, systemPrefersDark)).toBe(expected)
    })
  })

  describe('isModeSupported / supportsModeSwitch', () => {
    it('accepts every mode for industry, terminal and whoz-oss', () => {
      for (const variant of ['industry', 'terminal', 'whoz-oss']) {
        expect(supportsModeSwitch(variant)).toBe(true)
        for (const mode of ['light', 'dark', 'system'] as const) {
          expect(isModeSupported(variant, mode)).toBe(true)
        }
      }
    })
  })

  it('removes its system-preference listener on destroy', () => {
    const service = new AgentosThemeService()
    service.ngOnDestroy()
    expect(removeSpy).toHaveBeenCalledTimes(1)
  })
})
