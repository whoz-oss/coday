/**
 * Parses a command string starting with '@' to extract the agent name and the rest of the command.
 * Returns a tuple [agentName, restOfCommand].
 *
 * Examples:
 *   "@AgentName rest of command" => ["AgentName", "rest of command"]
 *   "@AgentName\nrest of command" => ["AgentName", "rest of command"]
 *   "@AgentName" => ["AgentName", ""]
 *   "@" => ["", ""]
 *   "@ some text" => ["", "some text"]
 */
export function parseAgentCommand(command: string): [string, string] {
  // Format: @ followed by zero or more non-whitespace characters, optionally followed by whitespace and the rest
  const match = command.match(/^@(\S*)(?:\s+([\s\S]*))?$/)
  if (!match) return ['', command]
  const agentName = match[1]
  const restOfCommand = match[2] || ''
  return [agentName?.toLowerCase(), restOfCommand]
}
