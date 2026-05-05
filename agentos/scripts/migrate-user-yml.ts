#!/usr/bin/env npx ts-node
/**
 * Migration script: Coday TS ~/.coday/user.yml → AgentOS REST API
 *
 * Usage:
 *   npx ts-node agentos/scripts/migrate-user-yml.ts \
 *     --user-yml ~/.coday/user.yml \
 *     --base-url http://localhost:8080 \
 *     --token <bearer-token> \
 *     [--namespace-mapping projectName=<namespace-uuid>]
 *
 * Mapping:
 *   ai[]                            → POST /api/user-ai-providers  (user-global, no namespaceId)
 *   ai[].models[]                   → POST /api/user-ai-models      (user-global, no namespaceId)
 *   projects.{ns}.integration.{t}  → POST /api/user-integration-configs (user×namespace)
 *
 * Limitations:
 *   - MCP server configurations (projects.{ns}.mcp) are NOT migrated (out of scope MVP).
 *   - On HTTP 409 (already exists), the entry is skipped with a warning.
 */

import * as fs from 'fs'
import * as path from 'path'
import * as https from 'https'
import * as http from 'http'

// ── CLI arg parsing ──────────────────────────────────────────────────────────

interface CliArgs {
  userYml: string
  baseUrl: string
  token: string
  namespaceMappings: Record<string, string>
}

function parseArgs(): CliArgs {
  const args = process.argv.slice(2)
  let userYml = ''
  let baseUrl = ''
  let token = ''
  const namespaceMappings: Record<string, string> = {}

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--user-yml') userYml = args[++i]
    else if (args[i] === '--base-url') baseUrl = args[++i]
    else if (args[i] === '--token') token = args[++i]
    else if (args[i] === '--namespace-mapping') {
      const [name, uuid] = args[++i].split('=')
      namespaceMappings[name] = uuid
    }
  }

  if (!userYml || !baseUrl || !token) {
    console.error('Usage: migrate-user-yml.ts --user-yml <path> --base-url <url> --token <bearer>')
    console.error('  Optional: --namespace-mapping projectName=<uuid>  (repeatable)')
    process.exit(1)
  }
  return { userYml, baseUrl, token, namespaceMappings }
}

// ── HTTP helpers ─────────────────────────────────────────────────────────────

function post(baseUrl: string, token: string, path: string, body: object): Promise<{ status: number; data: any }> {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body)
    const url = new URL(path, baseUrl)
    const transport = url.protocol === 'https:' ? https : http
    const req = transport.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payload),
          Authorization: `Bearer ${token}`,
        },
      },
      (res) => {
        let raw = ''
        res.on('data', (chunk) => (raw += chunk))
        res.on('end', () => {
          try {
            resolve({ status: res.statusCode ?? 0, data: raw ? JSON.parse(raw) : {} })
          } catch {
            resolve({ status: res.statusCode ?? 0, data: raw })
          }
        })
      }
    )
    req.on('error', reject)
    req.write(payload)
    req.end()
  })
}

async function get(baseUrl: string, token: string, path: string): Promise<any> {
  return new Promise((resolve, reject) => {
    const url = new URL(path, baseUrl)
    const transport = url.protocol === 'https:' ? https : http
    transport
      .get(
        {
          hostname: url.hostname,
          port: url.port,
          path: url.pathname + url.search,
          headers: { Authorization: `Bearer ${token}` },
        },
        (res) => {
          let raw = ''
          res.on('data', (c) => (raw += c))
          res.on('end', () => {
            try {
              resolve(JSON.parse(raw))
            } catch {
              resolve(raw)
            }
          })
        }
      )
      .on('error', reject)
  })
}

// ── YAML parser (minimal, no deps) ───────────────────────────────────────────

// Simple YAML → JSON using Node's built-in (requires Node 22+ with --experimental-require-module)
// or fall-through to js-yaml if available.
function loadYaml(filePath: string): any {
  const content = fs.readFileSync(filePath, 'utf8')
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const yaml = require('js-yaml')
    return yaml.load(content)
  } catch {
    throw new Error(
      'js-yaml not available. Install it: npm install js-yaml @types/js-yaml\n' +
        'Or run from the repo root: pnpm add -D js-yaml'
    )
  }
}

// ── AiApiType mapping ─────────────────────────────────────────────────────────

function toApiType(providerName: string): string {
  const n = providerName.toLowerCase()
  if (n.includes('anthropic')) return 'Anthropic'
  if (n.includes('openai') || n.includes('openai')) return 'OpenAI'
  if (n.includes('gemini') || n.includes('google')) return 'Gemini'
  return 'OpenAI'
}

// ── Migration logic ───────────────────────────────────────────────────────────

