import { stripSettingsCommand } from './settings-command'

describe('stripSettingsCommand', () => {
  it('leaves a text that is not a command untouched, the same string', () => {
    for (const value of [
      '',
      'hello world',
      '  spaced  ',
      'which /model should I use?',
      '/models',
      '/effortless',
      '/m',
    ]) {
      expect(stripSettingsCommand(value)).toBe(value)
    }
  })

  it('removes a command that is the whole composer value', () => {
    for (const value of ['/model', '/model big', '/MODEL Big', '/mo', '/effort', '/effort high', '/ef', '/effort h']) {
      expect(stripSettingsCommand(value)).toBe('')
    }
  })

  it('removes only the command when text follows it', () => {
    expect(stripSettingsCommand('/model big\nhello world')).toBe('hello world')
    expect(stripSettingsCommand('/effort high then more')).toBe('then more')
  })
})
