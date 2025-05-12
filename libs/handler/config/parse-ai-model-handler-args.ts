/**
 * Parses AI model handler trailing arguments (after commandWord).
 * Accepts at most two positional args (provider, model) and any flag order.
 * Throws error on more than 2 non-flag args, returns normalized info.
 */
export function parseAiModelHandlerArgs(args: string): {
  isProject: boolean
  aiProviderNameStart?: string
  aiModelName?: string
} {
  // Split args into tokens (preserve original casing for later use)
  const tokens = args.trim().split(/\s+/).filter(Boolean)
  const projectFlags = tokens.filter(t => t === '--project' || t === '-p')
  const nonFlags = tokens.filter(t => t !== '--project' && t !== '-p')

  // Usage: [providerName] [modelName] [--project]
  if (nonFlags.length > 2) {
    throw new Error('Too many arguments. Usage: [providerName] [modelName] [--project]')
  }

  const isProject = projectFlags.length > 0
  const aiProviderNameStart = nonFlags[0]
  const aiModelName = nonFlags[1]

  return {
    isProject,
    aiProviderNameStart,
    aiModelName
  }
}
