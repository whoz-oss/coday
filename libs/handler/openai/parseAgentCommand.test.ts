import { parseAgentCommand } from './parseAgentCommand'

describe('parseAgentCommand', () => {
  it('parses simple agent command with command string', () => {
    expect(parseAgentCommand('@AgentName rest of command')).toEqual(['agentname', 'rest of command'])
  })

  it('parses agent command with newline', () => {
    expect(parseAgentCommand('@AgentName\nrest of command')).toEqual(['agentname', 'rest of command'])
  })

  it('parses agent command with a lot of text', () => {
    expect(parseAgentCommand(`@AgentName rest of command`)).toEqual(['agentname', 'rest of command'])
  })

  it('parses agent command with very long text and multiple lines', () => {
    const longText = `Voici un très long texte avec beaucoup de contenu.
Ce texte contient plusieurs lignes et beaucoup de détails.
Il peut y avoir des caractères spéciaux comme @, #, $, %, &, *.
Plusieurs paragraphes avec des espaces multiples    et des tabulations\t.
Du code: function example() { return 'hello world'; }
Des URLs: https://example.com/path?param=value&other=123
Du JSON: {"key": "value", "array": [1, 2, 3]}
Du markdown: **bold** *italic* [link](url)
Et encore plus de texte pour vraiment tester la robustesse du parsing.
Ceci est la fin du très long texte de test.`

    const input = `@AgentName ${longText}`
    const result = parseAgentCommand(input)

    expect(result).toEqual(['agentname', longText])
  })

  it('parses agent command with text containing newlines at start', () => {
    const textWithNewlines = `\nCeci commence par un retour à la ligne\net contient plusieurs lignes\navec du texte complexe`
    expect(parseAgentCommand(`@TestAgent ${textWithNewlines}`)).toEqual(['testagent', textWithNewlines])
  })

  it('parses agent command with text ending with newlines', () => {
    const textEndingWithNewlines = `Du texte normal\nqui se termine par des retours à la ligne\n\n`
    expect(parseAgentCommand(`@Agent ${textEndingWithNewlines}`)).toEqual(['agent', textEndingWithNewlines])
  })

  it('parses agent name only', () => {
    expect(parseAgentCommand('@AgentName')).toEqual(['agentname', ''])
  })

  it('parses only @', () => {
    expect(parseAgentCommand('@')).toEqual(['', ''])
  })

  it('parses @ followed by space and text', () => {
    expect(parseAgentCommand('@ some text')).toEqual(['', 'some text'])
  })

  it('parses command without @', () => {
    expect(parseAgentCommand('no at sign')).toEqual(['', 'no at sign'])
  })

  it('parses @ with multiple spaces', () => {
    expect(parseAgentCommand('@  lots of spaces')).toEqual(['', 'lots of spaces'])
  })

  it('parses @name with multiple spaces before rest', () => {
    expect(parseAgentCommand('@AgentName   lots of spaces')).toEqual(['agentname', 'lots of spaces'])
  })

  it('handles empty string', () => {
    expect(parseAgentCommand('')).toEqual(['', ''])
  })
})
