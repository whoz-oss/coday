/**
 * Dashboard server — Software Factory.
 *
 * Usage : node factory/dashboard/server.mjs
 * Port  : 3141 (configurable via PORT env)
 *
 * Endpoints :
 *   GET  /                          → index.html
 *   GET  /api/config                → { agentosUrl } pour les liens profonds
 *   GET  /api/runs                  → liste des runs
 *   GET  /api/runs/:id              → détail d'un run
 *   POST /api/runs                  → lancer un run (répond avec { runId })
 *   GET  /api/runs/:id/stream       → SSE stdout/stderr en temps réel
 *   GET  /api/agents?namespaceId=   → proxy AgentOS (liste agents)
 *   GET  /api/cases/:caseId/events  → proxy AgentOS (événements d'un case)
 *   GET  /api/jira/:ticketId        → contenu d'un ticket Jira (récupéré maintenant)
 *
 * POURQUOI UN PROXY D'ÉVÉNEMENTS (décision O-B, 2026-08-22)
 * ---------------------------------------------------------
 * Le registre ne contient aucun texte produit par un LLM — c'est l'invariant 2.
 * Le récit (brief envoyé, réponse de l'agent, appels d'outils) vit dans AgentOS,
 * pas ici. Plutôt que de dupliquer ce texte dans les JSONL, le dashboard va le
 * chercher à la source au moment de l'affichage, via le `caseId` que le registre
 * enregistre comme un fait.
 *
 * La séparation est ainsi préservée : le registre reste un instrument de faits,
 * AgentOS reste la source du récit, et l'écran les met côte à côte sans les
 * mélanger.
 */

