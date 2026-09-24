#!/usr/bin/env ts-node
/**
 * forge-factory-launch.ts
 *
 * Script invoké par l'agent ProductEngineer via PROJECT_SCRIPTS.
 * Appelle le serveur Factory (Coday, port 3141) pour créer un EpicRun
 * dans le ledger Forge du repo cible.
 *
 * Usage (depuis la racine du repo Sprint) :
 *   npx ts-node coday/scripts/forge-factory-launch.ts --request '<json>'
 *
 * Le JSON attendu :
 *   {
 *     "roots": {
 *       "repoRoot": "/abs/path/to/sprint"
 *     },
 *     "epic":    { "id": "WZ-XXX", "kind": "Epic" },
 *     "stories": [ { "id": "WZ-YYY", "kind": "Story" } ]
 *   }
 *
 * Sortie stdout (JSON) :
 *   { "ok": true,  "runId": "epic_...", "filePath": "..." }
 *   { "ok": false, "error": "..." }
 *
 * Codes de sortie :
 *   0 — EpicRun créé avec succès
 *   1 — erreur (détails dans stdout JSON + stderr)
 */

import * as http from 'http'

const FACTORY_URL = process.env.FACTORY_URL ?? 'http://localhost:3141'

function emit(obj: Record<string, unknown>): void {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

function parseArgs(argv: string[]): Map<string, string> {
  const args = argv.slice(2)
  const values = new Map<string, string>()
  for (let i = 0; i < args.length; i += 2) {
    const flag = args[i]
    const value = args[i + 1]
    if (!flag || !value || !flag.startsWith('-')) {
      throw new Error(`Arguments invalides à la position ${i}: ${flag}`)
    }
    values.set(flag, value)
  }
  return values
}

function httpPost(url: string, body: unknown): Promise<{ status: number; data: unknown }> {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body)
    const parsed = new URL(url)
    const options: http.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port ? parseInt(parsed.port) : 3141,
      path: parsed.pathname,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    }
    const req = http.request(options, (res) => {
      let raw = ''
      res.on('data', (chunk: Buffer) => {
        raw += chunk.toString()
      })
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode ?? 0, data: JSON.parse(raw) })
        } catch {
          resolve({ status: res.statusCode ?? 0, data: raw })
        }
      })
    })
    req.on('error', reject)
    req.write(payload)
    req.end()
  })
}

async function main(): Promise<void> {
  let args: Map<string, string>
  try {
    args = parseArgs(process.argv)
  } catch (err) {
    emit({ ok: false, error: `Erreur d'arguments : ${(err as Error).message}` })
    process.exit(1)
  }

  const rawRequest = args.get('--request')
  if (!rawRequest) {
    emit({ ok: false, error: "Argument --request manquant. Usage : forge-factory-launch.ts --request '<json>'" })
    process.exit(1)
  }

  let request: Record<string, unknown>
  try {
    request = JSON.parse(rawRequest)
  } catch {
    emit({ ok: false, error: 'Le JSON passé à --request est invalide.' })
    process.exit(1)
  }

  // Validation minimale coté client
  const roots = request['roots'] as Record<string, unknown> | undefined
  if (!roots?.repoRoot) {
    emit({ ok: false, error: 'request.roots.repoRoot est requis.' })
    process.exit(1)
  }
  const epic = request['epic'] as Record<string, unknown> | undefined
  if (!epic?.id || !epic?.kind) {
    emit({ ok: false, error: 'request.epic doit contenir id et kind.' })
    process.exit(1)
  }
  const stories = request['stories']
  if (!Array.isArray(stories) || stories.length === 0) {
    emit({ ok: false, error: 'request.stories doit être un tableau non vide.' })
    process.exit(1)
  }

  try {
    const { status, data } = await httpPost(`${FACTORY_URL}/api/factory/forge/runs/create`, request)

    if (status === 201) {
      const result = data as { runId: string; filePath: string }
      emit({ ok: true, runId: result.runId, filePath: result.filePath })
      process.exit(0)
    } else {
      const error = data as { error?: string }
      emit({
        ok: false,
        error: error?.error ?? `Erreur HTTP ${status} du serveur Factory.`,
        hint: `Vérifiez que le serveur Factory tourne sur ${FACTORY_URL}`,
      })
      process.exit(1)
    }
  } catch (err) {
    emit({
      ok: false,
      error: `Impossible de joindre le serveur Factory sur ${FACTORY_URL} : ${(err as Error).message}`,
      hint: 'Vérifiez que node factory/dashboard/server.mjs tourne sur le repo Coday.',
    })
    process.exit(1)
  }
}

main()
