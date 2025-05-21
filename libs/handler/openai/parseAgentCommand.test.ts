import { parseAgentCommand } from './parseAgentCommand'

describe('parseAgentCommand', () => {
  it('parses simple agent command with command string', () => {
    expect(parseAgentCommand('@AgentName rest of command')).toEqual(['AgentName', 'rest of command'])
  })

  it('parses agent command with newline', () => {
    expect(parseAgentCommand('@AgentName\nrest of command')).toEqual(['AgentName', 'rest of command'])
  })

  it('parses agent name only', () => {
    expect(parseAgentCommand('@AgentName')).toEqual(['AgentName', ''])
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
    expect(parseAgentCommand('@AgentName   lots of spaces')).toEqual(['AgentName', 'lots of spaces'])
  })

  it('handles empty string', () => {
    expect(parseAgentCommand('')).toEqual(['', ''])
  })
})
