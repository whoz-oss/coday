/**
 * Provisionne les rôles de phase `factory-editor` et `factory-analyst`
 * dans un namespace AgentOS.
 *
 * Usage :
 *   FACTORY_NAMESPACE_ID=<uuid> node factory/provision.mjs
 *
 * Idempotent : si un objet existe déjà, il est mis à jour (PUT) plutôt que recréé.
 *
 * ## Pourquoi un script plutôt que la création à la main dans l'UI
 *
 * Le périmètre de capacité d'un rôle de phase est ce qui rend le verdict de
 * l'orchestrateur crédible. Un agent qui pourrait lancer le build lui-même, ou
 * déléguer à d'autres agents, casserait l'invariant acteur/oracle en silence.
 *
 * Créé à la main, ce périmètre dérive : on ajoute une intégration « juste pour
 * essayer » six mois plus tard, pour une bonne raison locale, et personne ne relie
 * ce geste à la fiabilité des runs. Écrit ici, il est versionné, relisable en diff,
 * et reproductible sur une autre machine.
 *
 * ## Quatre objets créés
 *
 * | Objet              | Type        | Paramètres                              |
 * |--------------------|-------------|----------------------------------------|
 * | FACTORY_FILES      | FILE_ACCESS | rootPath = REPO_ROOT, readOnly: false  |
 * | FACTORY_FILES_RO   | FILE_ACCESS | rootPath = REPO_ROOT, readOnly: true   |
 * | factory-editor     | agent       | FACTORY_FILES + QUERY_USER: []         |
 * | factory-analyst    | agent       | FACTORY_FILES_RO + QUERY_USER: []      |
 *
 * ## Le périmètre, et ce qui en est exclu
 *
 * ACCORDÉ à l'éditeur : `FACTORY_FILES` — lecture et écriture sur le dépôt.
 * ACCORDÉ à l'analyste : `FACTORY_FILES_RO` — lecture seule sur le dépôt.
 *
 * REFUSÉ aux deux, et les raisons :
 *
 *   - `PROJECT_SCRIPTS` / `BASH` — l'agent pourrait lancer le build. Un acteur qui
 *     tient son propre oracle bricole jusqu'au vert et rapporte « succès » de bonne
 *     foi. C'est le défaut de Gradlay, dans l'autre sens.
 *   - `MCP_STDIO` (dont `NX`) — lance un processus enfant arbitraire ; un serveur MCP
 *     filesystem ou shell redonne un accès non borné. C'est la brèche qui contourne
 *     toutes les autres restrictions.
 *   - `GIT` — l'agent n'a pas à committer ni à manipuler l'index. L'orchestrateur
 *     mesure le diff du working tree ; un `git checkout` ou un `stash` côté agent
 *     rendrait cette mesure fausse sans rien casser de visible.
 *   - `subAgents` — `DelegationTool` parallélise sans condition. Un rôle de phase qui
 *     délègue peut lancer plusieurs agents sur le même disque à l'insu de
 *     l'orchestrateur qui croit avoir séquencialisé.
 *   - `GITHUB`, `FETCH`, `CHROME`, `PLAYWRIGHT` — hors sujet pour éditer un fichier.
 *     Un périmètre large n'est pas neutre : il augmente la surface de dérive.
 *
 * ## Pourquoi readOnly: true pour l'analyste est structurel, pas cosmétique
 *
 * Un analyste capable d'écrire commence à implémenter (comportement serviable
 * par défaut d'un modèle), et son plan devient alors la narration de ce qu'il a
 * déjà fait — exactement ce que cette factory existe pour ne pas faire circuler.
 *
 * Note : `QUERY_USER` est accordé par défaut côté AgentOS (`QueryUserToolGrantService`,
 * `enabledByDefault = true`). On le désactive explicitement par une liste vide —
 * l'opt-out prévu par ce mécanisme. Un agent qui pose une question dans un run
 * automatique bloque le case jusqu'au timeout : personne n'est là pour répondre.
 */