async function migrateProviders(config: any, baseUrl: string, token: string): Promise<Record<string, string>> {
  const providerIdByName: Record<string, string> = {}
  const providers: any[] = config.ai ?? []

  for (const p of providers) {
    const body = {
      name: p.name,
      apiType: toApiType(p.name),
      apiKey: p.apiKey ?? undefined,
      baseUrl: p.url ?? undefined,
      description: undefined as string | undefined,
    }
    console.log(`[AiProvider] Migrating user-global provider '${p.name}'...`)
    const { status, data } = await post(baseUrl, token, '/api/user-ai-providers', body)
    if (status === 201 || status === 200) {
      providerIdByName[p.name] = data.id
      console.log(`  ✅ Created provider '${p.name}' (id=${data.id})`)
    } else if (status === 409) {
      console.warn(`  ⚠️  Provider '${p.name}' already exists — skipping`)
    } else {
      console.error(`  ❌ Error creating provider '${p.name}': HTTP ${status}`, data)
    }
  }
  return providerIdByName
}

async function migrateModels(
  config: any,
  baseUrl: string,
  token: string,
  providerIdByName: Record<string, string>
): Promise<void> {
  const providers: any[] = config.ai ?? []
  for (const p of providers) {
    const providerId = providerIdByName[p.name]
    if (!providerId) {
      console.warn(`[AiModel] Skipping models for provider '${p.name}' — provider not migrated`)
      continue
    }
    for (const m of p.models ?? []) {
      const body = {
        aiProviderId: providerId,
        apiModelName: m.name,
        alias: m.alias ?? undefined,
        temperature: m.temperature ?? undefined,
        maxTokens: m.contextWindow ?? undefined,
      }
      console.log(`[AiModel] Migrating user-global model '${m.alias ?? m.name}' under provider '${p.name}'...`)
      const { status, data } = await post(baseUrl, token, '/api/user-ai-models', body)
      if (status === 201 || status === 200) {
        console.log(`  ✅ Created model '${m.alias ?? m.name}' (id=${data.id})`)
      } else if (status === 409) {
        console.warn(`  ⚠️  Model '${m.alias ?? m.name}' already exists — skipping`)
      } else {
        console.error(`  ❌ Error creating model '${m.alias ?? m.name}': HTTP ${status}`, data)
      }
    }
  }
}

async function resolveNamespaceId(
  projectName: string,
  baseUrl: string,
  token: string,
  namespaceMappings: Record<string, string>
): Promise<string | null> {
  if (namespaceMappings[projectName]) return namespaceMappings[projectName]
  try {
    const data = await get(baseUrl, token, `/api/namespaces?name=${encodeURIComponent(projectName)}`)
    const list: any[] = Array.isArray(data) ? data : (data?.content ?? [])
    const match = list.find((n: any) => n.name === projectName)
    return match?.id ?? null
  } catch {
    return null
  }
}

async function migrateIntegrations(
  config: any,
  baseUrl: string,
  token: string,
  namespaceMappings: Record<string, string>
): Promise<void> {
  const projects: Record<string, any> = config.projects ?? {}
  for (const [projectName, projectCfg] of Object.entries(projects)) {
    const namespaceId = await resolveNamespaceId(projectName, baseUrl, token, namespaceMappings)
    if (!namespaceId) {
      console.warn(
        `[IntegrationConfig] Cannot resolve namespace for project '${projectName}'. ` +
          `Use --namespace-mapping ${projectName}=<uuid> to map it manually. Skipping.`
      )
      continue
    }
    const integration: Record<string, any> = (projectCfg as any).integration ?? {}
    for (const [intType, intParams] of Object.entries(integration)) {
      const body = {
        namespaceId,
        name: intType,
        integrationType: intType.toUpperCase(),
        parameters: intParams,
      }
      console.log(`[IntegrationConfig] Migrating '${intType}' for project '${projectName}' (ns=${namespaceId})...`)
      const { status, data } = await post(baseUrl, token, '/api/user-integration-configs', body)
      if (status === 201 || status === 200) {
        console.log(`  ✅ Created integration '${intType}' (id=${data.id})`)
      } else if (status === 409) {
        console.warn(`  ⚠️  Integration '${intType}' for project '${projectName}' already exists — skipping`)
      } else {
        console.error(`  ❌ Error creating integration '${intType}': HTTP ${status}`, data)
      }
    }

    // MCP configs are NOT migrated — explicitly documented as out-of-scope MVP
    if ((projectCfg as any).mcp) {
      console.warn(`  ℹ️  MCP server configurations for project '${projectName}' are NOT migrated (out of scope MVP).`)
    }
  }
}

// ── Main ─────────────────────────────────────────────────────────────────────

async function main(): Promise<void> {
  const { userYml, baseUrl, token, namespaceMappings } = parseArgs()
  console.log(`\nMigrating ${userYml} → ${baseUrl}\n`)

  const config = loadYaml(userYml)

  const providerIdByName = await migrateProviders(config, baseUrl, token)
  await migrateModels(config, baseUrl, token, providerIdByName)
  await migrateIntegrations(config, baseUrl, token, namespaceMappings)

  console.log('\n✅ Migration complete.')
}

main().catch((err) => {
  console.error('Migration failed:', err)
  process.exit(1)
})