import { createServer } from 'node:http'
import { readFileSync, readdirSync, existsSync, watchFile, unwatchFile } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { homedir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'
import { fetchJiraTicket } from '../lib/jira.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
const RUNS_DIR = join(__dirname, '..', 'runs')
const RUN_ENTRY = join(__dirname, '..', 'run.mjs')
const PORT = parseInt(process.env.PORT ?? '3141', 10)
const AGENTOS_URL = process.env.AGENTOS_URL ?? 'http://localhost:8124'
const FACTORY_USER = process.env.FACTORY_USER ?? 'benjamin.valdes'

// ---------------------------------------------------------------------------
// Lecture des credentials Jira depuis user.yaml Coday (fallback env)
// ---------------------------------------------------------------------------

/**
 * Lit le user.yaml Coday pour l'utilisateur courant et extrait les credentials
 * Jira du premier projet qui en possède.
 *
 * Structure attendue dans user.yaml :
 *   projects:
 *     <nom>:
 *       integration:
 *         JIRA:
 *           apiUrl: https://xxx.atlassian.net
 *           username: user@example.com
 *           apiKey: xxx
 *
 * Retourne { apiUrl, username, apiKey } ou null si introuvable / malformé.
 * Pas de dépendance externe : on cherche le bloc JIRA: par regex puis on lit
 * les trois clés qui le suivent — suffisant pour le format généré par Coday.
 */
function readJiraFromCodayConfig() {
  try {
    // Coday sanitizes usernames by replacing ALL non-alphanumeric chars with '_'
    // (see libs/utils/src/lib/username-utils.ts). 'benjamin.valdes' → 'benjamin_valdes'.
    const safeUser = FACTORY_USER.replace(/[^a-zA-Z0-9]/g, '_')
    const configPath = resolve(homedir(), '.coday', 'users', safeUser, 'user.yaml')
    const content = readFileSync(configPath, 'utf8')

    // Trouver le bloc "JIRA:" dans le YAML
    const jiraBlockMatch = content.match(/^(\s+)JIRA:\s*$/m)
    if (!jiraBlockMatch) return null

    const jiraStart = content.indexOf(jiraBlockMatch[0])
    const afterJira = content.slice(jiraStart + jiraBlockMatch[0].length)

    const apiUrl = afterJira.match(/apiUrl:\s*(.+)/)?.[1]?.trim()
    const username = afterJira.match(/username:\s*(.+)/)?.[1]?.trim()
    const apiKey = afterJira.match(/apiKey:\s*(.+)/)?.[1]?.trim()

    if (!apiUrl || !username || !apiKey) return null
    return { apiUrl, username, apiKey }
  } catch {
    return null
  }
}

const _codayJira = readJiraFromCodayConfig()

// ---------------------------------------------------------------------------
// Credentials Jira — lus depuis l'environnement du dashboard, pas du formulaire.
//
// POURQUOI ICI ET PAS DANS LE FORMULAIRE (décision O-C, 2026-08-26)
// ------------------------------------------------------------------
// Le formulaire garde FACTORY_TICKET (l'identifiant du ticket, qui n'est pas
// un secret : c'est un numéro visible dans l'URL). Les credentials (email +
// token API) sont des secrets : un token d'API n'a rien à faire dans un
// formulaire web, même local. Il transiterait en clair dans la requête HTTP,
// serait visible dans l'onglet Réseau des DevTools, et serait logé si le
// serveur log ses requêtes.
//
// Le serveur du dashboard est lancé manuellement par un humain qui maîtrise
// son environnement : c'est l'endroit où passer des secrets. Si les variables
// sont absentes, l'endpoint répond 501 avec un message actionnable.
//
// SOURCE UNIQUE (unification 2026-08-28)
// ----------------------------------------
// Le formulaire ne porte plus du tout ces trois champs. Ces constantes sont
// désormais la seule source pour le run (propagation explicite dans launchRun)
// ET pour l'affichage (endpoint /api/jira/:ticketId). Avant cette unification,
// deux sources indépendantes pour le même secret pouvaient diverger sans que
// rien ne le signale : le formulaire renvoyait ses valeurs au process enfant,
// l'environnement du serveur alimentait l'endpoint d'affichage, et aucun
// mécanisme ne garantissait qu'elles étaient identiques.
// ---------------------------------------------------------------------------
// Priorité : variable d'environnement > user.yaml Coday.
// Le fallback sur user.yaml évite de devoir passer les credentials à chaque
// démarrage quand ils sont déjà configurés dans Coday.
const JIRA_BASE_URL = process.env.JIRA_BASE_URL ?? _codayJira?.apiUrl ?? null
const JIRA_EMAIL = process.env.JIRA_EMAIL ?? _codayJira?.username ?? null
const JIRA_API_TOKEN = process.env.JIRA_API_TOKEN ?? _codayJira?.apiKey ?? null

// ---------------------------------------------------------------------------
// Registre en mémoire des process en cours
// { runId → { child: ChildProcess|null, listeners: Set<ServerResponse>, lines: string[] } }
// ---------------------------------------------------------------------------
const activeRuns = new Map()

// ---------------------------------------------------------------------------
// Lecture des JSONL
// ---------------------------------------------------------------------------

function parseJsonl(filePath) {
  try {
    return readFileSync(filePath, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => { try { return JSON.parse(line) } catch { return null } })
      .filter(Boolean)
  } catch {
    return []
  }
}

function summarizeRun(runId) {
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  const lines = parseJsonl(filePath)

  const start = lines.find((l) => l.kind === 'run_start')
  const end = lines.find((l) => l.kind === 'run_end')
  const phaseEnds = lines.filter((l) => l.kind === 'phase_end')

  let status = 'running'
  if (end) status = end.status
  else if (!activeRuns.has(runId)) status = 'crashed'

  // namespaceId is a durable fact written at run creation time (run_start record).
  // Historical runs that pre-date this field will have undefined here, which is
  // correct: they genuinely have no namespace association.
  const namespaceId = start?.namespaceId ?? undefined

  const summary = {
    runId,
    workflow: start?.workflow ?? '?',
    startedAt: start?.startedAt ?? null,
    endedAt: end?.endedAt ?? null,
    durationMs: end?.durationMs ?? null,
    status,
    phaseCount: phaseEnds.length,
    context: extractContext(lines),
  }
  if (namespaceId !== undefined) summary.namespaceId = namespaceId
  return summary
}

/**
 * Extrait le contexte d'un run depuis les faits de ses phases.
 *
 * Rien n'est calculé ni déduit : chaque valeur est lue telle quelle dans un
 * `facts` déjà enregistré. Le but est de remonter en tête d'écran ce qu'il
 * fallait jusqu'ici déplier une phase pour lire — sur quel dépôt le run a
 * tourné, avec quelle commande de vérité, sur quel ticket.
 *
 * NOTE : `namespaceId` n'est enregistré nulle part par les workflows. Il est
 * récupéré à l'affichage depuis les événements du case (chaque `CaseEvent` le
 * porte), et non depuis le registre.
 *
 * @param {object[]} lines  Lignes JSONL déjà parsées.
 * @returns {object}
 */
function extractContext(lines) {
  const ctx = {}
  for (const l of lines) {
    if (l.kind !== 'phase_end') continue
    const f = l.facts ?? {}
    if (f.domain && !ctx.domain) ctx.domain = f.domain
    if (f.rootPath && !ctx.rootPath) ctx.rootPath = f.rootPath
    if (f.command && !ctx.command) ctx.command = f.command
    if (f.ticketId && !ctx.ticketId) ctx.ticketId = f.ticketId
    if (f.summary && !ctx.ticketSummary) ctx.ticketSummary = f.summary
    // Rôles : un seul pour fix-loop, deux pour us-loop.
    if (f.agentName && !ctx.roles) ctx.roles = [f.agentName]
    if (f.analystName || f.editorName) {
      ctx.roles = [f.analystName, f.editorName].filter(Boolean)
    }
  }
  return ctx
}

/**
 * Rebuild phases exclusively from registry records. A phase start timestamp is
 * written on the `phase` record; `phase_end` contributes only the outcome and
 * duration. Keeping that boundary explicit avoids accidentally using an end
 * record's timestamp as a visual start.
 */
function reconstructPhases(lines) {
  const phaseOrder = []
  const phaseStarts = new Map()
  const phaseEnds = new Map()
  for (const line of lines) {
    if (line.kind === 'phase') {
      if (!phaseStarts.has(line.name)) phaseOrder.push(line.name)
      phaseStarts.set(line.name, line)
    } else if (line.kind === 'phase_end') {
      phaseEnds.set(line.name, line)
    }
  }
  return phaseOrder.map((name) => {
    const start = phaseStarts.get(name)
    const end = phaseEnds.get(name)
    return {
      name,
      phaseKind: start?.phaseKind ?? '?',
      status: end?.status ?? 'running',
      startedAt: start?.startedAt ?? null,
      durationMs: end?.durationMs ?? null,
      facts: end?.facts ?? {},
    }
  })
}

function detailRun(runId) {
  const filePath = join(RUNS_DIR, `${runId}.jsonl`)
  if (!existsSync(filePath)) return null

  const lines = parseJsonl(filePath)
  const summary = summarizeRun(runId)
  const phases = reconstructPhases(lines)

  // Lignes de log en mémoire (uniquement pour les runs actifs)
  const logLines = activeRuns.get(runId)?.lines ?? []

  return { ...summary, phases, logLines }
}

function listRuns() {
  let files
  try { files = readdirSync(RUNS_DIR).filter((f) => f.endsWith('.jsonl')) }
  catch { return [] }
  return files
    .sort((a, b) => b.localeCompare(a))
    .map((f) => summarizeRun(f.replace('.jsonl', '')))
}

// ---------------------------------------------------------------------------
// Lancement d'un run
// ---------------------------------------------------------------------------

/**
 * Démarre node factory/run.mjs <workflow>.
 *
 * Stratégie pour obtenir le runId :
 * - Le workflow appelle createRun() qui crée immédiatement un fichier JSONL
 *   dans factory/runs/ avec un nom de la forme <runId>.jsonl.
 * - On surveille le répertoire factory/runs/ pour détecter ce nouveau fichier
 *   via watchFile sur les fichiers existants + polling.
 * - Plus simple : on prend un snapshot des fichiers existants AVANT de lancer,
 *   puis on poll jusqu'à ce qu'un nouveau fichier apparaisse.
 *
 * @param {object} params
 * @returns {{ pid: number, error?: string }}
 */
function launchRun(params) {
  const {
    workflow = 'fix-loop',
    FACTORY_NAMESPACE_ID,
    FACTORY_AGENT,
    FACTORY_AGENT_ANALYST,
    FACTORY_AGENT_EDITOR,
    FACTORY_TASK,
    FACTORY_SCOPE,
    FACTORY_DOMAIN,
    AGENTOS_URL: agentosUrl,
    FACTORY_USER: factoryUser,
  } = params

  // Les workflows et diagnostics n'ont pas les mêmes rôles.
  //
  // fix-loop / agentos-smoke : un seul rôle (FACTORY_AGENT).
  // us-loop  : deux rôles distincts (FACTORY_AGENT_ANALYST + FACTORY_AGENT_EDITOR),
  //            et FACTORY_AGENT n'est PAS lu par le workflow.
  // backend-oracle-check : aucun agent, pas de FACTORY_NAMESPACE_ID requis.
  //
  // Les alias hérités (smoke, verify-back) sont acceptés ici aussi.
  const ITEMS_WITH_SINGLE_ROLE = new Set(['fix-loop', 'smoke', 'agentos-smoke'])
  const ITEMS_WITHOUT_AGENT = new Set(['backend-oracle-check', 'verify-back'])

  if (!FACTORY_NAMESPACE_ID && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_NAMESPACE_ID manquant' }
  if (!FACTORY_TASK && !ITEMS_WITHOUT_AGENT.has(workflow)) return { error: 'FACTORY_TASK manquant' }
  if (ITEMS_WITH_SINGLE_ROLE.has(workflow) && !FACTORY_AGENT) {
    return { error: `FACTORY_AGENT manquant (requis par "${workflow}")` }
  }

  // Fail-fast : refuser avant de créer quoi que ce soit si un ticket est demandé
  // mais que les credentials Jira manquent dans l'environnement du serveur.
  //
  // Sans cette garde, le run part, crée un fichier de registre, démarre un
  // process, et échoue dans la phase `fetch-ticket` — proprement, mais tard.
  // Refuser ici est préférable : aucun artefact n'est créé, et le message dit
  // exactement quoi faire.
  if (params.FACTORY_TICKET) {
    const missingJira = [
      !JIRA_BASE_URL ? 'JIRA_BASE_URL' : null,
      !JIRA_EMAIL    ? 'JIRA_EMAIL'    : null,
      !JIRA_API_TOKEN ? 'JIRA_API_TOKEN' : null,
    ].filter(Boolean)
    if (missingJira.length > 0) {
      return {
        error:
          `Le serveur du dashboard n'a pas de credentials Jira configurés ` +
          `(manquant : ${missingJira.join(', ')}). ` +
          `Relancez-le avec ces variables dans son environnement : ` +
          `JIRA_BASE_URL=https://votre-instance.atlassian.net ` +
          `JIRA_EMAIL=votre@email.com ` +
          `JIRA_API_TOKEN=votre-token ` +
          `node factory/dashboard/server.mjs`,
      }
    }
  }

  // Snapshot des fichiers existants avant le lancement
  let existingFiles
  try { existingFiles = new Set(readdirSync(RUNS_DIR)) }
  catch { existingFiles = new Set() }

  const env = {
    ...process.env,
    FACTORY_NAMESPACE_ID,
    FACTORY_TASK,
    AGENTOS_URL: agentosUrl ?? AGENTOS_URL,
    FACTORY_USER: factoryUser ?? FACTORY_USER,
  }
  if (FACTORY_AGENT) env.FACTORY_AGENT = FACTORY_AGENT
  if (FACTORY_AGENT_ANALYST) env.FACTORY_AGENT_ANALYST = FACTORY_AGENT_ANALYST
  if (FACTORY_AGENT_EDITOR) env.FACTORY_AGENT_EDITOR = FACTORY_AGENT_EDITOR
  if (FACTORY_SCOPE) env.FACTORY_SCOPE = FACTORY_SCOPE
  if (FACTORY_DOMAIN) env.FACTORY_DOMAIN = FACTORY_DOMAIN
  if (params.FACTORY_ROOT) env.FACTORY_ROOT = params.FACTORY_ROOT
  if (params.FACTORY_COMMAND_FRONT) env.FACTORY_COMMAND_FRONT = params.FACTORY_COMMAND_FRONT
  if (params.FACTORY_COMMAND_BACK) env.FACTORY_COMMAND_BACK = params.FACTORY_COMMAND_BACK
  if (params.FACTORY_CWD_FRONT) env.FACTORY_CWD_FRONT = params.FACTORY_CWD_FRONT
  if (params.FACTORY_CWD_BACK) env.FACTORY_CWD_BACK = params.FACTORY_CWD_BACK
  if (params.FACTORY_TICKET) env.FACTORY_TICKET = params.FACTORY_TICKET

  // Propagation explicite des credentials Jira depuis les constantes du module.
  //
  // Ces variables sont déjà héritées de l'environnement du serveur via le
  // spread `{ ...process.env, … }` ci-dessus. On les répète ici pour la
  // lisibilité : quelqu'un qui lit launchRun() doit voir d'où viennent les
  // credentials du process enfant sans avoir à raisonner sur le spread.
  //
  // Les valeurs éventuellement envoyées par le client dans `params` sont
  // ignorées silencieusement : elles ne sont jamais lues ni propagées.
  if (JIRA_BASE_URL) env.JIRA_BASE_URL = JIRA_BASE_URL
  if (JIRA_EMAIL) env.JIRA_EMAIL = JIRA_EMAIL
  if (JIRA_API_TOKEN) env.JIRA_API_TOKEN = JIRA_API_TOKEN

  // Construire les arguments CLI selon la forme canonique.
  // Les alias hérités (smoke, verify-back) sont transmis tels quels ;
  // run.mjs les résout et affiche l'avertissement de déprécation.
  const WORKFLOWS = new Set(['fix-loop', 'us-loop'])
  const DIAGNOSTICS = new Set(['agentos-smoke', 'backend-oracle-check'])
  let runArgs
  if (WORKFLOWS.has(workflow)) {
    runArgs = ['workflow', workflow]
  } else if (DIAGNOSTICS.has(workflow)) {
    runArgs = ['diagnostic', workflow]
  } else {
    // Alias hérité ou inconnu : passer directement, run.mjs gère l'erreur
    runArgs = [workflow]
  }

  const child = spawn(process.execPath, [RUN_ENTRY, ...runArgs], {
    env,
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  // Entrée provisoire indexée par PID le temps de trouver le runId
  const pidKey = `pid:${child.pid}`
  activeRuns.set(pidKey, { child, listeners: new Set(), lines: [] })

  let runId = null
  const entry = () => activeRuns.get(runId ?? pidKey)

  function broadcast(line) {
    const e = entry()
    if (!e) return
    e.lines.push(line)
    for (const res of e.listeners) {
      try { res.write(`data: ${JSON.stringify({ line })}\n\n`) }
      catch { e.listeners.delete(res) }
    }
  }

  // Poll pour découvrir le nouveau fichier JSONL (créé dans les premières ms)
  const pollInterval = setInterval(() => {
    let files
    try { files = readdirSync(RUNS_DIR).filter((f) => f.endsWith('.jsonl')) }
    catch { return }
    const newFile = files.find((f) => !existingFiles.has(f))
    if (newFile && !runId) {
      runId = newFile.replace('.jsonl', '')
      // Migrer l'entrée du PID vers le runId
      const old = activeRuns.get(pidKey)
      if (old) {
        activeRuns.delete(pidKey)
        activeRuns.set(runId, old)
      }
    }
  }, 200)

  // Lire stdout/stderr ligne par ligne et broadcaster
  let stdoutBuf = ''
  child.stdout.on('data', (chunk) => {
    stdoutBuf += chunk.toString()
    const parts = stdoutBuf.split('\n')
    stdoutBuf = parts.pop()
    for (const line of parts) broadcast(line)
  })

  let stderrBuf = ''
  child.stderr.on('data', (chunk) => {
    stderrBuf += chunk.toString()
    const parts = stderrBuf.split('\n')
    stderrBuf = parts.pop()
    for (const line of parts) broadcast(`[stderr] ${line}`)
  })

  child.on('close', () => {
    clearInterval(pollInterval)
    if (stdoutBuf) broadcast(stdoutBuf)
    if (stderrBuf) broadcast(`[stderr] ${stderrBuf}`)

    const e = entry()
    if (e) {
      for (const res of e.listeners) {
        try { res.write(`data: ${JSON.stringify({ done: true })}\n\n`) } catch {}
        try { res.end() } catch {}
      }
      e.listeners.clear()
      e.child = null
    }
  })

  return { pid: child.pid }
}

// ---------------------------------------------------------------------------
// Proxy AgentOS
// ---------------------------------------------------------------------------

async function fetchAgents(namespaceId) {
  const url = `${AGENTOS_URL}/api/agent-configs/by-parentId/${namespaceId}`
  const res = await fetch(url, { headers: { 'X-External-User-Id': FACTORY_USER } })
  if (!res.ok) throw new Error(`AgentOS ${res.status}`)
  return res.json()
}

/**
 * Récupère les événements d'un case AgentOS.
 *
 * Même endpoint que celui utilisé par `lib/agentos.mjs` pour la détection de fin
 * de tour : `GET /api/case-events/by-parentId/{caseId}`. L'ordre chronologique
 * est garanti par le backend.
 *
 * @param {string} caseId
 * @returns {Promise<object[]>}
 */
async function fetchCaseEvents(caseId) {
  const url = `${AGENTOS_URL}/api/case-events/by-parentId/${caseId}`
  const res = await fetch(url, { headers: { 'X-External-User-Id': FACTORY_USER } })
  if (!res.ok) throw new Error(`AgentOS ${res.status}`)
  return res.json()
}

// ---------------------------------------------------------------------------
// Routeur HTTP
// ---------------------------------------------------------------------------

function send(res, status, body, ct = 'application/json') {
  const data = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': ct,
    'Access-Control-Allow-Origin': '*',
  })
  res.end(data)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => { data += c })
    req.on('end', () => { try { resolve(JSON.parse(data || '{}')) } catch { resolve({}) } })
    req.on('error', reject)
  })
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const path = url.pathname
  const method = req.method

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST',
      'Access-Control-Allow-Headers': 'Content-Type',
    })
    return res.end()
  }

  // UI
  if (method === 'GET' && (path === '/' || path === '/index.html')) {
    const html = readFileSync(join(__dirname, 'index.html'), 'utf8')
    return send(res, 200, html, 'text/html; charset=utf-8')
  }

  // GET /api/config — expose l'URL AgentOS pour les liens profonds côté client,
  // et la disponibilité Jira pour que l'interface puisse informer l'utilisateur.
  //
  // IMPORTANT : on n'expose JAMAIS les valeurs des credentials, ni même
  // partiellement (pas d'email en clair, pas de token tronqué). Un booléen
  // suffit : l'interface a besoin de savoir si la fonctionnalité est disponible,
  // pas avec quels secrets. Exposer même un fragment d'un secret dans une API
  // HTTP serait contraire à la raison d'être de cette architecture.
  if (method === 'GET' && path === '/api/config') {
    const jiraConfigured = !!(JIRA_BASE_URL && JIRA_EMAIL && JIRA_API_TOKEN)
    return send(res, 200, { agentosUrl: AGENTOS_URL, factoryUser: FACTORY_USER, jiraConfigured })
  }

  // GET /api/runs
  if (method === 'GET' && path === '/api/runs') {
    return send(res, 200, listRuns())
  }

  // POST /api/runs
  if (method === 'POST' && path === '/api/runs') {
    const body = await readBody(req)
    const result = launchRun(body)
    if (result.error) return send(res, 400, { error: result.error })
    return send(res, 202, result)
  }

  // GET /api/runs/:id
  const detailMatch = path.match(/^\/api\/runs\/([^/]+)$/)
  if (method === 'GET' && detailMatch) {
    const detail = detailRun(detailMatch[1])
    if (!detail) return send(res, 404, { error: 'Run introuvable' })
    return send(res, 200, detail)
  }

  // GET /api/runs/:id/stream  (SSE)
  const streamMatch = path.match(/^\/api\/runs\/([^/]+)\/stream$/)
  if (method === 'GET' && streamMatch) {
    const runId = streamMatch[1]
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    })
    res.write(': connected\n\n')

    const e = activeRuns.get(runId)
    if (!e || !e.child) {
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
      return res.end()
    }
    // Rejouer les lignes déjà émises
    for (const line of e.lines) {
      res.write(`data: ${JSON.stringify({ line })}\n\n`)
    }
    e.listeners.add(res)
    req.on('close', () => e.listeners.delete(res))
    return
  }

  // GET /api/cases/:caseId/events — proxy AgentOS.
  const eventsMatch = path.match(/^\/api\/cases\/([^/]+)\/events$/)
  if (method === 'GET' && eventsMatch) {
    try {
      const events = await fetchCaseEvents(eventsMatch[1])
      return send(res, 200, events)
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  // GET /api/jira/:ticketId — contenu d'un ticket Jira récupéré maintenant.
  //
  // Cet endpoint est appelé par le dashboard lorsque l'utilisateur déplie la
  // phase fetch-ticket. Il récupère le ticket au moment de l'affichage, pas
  // au moment du run — voir l'avertissement de mutabilité dans index.html.
  const jiraMatch = path.match(/^\/api\/jira\/([^/]+)$/)
  if (method === 'GET' && jiraMatch) {
    // Si les credentials Jira ne sont pas configurés dans l'environnement du
    // dashboard, on répond 501 (Not Implemented) avec un message actionnable.
    // 501 plutôt que 403 : ce n'est pas un problème d'autorisation (le serveur
    // ne sait même pas qui est l'utilisateur), c'est une configuration manquante.
    if (!JIRA_BASE_URL || !JIRA_EMAIL || !JIRA_API_TOKEN) {
      const missing = [
        !JIRA_BASE_URL ? 'JIRA_BASE_URL' : null,
        !JIRA_EMAIL ? 'JIRA_EMAIL' : null,
        !JIRA_API_TOKEN ? 'JIRA_API_TOKEN' : null,
      ].filter(Boolean)
      return send(res, 501, {
        error:
          `Le serveur du dashboard n'a pas de credentials Jira configurés ` +
          `(manquant : ${missing.join(', ')}). ` +
          `Relancez-le avec ces variables dans son environnement : ` +
          `JIRA_BASE_URL=https://votre-instance.atlassian.net ` +
          `JIRA_EMAIL=votre@email.com ` +
          `JIRA_API_TOKEN=votre-token ` +
          `node factory/dashboard/server.mjs`,
      })
    }
    try {
      const ticketId = jiraMatch[1]
      const result = await fetchJiraTicket(ticketId, JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN)
      return send(res, 200, { ...result, fetchedAt: new Date().toISOString() })
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  // ---------------------------------------------------------------------------
  // /api/factory/* — stable aliases for Angular client consumption (slice A1).
  //
  // DESIGN DECISIONS
  // ─────────────────
  // 1. These routes are ALIASES, not replacements. The original /api/runs,
  //    /api/runs/:id, and /api/runs/:id/stream routes are preserved unchanged
  //    for backward compatibility with the standalone dashboard.
  //
  // 2. namespaceId filtering (GET /api/factory/runs?namespaceId=<uuid>):
  //    - A run matches when its run_start record has a namespaceId field that
  //      equals the requested value.
  //    - Historical runs without a namespaceId field DO NOT match any namespace
  //      query. They are not fabricated into any namespace.
  //    - When no namespaceId query param is provided, all runs are returned
  //      (same behavior as GET /api/runs).
  //
  // 3. Secrets, filesystem, process spawning, Jira, and AgentOS identity
  //    remain server-side. The Angular client receives only JSON payloads.
  //
  // 4. Run launch (POST /api/factory/runs) and stream (GET /api/factory/runs/:id/stream)
  //    are aliased here so Angular can use a single base path.
  // ---------------------------------------------------------------------------

  // GET /api/factory/runs[?namespaceId=<uuid>]
  if (method === 'GET' && path === '/api/factory/runs') {
    const nsFilter = url.searchParams.get('namespaceId')
    const all = listRuns()
    if (!nsFilter) return send(res, 200, all)
    // Only return runs that explicitly carry the requested namespaceId.
    // Runs without the field (historical) are excluded — not fabricated.
    const filtered = all.filter((r) => r.namespaceId === nsFilter)
    return send(res, 200, filtered)
  }

  // POST /api/factory/runs — launches a run and waits to resolve the runId.
  //
  // Unlike POST /api/runs (which returns immediately with only pid), this alias
  // waits up to 3 seconds for the workflow to create its JSONL file and resolves
  // the runId before responding. Angular clients can navigate directly to the new
  // run without a separate polling step.
  //
  // Response: { pid, runId } on success, { error } on validation failure.
  // runId may be null if the file is not created within 3 seconds (edge case).
  if (method === 'POST' && path === '/api/factory/runs') {
    const body = await readBody(req)
    const result = launchRun(body)
    if (result.error) return send(res, 400, { error: result.error })
    const pid = result.pid
    const deadline = Date.now() + 3000
    const runId = await new Promise((resolve) => {
      const check = () => {
        for (const [key, entry] of activeRuns) {
          if (!key.startsWith('pid:') && entry.child?.pid === pid) return resolve(key)
        }
        if (Date.now() >= deadline) return resolve(null)
        setTimeout(check, 200)
      }
      check()
    })
    return send(res, 202, { pid, runId })
  }

  // GET /api/factory/runs/:id
  const factoryDetailMatch = path.match(/^\/api\/factory\/runs\/([^/]+)$/)
  if (method === 'GET' && factoryDetailMatch) {
    const detail = detailRun(factoryDetailMatch[1])
    if (!detail) return send(res, 404, { error: 'Run introuvable' })
    return send(res, 200, detail)
  }

  // GET /api/factory/runs/:id/stream — SSE alias
  const factoryStreamMatch = path.match(/^\/api\/factory\/runs\/([^/]+)\/stream$/)
  if (method === 'GET' && factoryStreamMatch) {
    const runId = factoryStreamMatch[1]
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive',
      'Access-Control-Allow-Origin': '*',
    })
    res.write(': connected\n\n')

    const e = activeRuns.get(runId)
    if (!e || !e.child) {
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`)
      return res.end()
    }
    for (const line of e.lines) {
      res.write(`data: ${JSON.stringify({ line })}\n\n`)
    }
    e.listeners.add(res)
    req.on('close', () => e.listeners.delete(res))
    return
  }

  // GET /api/agents?namespaceId=
  if (method === 'GET' && path === '/api/agents') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(res, 400, { error: 'namespaceId requis' })
    try {
      const agents = await fetchAgents(namespaceId)
      return send(res, 200, agents)
    } catch (err) {
      return send(res, 502, { error: String(err) })
    }
  }

  send(res, 404, { error: 'Not found' })
})

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  server.listen(PORT, () => {
    console.log(`Factory dashboard → http://localhost:${PORT}`)
    console.log(`AgentOS           : ${AGENTOS_URL}`)
    if (JIRA_BASE_URL) {
      const src = process.env.JIRA_BASE_URL ? 'env' : 'user.yaml Coday'
      console.log(`Jira              : ${JIRA_BASE_URL} (${src})`)
    } else {
      console.log(`Jira              : non configuré (tickets Jira indisponibles)`)
    }
  })
}

export { parseJsonl, reconstructPhases }
