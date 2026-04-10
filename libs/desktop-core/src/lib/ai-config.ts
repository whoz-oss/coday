import { log } from './logger'

const fs = require('fs') as typeof import('fs')
const { join } = require('path') as typeof import('path')
const os = require('os') as typeof import('os')

/**
 * Check whether an AI provider API key is configured.
 * Checks the environment variable first, then ~/.coday/users/<username>/user.yaml.
 */
export function checkApiKey(
  providerName: 'anthropic' | 'openai',
  envVar: string
): { configured: boolean; source: string | null } {
  if (process.env[envVar]) return { configured: true, source: 'env' }

  const username = os.userInfo().username.replace(/[^a-zA-Z0-9]/g, '_')
  const userConfigPath = join(os.homedir(), '.coday', 'users', username, 'user.yaml')

  try {
    if (fs.existsSync(userConfigPath)) {
      const lines = fs.readFileSync(userConfigPath, 'utf8').split('\n')
      let inAi = false
      let inEntry = false
      for (const line of lines) {
        if (/^ai:\s*\[\s*\]/.test(line)) {
          inAi = false
          continue
        }
        if (/^ai:\s*$/.test(line) || /^ai:\s*#/.test(line)) {
          inAi = true
          continue
        }
        if (inAi && /^\S/.test(line) && !line.startsWith('#')) {
          inAi = false
          inEntry = false
        }
        if (inAi) {
          if (new RegExp(`^\\s+-\\s+name:\\s*${providerName}\\s*$`).test(line)) {
            inEntry = true
            continue
          }
          if (/^\s+-\s/.test(line) && inEntry) inEntry = false
          if (inEntry && /^\s+apiKey:\s*\S+/.test(line)) return { configured: true, source: 'user.yaml' }
        }
      }
    }
  } catch (error) {
    log('ERROR', `Failed to check user config for ${providerName} API key:`, error)
  }

  return { configured: false, source: null }
}

/**
 * Save an AI provider API key to ~/.coday/users/<username>/user.yaml.
 * Handles creating the file, adding the ai: section, and updating an existing entry.
 */
export function saveApiKeyToUserYaml(providerName: 'anthropic' | 'openai', apiKey: string): void {
  const username = os.userInfo().username.replace(/[^a-zA-Z0-9]/g, '_')
  const userDir = join(os.homedir(), '.coday', 'users', username)
  const userConfigPath = join(userDir, 'user.yaml')

  try {
    fs.mkdirSync(userDir, { recursive: true })

    if (fs.existsSync(userConfigPath)) {
      const lines = fs.readFileSync(userConfigPath, 'utf8').split('\n')
      const out: string[] = []
      let inAi = false,
        inEntry = false,
        handled = false,
        aiFound = false,
        written = false

      for (let i = 0; i < lines.length; i++) {
        const l = lines[i]!

        // Handle "ai: []" — replace with block form including our entry
        if (/^ai:\s*\[\s*\]/.test(l)) {
          out.push('ai:', `  - name: ${providerName}`, `    apiKey: ${apiKey}`)
          aiFound = true
          handled = true
          written = true
          continue
        }

        // Detect start of ai array (block form)
        if (/^ai:\s*$/.test(l) || /^ai:\s*#/.test(l)) {
          inAi = true
          aiFound = true
          out.push(l)
          continue
        }

        // If we hit another top-level key while in ai section, check if we need to add provider
        if (inAi && /^\S/.test(l) && !l.startsWith('#')) {
          if (!handled) {
            out.push(`  - name: ${providerName}`, `    apiKey: ${apiKey}`)
            handled = true
            written = true
          }
          inAi = false
          inEntry = false
        }

        if (inAi) {
          if (new RegExp(`^\\s+-\\s+name:\\s*${providerName}\\s*$`).test(l)) {
            inEntry = true
            handled = true
            out.push(l)
            continue
          }
          if (/^\s+-\s/.test(l) && inEntry) {
            if (!written) {
              out.push(`    apiKey: ${apiKey}`)
              written = true
            }
            inEntry = false
          }
          if (inEntry && /^\s+apiKey:/.test(l)) {
            out.push(`    apiKey: ${apiKey}`)
            written = true
            continue
          }
        }

        out.push(l)
      }

      // Edge case: entry was last in file without apiKey
      if (inEntry && !written) {
        out.push(`    apiKey: ${apiKey}`)
        handled = true
      }
      // Edge case: ai array was last section and no entry was found
      if (inAi && !handled) {
        out.push(`  - name: ${providerName}`, `    apiKey: ${apiKey}`)
      }
      // If no ai section existed at all, append one
      if (!aiFound) {
        if (out[out.length - 1] !== '') out.push('')
        out.push('ai:', `  - name: ${providerName}`, `    apiKey: ${apiKey}`)
      }

      let result = out.join('\n')
      if (!result.endsWith('\n')) result += '\n'
      fs.writeFileSync(userConfigPath, result, 'utf8')
      log('INFO', `Saved ${providerName} API key to user config`)
    } else {
      // Create new user.yaml
      const newConfig = `version: 2\nai:\n  - name: ${providerName}\n    apiKey: ${apiKey}\n`
      fs.writeFileSync(userConfigPath, newConfig, 'utf8')
      log('INFO', `Created new user config with ${providerName} API key`)
    }
  } catch (error) {
    log('ERROR', `Failed to save ${providerName} API key:`, error)
    throw error
  }
}
