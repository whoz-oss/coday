#!/usr/bin/env ts-node
/**
 * forge-gate2-record.ts
 *
 * Script invoqué par l'agent ProductEngineer via PROJECT_SCRIPTS.
 * Enregistre le passage de Gate 2 (spec Epic validée) dans le ledger Forge.
 *
 * Usage :
 *   npx ts-node coday/scripts/forge-gate2-record.ts \
 *     --run-id epic_xxx \
 *     --spec-path /abs/path/to/sprint/forge/specs/WZ-XXX.md
 *
 * Sortie stdout (JSON) :
 *   { "ok": true,  "status": "recorded", "specHash": "sha256:..." }
 *   { "ok": true,  "status": "idempotent" }
 *   { "ok": false, "error": "..." }
 *
 * Codes de sortie :
 *   0 — G2 enregistré (recorded ou idempotent)
 *   1 — erreur (préconditions non remplies, spec invalide, serveur absent)
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

function httpPost(
  url: string,
  body: unknown,
  headers: Record<string, string> = {}
): Promise<{ status: number; data: unknown }> {
  return new Promise((resolve, reject) => {
    const payload = JSON.stringify(body)
    const parsed = new URL(url)
    const options: http.RequestOptions = {
      hostname: parsed.hostname,
      port: parsed.port ? parseInt(parsed.port) : 3141,
      path: parsed.pathname + parsed.search,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
        ...headers,
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

  const runId = args.get('--run-id')
  const specPath = args.get('--spec-path')
  const namespaceId = args.get('--namespace-id') ?? process.env.FACTORY_NAMESPACE_ID

  if (!runId) {
    emit({
      ok: false,
      error: '--run-id manquant. Usage: forge-gate2-record.ts --run-id epic_xxx --spec-path /abs/path/to/spec.md',
    })
    process.exit(1)
  }
  if (!specPath) {
    emit({
      ok: false,
      error: '--spec-path manquant. Usage: forge-gate2-record.ts --run-id epic_xxx --spec-path /abs/path/to/spec.md',
    })
    process.exit(1)
  }
  if (!namespaceId) {
    emit({ ok: false, error: '--namespace-id manquant (ou variable FACTORY_NAMESPACE_ID non définie).' })
    process.exit(1)
  }

  try {
    const url = `${FACTORY_URL}/api/factory/forge/runs/${encodeURIComponent(runId)}/gates/G2?namespaceId=${encodeURIComponent(namespaceId)}`
    const { status, data } = await httpPost(url, { specPath })

    if (status === 200 || status === 201) {
      const result = data as { status: string; event?: { spec?: { sha256: string } } }
      const specHash = result.event?.spec?.sha256 ?? null
      emit({ ok: true, status: result.status, specHash })
      process.exit(0)
    } else {
      const error = data as { error?: string }
      emit({
        ok: false,
        error: error?.error ?? `Erreur HTTP ${status} du serveur Factory.`,
        hint: `Vérifiez que le serveur Factory tourne sur ${FACTORY_URL} et que G1 est approuvé.`,
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