import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Racine du dépôt cible — là où les agents doivent lire et écrire.
 *
 * Par défaut : un niveau au-dessus de `factory/` (le dépôt qui contient ce script).
 * Surcharge possible via `FACTORY_ROOT` quand le dépôt cible est différent du dépôt
 * qui héberge la factory.
 *
 * Exemple :
 *   FACTORY_ROOT=/path/to/other-repo node factory/provision.mjs
 */
const REPO_ROOT = process.env.FACTORY_ROOT
  ? resolve(process.env.FACTORY_ROOT)
  : join(__dirname, '..')

const BASE_URL = process.env.AGENTOS_URL ?? 'http://localhost:8124'
const FACTORY_USER = process.env.FACTORY_USER ?? 'benjamin.valdes'

/** Nom du rôle d'édition. */
const EDITOR_AGENT_NAME = 'factory-editor'

/** Nom du rôle d'analyse. */
const ANALYST_AGENT_NAME = 'factory-analyst'

/** Intégration lecture+écriture (pour l'éditeur). */
const INTEGRATION_RW = 'FACTORY_FILES'

/** Intégration lecture seule (pour l'analyste). */
const INTEGRATION_RO = 'FACTORY_FILES_RO'

/**
 * Instructions du rôle éditeur.
 */
const EDITOR_INSTRUCTIONS = `You are factory-editor, an execution role invoked by an external orchestrator.

Your job is to make a requested change to files on disk. Nothing else.

## How you work

1. Read the current state of the relevant files before changing anything. Never assume
   what a previous attempt did — the disk is the only source of truth.
2. Make the smallest change that accomplishes the task.
3. Stay strictly within the scope given in the brief.
4. Report briefly what you changed, file by file.

## What you must NOT do

- Do NOT build, compile, lint, test, or otherwise verify your work. Verification is
  performed independently by the orchestrator and is not your responsibility. You do
  not have the tools to do it, and claiming to have done it would be false.
- Do NOT touch files outside the stated scope.
- Do NOT commit, stage, stash or otherwise manipulate git state.

## When the task is not right

If the work required falls outside the scope you were given, or if you determine the
real problem lies elsewhere, say so explicitly and stop without changing anything.
Reporting "this is not the right place to fix it" is a successful outcome, not a
failure. Making a plausible change in the wrong place is much worse than making none.`

/**
 * Instructions du rôle analyste.
 */
const ANALYST_INSTRUCTIONS = `You are factory-analyst, a read-only analysis role invoked by an external orchestrator.

Your job is to analyse the codebase and produce a structured implementation plan
that a separate editor agent will execute. You must NOT modify any file.

## You are read-only

You have no write access to the repository. Do not attempt to write, create, or
modify any file. Your only output is the plan you produce in your response.

## How you work

1. Read the relevant files to understand the current state of the code.
2. Identify exactly which existing files need to change to accomplish the task.
3. Produce a structured JSON plan in a \`\`\`json code fence.

## Output format

Respond with a JSON object in a \`\`\`json code fence:

\`\`\`json
{
  "files": ["path/relative/to/repo/root.ts"],
  "doneWhen": "verifiable completion criterion",
  "steps": ["step 1", "step 2"]
}
\`\`\`

- \`files\` : RELATIVE paths from repo root, existing files the editor will modify. Required, non-empty.
- \`doneWhen\` : required, non-empty.
- \`steps\` : optional ordered implementation steps for the editor.

Only list files that already exist. Do not list files that need to be created.

## When the task is not right

If the work required is outside the scope you were given, or if you determine the
real problem lies elsewhere, say so explicitly and stop. Reporting "this is not the
right place to fix it" is a successful outcome, not a failure.`

const EDITOR_DESCRIPTION =
  'Phase role for the factory orchestrator: edits files on disk, never verifies its own work.'

