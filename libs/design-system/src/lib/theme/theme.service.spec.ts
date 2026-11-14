import {
  DS_THEME_MODES,
  DS_THEME_VARIANTS,
  DsThemeService,
  isThemeMode,
  isThemeVariant,
  nextThemeMode,
  themeAttribute,
} from './theme.service'

describe('DsThemeService', () => {
  const STORAGE_KEY_VARIANT = 'agentos.theme.variant'
  const STORAGE_KEY_MODE = 'agentos.theme.mode'

  let systemListeners: Array<() => void>
  let removeSpy: jest.Mock

  const mockMatchMedia = (initialMatches: boolean): { setMatches: (value: boolean) => void } => {
    systemListeners = []
    removeSpy = jest.fn()
    const state = { matches: initialMatches }
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: jest.fn().mockImplementation((query: string) => ({
        get matches() {
          return state.matches
        },
        media: query,
        addEventListener: jest.fn((_type: string, listener: () => void) => systemListeners.push(listener)),
        removeEventListener: removeSpy,
      })),
    })
    return { setMatches: (value: boolean) => (state.matches = value) }
  }

  const dataTheme = (): string | null => document.documentElement.getAttribute('data-theme')

  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
    mockMatchMedia(false)
  })

  it('defaults to industry / system and leaves data-theme off (industry light)', () => {
    const service = new DsThemeService()
    expect(service.theme()).toEqual({ variant: 'industry', mode: 'system' })
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false)
  })

  it('cycles light → dark → system → light', () => {
    const service = new DsThemeService()
    service.setMode('light')

    const seen: string[] = []
    for (let i = 0; i < 4; i++) {
      service.cycleMode()
      seen.push(service.theme().mode)
    }

    expect(seen).toEqual(['dark', 'system', 'light', 'dark'])
    expect(nextThemeMode('light')).toBe('dark')
    expect(nextThemeMode('dark')).toBe('system')
    expect(nextThemeMode('system')).toBe('light')
  })

  it('sets no attribute for industry light and `<variant>-<mode>` otherwise', () => {
    const service = new DsThemeService()

    service.setMode('light')
    expect(dataTheme()).toBeNull()

    service.setMode('dark')
    expect(dataTheme()).toBe('industry-dark')

    service.setVariant('terminal')
    expect(dataTheme()).toBe('terminal-dark')

    service.setMode('light')
    expect(dataTheme()).toBe('terminal-light')

    service.setVariant('whoz-oss')
    expect(dataTheme()).toBe('whoz-oss-light')

    service.setMode('dark')
    expect(dataTheme()).toBe('whoz-oss-dark')

    service.setVariant('industry')
    service.setMode('light')
    expect(dataTheme()).toBeNull()
  })

  it('follows the OS in system mode, including when it changes', () => {
    const media = mockMatchMedia(true)
    const service = new DsThemeService()
    service.setMode('system')
    expect(dataTheme()).toBe('industry-dark')

    media.setMatches(false)
    systemListeners.forEach((listener) => listener())
    expect(dataTheme()).toBeNull()

    service.setVariant('terminal')
    expect(dataTheme()).toBe('terminal-light')

    media.setMatches(true)
    systemListeners.forEach((listener) => listener())
    expect(dataTheme()).toBe('terminal-dark')
  })

  it('ignores OS changes when the mode is not system', () => {
    const media = mockMatchMedia(false)
    const service = new DsThemeService()
    service.setMode('light')

    media.setMatches(true)
    systemListeners.forEach((listener) => listener())

    expect(dataTheme()).toBeNull()
  })

  it('persists the choice under the keys shared with agentos-ui', () => {
    const service = new DsThemeService()
    service.setVariant('terminal')
    service.setMode('dark')

    expect(localStorage.getItem(STORAGE_KEY_VARIANT)).toBe('terminal')
    expect(localStorage.getItem(STORAGE_KEY_MODE)).toBe('dark')
  })

  it('reads and applies the stored choice on init', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'whoz-oss')
    localStorage.setItem(STORAGE_KEY_MODE, 'dark')

    const service = new DsThemeService()

    expect(service.theme()).toEqual({ variant: 'whoz-oss', mode: 'dark' })
    expect(dataTheme()).toBe('whoz-oss-dark')
  })

  it('falls back to industry / system for unknown stored values', () => {
    localStorage.setItem(STORAGE_KEY_VARIANT, 'rainbow')
    localStorage.setItem(STORAGE_KEY_MODE, 'ultraviolet')

    expect(new DsThemeService().theme()).toEqual({ variant: 'industry', mode: 'system' })
  })

  it('removes its system-preference listener on destroy', () => {
    const service = new DsThemeService()
    service.ngOnDestroy()
    expect(removeSpy).toHaveBeenCalledTimes(1)
  })

  describe('helpers', () => {
    it('lists the three skins and the three modes', () => {
      expect(DS_THEME_VARIANTS.map((v) => v.id)).toEqual(['industry', 'terminal', 'whoz-oss'])
      expect([...DS_THEME_MODES]).toEqual(['light', 'dark', 'system'])
    })

    it('guards variants and modes', () => {
      expect(isThemeVariant('terminal')).toBe(true)
      expect(isThemeVariant('rainbow')).toBe(false)
      expect(isThemeMode('system')).toBe(true)
      expect(isThemeMode('ultraviolet')).toBe(false)
    })

    it('computes the data-theme value', () => {
      expect(themeAttribute('industry', 'light')).toBeNull()
      expect(themeAttribute('industry', 'dark')).toBe('industry-dark')
      expect(themeAttribute('terminal', 'light')).toBe('terminal-light')
      expect(themeAttribute('whoz-oss', 'dark')).toBe('whoz-oss-dark')
    })
  })
})
