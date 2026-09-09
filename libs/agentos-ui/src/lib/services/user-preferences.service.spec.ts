import { AgentosUserPreferencesService } from './user-preferences.service'

describe('AgentosUserPreferencesService', () => {
  const STORAGE_KEY = 'agentos.enter-key-behavior'

  const keydown = (init: KeyboardEventInit = {}): KeyboardEvent =>
    new KeyboardEvent('keydown', { key: 'Enter', ...init })

  beforeEach(() => {
    localStorage.clear()
  })

  it('defaults to "send" when nothing is stored', () => {
    expect(new AgentosUserPreferencesService().enterKeyBehavior()).toBe('send')
  })

  it('reads the persisted behavior on init', () => {
    localStorage.setItem(STORAGE_KEY, 'newline')
    expect(new AgentosUserPreferencesService().enterKeyBehavior()).toBe('newline')
  })

  it('persists and reflects the selected behavior on the signal', () => {
    const service = new AgentosUserPreferencesService()

    service.setEnterKeyBehavior('newline')
    expect(service.enterKeyBehavior()).toBe('newline')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('newline')

    service.setEnterKeyBehavior('send')
    expect(service.enterKeyBehavior()).toBe('send')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('send')
  })

  it('falls back to "send" for an invalid stored value', () => {
    localStorage.setItem(STORAGE_KEY, 'nope')
    expect(new AgentosUserPreferencesService().enterKeyBehavior()).toBe('send')
  })

  it('exposes a composer hint that matches the active behavior', () => {
    const service = new AgentosUserPreferencesService()
    expect(service.composerHint()).toContain('Enter to send')
    service.setEnterKeyBehavior('newline')
    expect(service.composerHint()).toContain('Ctrl/Cmd+Enter to send')
  })

  describe('shouldSend — only a real ENTER press', () => {
    it('never sends for a non-Enter key', () => {
      expect(new AgentosUserPreferencesService().shouldSend(keydown({ key: 'a' }))).toBe(false)
    })

    it('never sends while an IME composition is active', () => {
      const composing = keydown()
      // KeyboardEvent.isComposing is read-only and not settable via the init dict; emulate it.
      Object.defineProperty(composing, 'isComposing', { value: true })
      expect(new AgentosUserPreferencesService().shouldSend(composing)).toBe(false)
    })
  })

  describe('shouldSend — "send" mode (default)', () => {
    it('sends on plain Enter', () => {
      expect(new AgentosUserPreferencesService().shouldSend(keydown())).toBe(true)
    })

    it('does not send on Shift+Enter (inserts a newline)', () => {
      expect(new AgentosUserPreferencesService().shouldSend(keydown({ shiftKey: true }))).toBe(false)
    })

    it('also sends on Ctrl+Enter and Cmd+Enter (a modifier does not block send)', () => {
      const service = new AgentosUserPreferencesService()
      expect(service.shouldSend(keydown({ ctrlKey: true }))).toBe(true)
      expect(service.shouldSend(keydown({ metaKey: true }))).toBe(true)
    })
  })

  describe('shouldSend — "newline" mode', () => {
    const newlineService = (): AgentosUserPreferencesService => {
      const service = new AgentosUserPreferencesService()
      service.setEnterKeyBehavior('newline')
      return service
    }

    it('does not send on plain Enter (inserts a newline)', () => {
      expect(newlineService().shouldSend(keydown())).toBe(false)
    })

    it('sends on Ctrl+Enter and Cmd+Enter', () => {
      const service = newlineService()
      expect(service.shouldSend(keydown({ ctrlKey: true }))).toBe(true)
      expect(service.shouldSend(keydown({ metaKey: true }))).toBe(true)
    })

    it('does not send on Shift+Enter', () => {
      expect(newlineService().shouldSend(keydown({ shiftKey: true }))).toBe(false)
    })
  })
})