const ANALYST_DESCRIPTION =
  'Phase role for the factory orchestrator: read-only analysis, produces a structured plan for the editor.'

/**
 * @param {string} method
 * @param {string} path
 * @param {unknown} [body]
 * @returns {Promise<Response>}
 */
async function request(method, path, body) {
  const url = `${BASE_URL}${path}`
  const init = {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-External-User-Id': FACTORY_USER,
    },
  }
  if (body !== undefined) init.body = JSON.stringify(body)

  const res = await fetch(url, init)
  if (!res.ok) {
    let text = ''
    try {
      text = await res.text()
    } catch {
      // ignore
    }
    throw new Error(`AgentOS ${method} ${url} \u2192 HTTP ${res.status}\n${text}`)
  }
  return res
}

/**
 * Crée ou met à jour une intégration FILE_ACCESS.
 *
 * @param {string} namespaceId
 * @param {object[]} existingIntegrations  Liste relue depuis l'API.
 * @param {string} name
 * @param {boolean} readOnly
 * @returns {Promise<object>}  L'objet intégration relu depuis le serveur.
 */
async function provisionIntegration(namespaceId, existingIntegrations, name, readOnly) {
  const payload = {
    namespaceId,
    userId: null,
    name,
    integrationType: 'FILE_ACCESS',
    description: readOnly
      ? `Accès fichier en lecture seule pour l'orchestrateur factory — borné au dépôt local.`
      : `Accès fichier de l'orchestrateur factory — borné au dépôt local.`,
    parameters: {
      rootPath: REPO_ROOT,
      readOnly,
      readMaxSizeMb: 10,
      extraDenyPatterns: [],
    },
  }

  const existing = existingIntegrations.find((i) => i.name === name)

  let integration
  if (existing) {
    const res = await request('PUT', `/api/integration-configs/${existing.id}`, payload)
    integration = await res.json()
    console.log(`\u2713 Intégration "${name}" mise à jour`)
  } else {
    const res = await request('POST', '/api/integration-configs', payload)
    integration = await res.json()
    console.log(`\u2713 Intégration "${name}" créée`)
  }

  // Vérification de l'état relu
  const actualRoot = integration.parameters?.rootPath
  if (actualRoot !== REPO_ROOT) {
    console.error(
      `\u2717 Le rootPath relu pour "${name}" (${actualRoot}) diffère de celui envoyé (${REPO_ROOT}).`
    )
    process.exit(1)
  }
  console.log(`  rootPath : ${actualRoot}`)

  if (readOnly) {
    const actualReadOnly = integration.parameters?.readOnly
    if (actualReadOnly !== true) {
      console.error(
        `\u2717 L'intégration "${name}" devrait être readOnly:true mais le serveur retourne readOnly:${actualReadOnly}.`
      )
      process.exit(1)
    }
    console.log(`  readOnly : ${actualReadOnly} \u2714`)
  }

  return integration
}

/**
 * Crée ou met à jour un agent de phase.
 *
 * @param {string} namespaceId
 * @param {object[]} existingAgents  Liste relue depuis l'API.
 * @param {string} agentName
 * @param {string} description
 * @param {string} instructions
 * @param {string} integrationName  Nom de l'intégration FILE_ACCESS à lier.
 * @returns {Promise<object>}  L'objet agent relu depuis le serveur.
 */
async function provisionAgent(
  namespaceId,
  existingAgents,
  agentName,
  description,
  instructions,
  integrationName
) {
  const payload = {
    namespaceId,
    name: agentName,
    description,
    instructions,
    integrations: {
      // null = tous les outils de cette intégration.
      [integrationName]: null,
      // Liste vide = opt-out explicite du grant par défaut de QUERY_USER.
      QUERY_USER: [],
    },
    // Absent délibérément : subAgents (pas de délégation — voir l'en-tête).
    enabled: true,
  }

  const existing = existingAgents.find((a) => a.name === agentName)

  let result
  if (existing) {
    const res = await request('PUT', `/api/agent-configs/${existing.id}`, payload)
    result = await res.json()
    console.log(`\u2713 Agent "${agentName}" mis à jour (id: ${result.id})`)
  } else {
    const res = await request('POST', '/api/agent-configs', payload)
    result = await res.json()
    console.log(`\u2713 Agent "${agentName}" créé (id: ${result.id})`)
  }

  // Relire depuis le serveur — on ne fait pas confiance au payload envoyé.
  const verifyRes = await request('GET', `/api/agent-configs/${result.id}`)
  const verified = await verifyRes.json()

  const subAgents = verified.subAgents ?? []
  const integrationKeys = Object.keys(verified.integrations ?? {})

  console.log('')
  console.log(`État relu depuis AgentOS pour "${agentName}" :`)
  console.log(`  name         : ${verified.name}`)
  console.log(`  enabled      : ${verified.enabled}`)
  console.log(`  integrations : ${integrationKeys.join(', ') || '(aucune)'}`)
  console.log(`  subAgents    : ${subAgents.length === 0 ? '(vide \u2014 correct)' : subAgents.join(', ')}`)
  console.log('')

  const problems = []
  if (verified.enabled !== true) problems.push("l'agent n'est pas activé")
  if (subAgents.length > 0) problems.push('subAgents est non-vide')
  if (!integrationKeys.includes(integrationName)) {
    problems.push(`l'intégration ${integrationName} n'est pas liée`)
  }

  if (problems.length > 0) {
    console.error(`\u2717 "${agentName}" ne satisfait pas le préflight : ${problems.join(', ')}.`)
    process.exit(1)
  }

  return verified
}

async function main() {
  const namespaceId = process.env.FACTORY_NAMESPACE_ID
  if (!namespaceId) {
    console.error('Variable manquante : FACTORY_NAMESPACE_ID')
    console.error('Usage : FACTORY_NAMESPACE_ID=<uuid> node factory/provision.mjs')
    process.exit(1)
  }

  console.log(`Racine du dépôt : ${REPO_ROOT}`)
  console.log('')

  // -------------------------------------------------------------------------
  // Étape 1 : récupérer l'état actuel des intégrations et des agents
  // -------------------------------------------------------------------------
  const integrationsRes = await request(
    'GET',
    `/api/integration-configs?namespaceId=${namespaceId}`
  )
  const existingIntegrations = await integrationsRes.json()

  const agentsRes = await request('GET', `/api/agent-configs/by-parentId/${namespaceId}`)
  const existingAgents = await agentsRes.json()

  // -------------------------------------------------------------------------
  // Étape 2 : intégrations
  // -------------------------------------------------------------------------
  console.log('--- Intégrations ---')
  await provisionIntegration(namespaceId, existingIntegrations, INTEGRATION_RW, false)
  await provisionIntegration(namespaceId, existingIntegrations, INTEGRATION_RO, true)
  console.log('')

  // -------------------------------------------------------------------------
  // Étape 3 : agents
  // -------------------------------------------------------------------------
  console.log('--- Agents ---')
  await provisionAgent(
    namespaceId,
    existingAgents,
    EDITOR_AGENT_NAME,
    EDITOR_DESCRIPTION,
    EDITOR_INSTRUCTIONS,
    INTEGRATION_RW
  )

  await provisionAgent(
    namespaceId,
    existingAgents,
    ANALYST_AGENT_NAME,
    ANALYST_DESCRIPTION,
    ANALYST_INSTRUCTIONS,
    INTEGRATION_RO
  )

  console.log(`\u2713 Prêt.`)
  console.log(`  Éditeur  : FACTORY_AGENT_EDITOR=${EDITOR_AGENT_NAME}`)
  console.log(`  Analyste : FACTORY_AGENT_ANALYST=${ANALYST_AGENT_NAME}`)
}

main().catch((err) => {
  console.error(String(err))
  process.exit(1)
})
