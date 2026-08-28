/**
 * Client REST pour AgentOS.
 *
 * DETTE TECHNIQUE : cet orchestrateur s'authentifie sous une identité humaine
 * (FACTORY_USER). Les runs sont donc attribués à quelqu'un qui ne les a pas
 * faits. Il faudra un utilisateur technique dédié (ex. `factory-bot`) dès que
 * AgentOS supportera les comptes de service.
 */

import { setActiveCaseId, clearActiveCaseId } from './active-case.mjs'

const BASE_URL = process.env.AGENTOS_URL ?? 'http://localhost:8124'
const FACTORY_USER = process.env.FACTORY_USER ?? 'benjamin.valdes'

// --------------------------------------------------------------------------
// Utilitaire HTTP de base
// --------------------------------------------------------------------------

/**
 * Effectue un appel REST vers AgentOS.
 * Lève une erreur explicite si le statut n'est pas 2xx.
 *
 * @param {string} method
 * @param {string} path
 * @param {unknown} [body]
 * @returns {Promise<Response>}
 */
async function request(method, path, body) {
  const url = `${BASE_URL}${path}`
  const headers = {
    'Content-Type': 'application/json',
    'X-External-User-Id': FACTORY_USER,
  }

  const init = { method, headers }
  if (body !== undefined) {
    init.body = JSON.stringify(body)
  }

  const res = await fetch(url, init)

  if (!res.ok) {
    let responseBody = ''
    try {
      responseBody = await res.text()
    } catch {
      // ignore
    }
    throw new Error(
      `AgentOS ${method} ${url} → HTTP ${res.status}\n${responseBody}`
    )
  }

  return res
}

// --------------------------------------------------------------------------
// API publique
// --------------------------------------------------------------------------

/**
 * Crée un case racine dans AgentOS.
 * NOTE : ce endpoint n'accepte PAS de `parentCaseId` — un case créé par REST
 * est toujours racine. Ne pas tenter de passer ce champ.
 *
 * @param {string} namespaceId
 * @param {string} title
 * @returns {Promise<object>} L'objet case créé, dont le champ `id`.
 */
export async function createCase(namespaceId, title) {
  const res = await request('POST', '/api/cases', { namespaceId, title })
  return res.json()
}

/**
 * Poste un message dans un case.
 * Le endpoint retourne 200/204 sans corps : on ne tente pas de parser la réponse.
 *
 * ATTENTION : ce POST est asynchrone côté serveur. `CaseServiceImpl.addMessage`
 * fait `scope.launch { runtime.run() }` et rend la main immédiatement. Le case
 * n'est PAS encore passé à RUNNING quand ce POST retourne.
 *
 * @param {string} caseId
 * @param {string} content
 */
export async function postMessage(caseId, content) {
  await request('POST', `/api/cases/${caseId}/messages`, { content })
}

/**
 * Récupère l'état courant d'un case.
 *
 * @param {string} caseId
 * @returns {Promise<object>}
 */
export async function getCase(caseId) {
  const res = await request('GET', `/api/cases/${caseId}`)
  return res.json()
}

/**
 * Liste les événements d'un case, dans l'ordre chronologique.
 *
 * L'ordre est garanti par le backend (`ORDER BY e.timestamp ASC, e.id ASC` dans
 * `CaseEventNodeNeo4jRepository.findActiveByCaseId`). C'est cet ordre qui fait
 * autorité ici, PAS la comparaison des champs `timestamp` — voir `sliceAfterId`.
 *
 * @param {string} caseId
 * @returns {Promise<object[]>}
 */
export async function listEvents(caseId) {
  const res = await request('GET', `/api/case-events/by-parentId/${caseId}`)
  return res.json()
}

/**
 * Tue un case en cours d'exécution.
 *
 * @param {string} caseId
 */
export async function killCase(caseId) {
  await request('POST', `/api/cases/${caseId}/kill`)
}

/**
 * Liste les agents disponibles dans un namespace.
 *
 * @param {string} namespaceId
 * @returns {Promise<object[]>}
 */
export async function listAgents(namespaceId) {
  const res = await request('GET', `/api/agent-configs/by-parentId/${namespaceId}`)
  return res.json()
}

/**
 * Préflight sur un rôle de phase : vérifie qu'il est utilisable par l'orchestrateur.
 *
 * Trois contrôles, tous fail-closed — en cas de doute on refuse de partir.
 *
 * 1. L'AGENT EXISTE. S'il n'existe pas, `@mention` ne résoudra pas et
 *    `CaseServiceImpl.selectAgent` basculera SILENCIEUSEMENT sur l'agent par défaut
 *    du namespace (il émet un WarnEvent puis un AgentSelectedEvent pointant ailleurs).
 *    Le case tournerait normalement et rendrait un `finished` — pour un travail fait
 *    par quelqu'un d'autre que celui qu'on a demandé.
 *
 * 2. L'AGENT EST ACTIVÉ. Même conséquence : un agent désactivé ne résout pas, donc
 *    bascule silencieuse.
 *
 * 3. `subAgents` EST VIDE. `DelegationTool` n'est instancié que si `subAgents` est
 *    non-vide, et son `execute()` fait `delegations.map { async { … } }.awaitAll()` —
 *    parallélisme inconditionnel. Un rôle de phase capable de déléguer peut donc
 *    relancer plusieurs agents en parallèle sur le même disque, à l'insu de
 *    l'orchestrateur qui croit avoir séquentialisé. C'est l'incident des deux
 *    délégations concurrentes, reconstitué par une ligne de configuration ajoutée
 *    six mois plus tard pour une bonne raison locale.
 *
 * ON NE DEMANDE JAMAIS À L'HUMAIN DE CHOISIR UN REMPLAÇANT (décision, 2026-08-19).
 * Un orchestrateur qui pose une question a besoin de quelqu'un pour y répondre. La
 * finalité de cet outil est de tourner sans surveillance : un point d'arrêt interactif
 * au milieu d'une chaîne devient un blocage silencieux dès que personne ne regarde —
 * exactement ce que le statut `pending_question` traite déjà comme un échec de phase.
 * De plus, improviser un remplaçant en cours de route reviendrait à lancer un run avec
 * un rôle dont on ne connaît ni les intégrations ni le périmètre d'écriture.
 *
 * En échange, l'échec est rendu ACTIONNABLE : le rapport liste les agents du namespace
 * avec leur état et leurs `subAgents`, en signalant lesquels sont utilisables comme rôle
 * de phase. On relance avec la bonne valeur de `FACTORY_AGENT`.
 *
 * @param {string} namespaceId
 * @param {string} agentName
 * @returns {Promise<{ ok: boolean, reason: string|null, agent: object|null }>}
 */
export async function preflightAgent(namespaceId, agentName) {
  let agents
  try {
    agents = await listAgents(namespaceId)
  } catch (err) {
    return { ok: false, reason: `Impossible de lister les agents : ${err}`, agent: null }
  }

  const agent = agents.find((a) => a.name === agentName)
  if (!agent) {
    return {
      ok: false,
      reason:
        `Agent "${agentName}" introuvable dans le namespace. ` +
        `Une @mention non résolue bascule silencieusement sur l'agent par défaut côté ` +
        `AgentOS — le run aurait fait travailler quelqu'un d'autre sans le dire.\n\n` +
        formatAgentInventory(agents),
      agent: null,
    }
  }

  if (agent.enabled === false) {
    return {
      ok: false,
      reason:
        `Agent "${agentName}" est désactivé. Une @mention qui ne résout pas bascule ` +
        `silencieusement sur l'agent par défaut.\n\n` +
        formatAgentInventory(agents),
      agent,
    }
  }

  if (Array.isArray(agent.subAgents) && agent.subAgents.length > 0) {
    return {
      ok: false,
      reason:
        `Agent "${agentName}" déclare subAgents=[${agent.subAgents.join(', ')}]. ` +
        `Un rôle de phase ne délègue pas : DelegationTool parallélise sans condition, ` +
        `ce qui rendrait l'ordonnancement de l'orchestrateur inopérant.\n\n` +
        formatAgentInventory(agents),
      agent,
    }
  }

  return { ok: true, reason: null, agent }
}

/**
 * Liste les IntegrationConfig visibles d'un namespace.
 *
 * ATTENTION : ne retourne QUE les configs persistées en base. Les intégrations
 * chargées depuis le disque (`{configPath}/integrations/*.yaml`, via
 * `FilesystemIntegrationConfigRepository`) n'y figurent pas. Un agent peut donc
 * disposer d'outils fichier sans qu'aucune entrée n'apparaisse ici.
 *
 * @param {string} namespaceId
 * @returns {Promise<object[]>}
 */
export async function listIntegrations(namespaceId) {
  const res = await request('GET', `/api/integration-configs?namespaceId=${namespaceId}`)
  return res.json()
}

/**
 * Normalise un chemin absolu pour comparaison : supprime le séparateur final.
 *
 * @param {string} p
 * @returns {string}
 */
function normalizeRoot(p) {
  return p.replace(/\/+$/, '')
}

/**
 * Préflight de colocalisation : vérifie que l'agent écrira dans l'arbre que
 * l'orchestrateur va compiler et mesurer.
 *
 * POURQUOI CETTE GARDE EXISTE (incident du 2026-08-19, F13)
 * ----------------------------------------------------------
 * Un run a été lancé sur un namespace dont l'intégration `FILE_ACCESS` pointait sur
 * un tout autre dépôt que celui où tournait l'orchestrateur. L'agent cherchait des
 * fichiers absents de son arbre ; l'orchestrateur mesurait le diff du sien.
 *
 * Ce run-là a échoué proprement parce que le fichier visé n'existait pas côté agent.
 * MAIS si la tâche avait été réalisable dans les deux arbres, l'agent aurait modifié
 * l'un, l'oracle aurait compilé l'autre, et le verdict aurait été VERT sur un travail
 * invisible. C'est la pire défaillance possible : un succès qui ne porte sur rien.
 *
 * L'égalité est EXACTE, pas une relation d'ascendance. Un `rootPath` ancêtre du dépôt
 * laisserait l'agent écrire hors du périmètre mesuré par le snapshot de diff — les
 * modifications hors dépôt seraient invisibles au registre.
 *
 * FAIL-CLOSED SUR L'INVÉRIFIABLE : si une intégration déclarée par l'agent n'apparaît
 * pas dans la liste REST (cas des intégrations filesystem), on refuse de partir plutôt
 * que de supposer. On ne peut pas vérifier son `rootPath`, donc on ne peut pas garantir
 * la colocalisation.
 *
 * @param {string} namespaceId
 * @param {object} agent      L'AgentConfig retourné par [preflightAgent].
 * @param {string} repoRoot   Racine du dépôt où tourne l'orchestrateur.
 * @returns {Promise<{ ok: boolean, reason: string|null, rootPath: string|null }>}
 */
export async function preflightWorkspace(namespaceId, agent, repoRoot) {
  /** Clés réservées : résolues par le service, jamais par une IntegrationConfig. */
  const RESERVED = new Set(['QUERY_USER', 'CASE_FILE_EXCHANGE', 'NAMESPACE_FILE_EXCHANGE'])

  const declared = Object.keys(agent.integrations ?? {}).filter((k) => !RESERVED.has(k))
  if (declared.length === 0) {
    return {
      ok: false,
      reason:
        `Agent "${agent.name}" ne déclare aucune intégration hors clés réservées : ` +
        `il n'a aucun outil d'écriture et ne pourra rien modifier.`,
      rootPath: null,
    }
  }

  let configs
  try {
    configs = await listIntegrations(namespaceId)
  } catch (err) {
    return { ok: false, reason: `Impossible de lister les intégrations : ${err}`, rootPath: null }
  }

  const byName = new Map(configs.map((c) => [c.name, c]))

  // Seules les intégrations FILE_ACCESS peuvent rompre la colocalisation.
  // Les intégrations sans rootPath (AI, MEMORY, ANGULAR_MCP, CHROME_DEVTOOLS, etc.)
  // sont ignorées ici même si elles ne figurent pas dans l'API REST : elles n'écrivent
  // pas dans l'arbre mesuré par l'oracle et ne peuvent pas produire de verdict fantôme.
  const unverifiable = declared.filter((name) => {
    if (byName.has(name)) return false          // connue de l'API : vérifiable
    const fromAgent = (agent.integrations ?? {})[name]
    // Si la valeur côté agent est null/undefined ou un tableau d'outils sans rootPath,
    // ce n'est pas une FILE_ACCESS — on l'ignore.
    if (fromAgent === null || Array.isArray(fromAgent) || typeof fromAgent !== 'object') return false
    // Objet avec des champs : potentiellement FILE_ACCESS avec rootPath.
    return true
  })
  if (unverifiable.length > 0) {
    return {
      ok: false,
      reason:
        `Intégration(s) déclarée(s) mais absente(s) de l'API : ${unverifiable.join(', ')}.\n` +
        `Elles sont probablement chargées depuis le disque ` +
        `({configPath}/integrations/), où leur rootPath n'est pas vérifiable par REST.\n` +
        `L'orchestrateur refuse de partir sans pouvoir garantir que l'agent écrit dans ` +
        `l'arbre qu'il va compiler.`,
      rootPath: null,
    }
  }

  const fileAccess = declared
    .map((name) => byName.get(name))
    .filter((c) => c != null && c.integrationType === 'FILE_ACCESS')

  if (fileAccess.length === 0) {
    return {
      ok: false,
      reason:
        `Agent "${agent.name}" n'a aucune intégration FILE_ACCESS : il ne peut rien écrire.`,
      rootPath: null,
    }
  }

  const expected = normalizeRoot(repoRoot)

  for (const cfg of fileAccess) {
    const rootPath = cfg.parameters?.rootPath
    if (!rootPath) {
      return {
        ok: false,
        reason: `L'intégration "${cfg.name}" n'a pas de rootPath.`,
        rootPath: null,
      }
    }

    if (normalizeRoot(rootPath) !== expected) {
      return {
        ok: false,
        reason:
          `Colocalisation rompue sur l'intégration "${cfg.name}".\n` +
          `  rootPath de l'agent   : ${rootPath}\n` +
          `  racine de l'orchestrateur : ${repoRoot}\n` +
          `L'agent écrirait dans un arbre et l'oracle en compilerait un autre : le verdict ` +
          `porterait sur un travail invisible.`,
        rootPath,
      }
    }

    if (cfg.parameters?.readOnly === true) {
      return {
        ok: false,
        reason: `L'intégration "${cfg.name}" est en readOnly : l'agent ne peut rien écrire.`,
        rootPath,
      }
    }
  }

  return { ok: true, reason: null, rootPath: normalizeRoot(fileAccess[0].parameters.rootPath) }
}

/**
 * Rend l'inventaire des agents du namespace, en marquant ceux qui sont utilisables
 * comme rôle de phase (activés et sans `subAgents`).
 *
 * C'est ce qui remplace la question à l'humain : l'information nécessaire pour relancer
 * correctement, sans transformer l'orchestrateur en programme interactif.
 *
 * @param {object[]} agents
 * @returns {string}
 */
function formatAgentInventory(agents) {
  if (agents.length === 0) {
    return 'Aucun agent dans ce namespace.'
  }

  const lines = agents
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((a) => {
      const subs = Array.isArray(a.subAgents) ? a.subAgents : []
      const usable = a.enabled !== false && subs.length === 0
      const marker = usable ? '  \u2713' : '  \u2717'
      const notes = []
      if (a.enabled === false) notes.push('désactivé')
      if (subs.length > 0) notes.push(`subAgents=[${subs.join(', ')}]`)
      const suffix = notes.length > 0 ? `  (${notes.join(', ')})` : ''
      return `${marker} ${a.name}${suffix}`
    })

  return [
    'Agents du namespace (\u2713 = utilisable comme r\u00f4le de phase) :',
    ...lines,
    '',
    'Relancer avec FACTORY_AGENT=<nom>.',
  ].join('\n')
}

// --------------------------------------------------------------------------
// Détection de fin de tour
// --------------------------------------------------------------------------

/*
 * POURQUOI `CaseStatusEvent` ET PAS `AgentFinishedEvent` (correction du 2026-08-19)
 * --------------------------------------------------------------------------------
 * Une première version considérait le premier `AgentFinishedEvent` postérieur au
 * message posté comme la fin du traitement. C'est FAUX.
 *
 * `AgentFinishedEvent` signifie « un tour d'agent s'est terminé ». Il est émis en
 * huit endroits du backend, dont plusieurs sont suivis d'un travail supplémentaire
 * sans nouvelle intervention utilisateur :
 *
 *   - `AgentInterruptHandler` sur une redirection : émet AgentFinishedEvent PUIS
 *     un AgentSelectedEvent — un autre agent enchaîne immédiatement.
 *   - `CaseRuntime.runTurns` : après chaque AgentFinishedEvent, si la file de
 *     commandes n'est pas vide, un nouveau message est injecté et un nouveau tour
 *     démarre (`iterationCount = 0`).
 *   - `AgentAdvanced` gate de confirmation : émet AgentFinishedEvent en attendant
 *     une confirmation utilisateur.
 *   - `AwaitAnswer` (queryUser) : émet AgentFinishedEvent puis un QuestionEvent.
 *
 * Conclure « la phase est terminée » sur le premier de ces événements produit un
 * verdict rendu pendant que le travail continue. Pour un workflow où un agent
 * modifie du code et où l'orchestrateur lance ensuite un build, cela signifie
 * compiler un arbre à moitié modifié — et obtenir un résultat vert ou rouge qui
 * ne veut rien dire. Défaillance silencieuse, plausible, fausse : exactement ce
 * que cet orchestrateur existe pour éliminer.
 *
 * Le signal correct est `CaseStatusEvent`. Émis par `CaseServiceImpl.handleStatusChange`
 * APRÈS le retour de `runTurns()`, c'est-à-dire après épuisement de la file de
 * commandes et de la chaîne de redirections. Il est durable (il n'implémente pas
 * `TransientCaseEvent`) et persisté AVANT d'être diffusé en SSE, donc lisible via
 * l'historique REST — aucun besoin de SSE.
 *
 * `status ∈ {IDLE, KILLED, ERROR}` est la quiescence : plus rien ne se produira
 * sans nouvelle entrée.
 *
 * DEUX ATTENTES, PAS UNE
 * ----------------------
 * Le POST est asynchrone (`scope.launch { runtime.run() }`). Entre le POST et le
 * passage à RUNNING, l'historique ne contient encore rien de nouveau. Attendre
 * directement IDLE conclurait « terminé » AVANT que le travail ait commencé.
 * On attend donc RUNNING d'abord, puis la quiescence après ce RUNNING.
 *
 * F7 — QUIESCENCE APRÈS LE DERNIER RUNNING, PAS LE PREMIER (correction)
 * -----------------------------------------------------------------------
 * Un agent peut enchaîner plusieurs tours sans intervention utilisateur :
 * redirection (AgentFinishedEvent + AgentSelectedEvent → nouveau RUNNING),
 * file de commandes (CaseRuntime injecte un message → nouveau RUNNING), etc.
 *
 * Chaque transition RUNNING→IDLE intermédiaire est un `CaseStatusEvent` IDLE
 * légitime, mais suivi d'un nouveau RUNNING. S'arrêter sur le PREMIER IDLE
 * après le premier RUNNING produirait un verdict au milieu du travail :
 * l'oracle compilerait un arbre à moitié modifié.
 *
 * Le fix : à chaque tour de sondage, avancer `runningIndex` sur le RUNNING le
 * plus récent dans la fenêtre, PUIS chercher la quiescence à partir de là.
 * Seul un statut quiescent qui n'est suivi d'aucun RUNNING ultérieur est final.
 */

/** Type d'événement portant le statut du case. */
const CASE_STATUS_EVENT = 'CaseStatusEvent'

/** Statuts marquant la quiescence du runtime : plus rien sans nouvelle entrée. */
const QUIESCENT_STATUSES = ['IDLE', 'KILLED', 'ERROR']

/** Intervalle de sondage de l'historique REST. */
const POLL_INTERVAL_MS = 2_000

/** Budget par défaut pour voir le case démarrer (passer à RUNNING). */
const DEFAULT_START_TIMEOUT_MS = 30_000

/** Budget par défaut pour le travail lui-même, une fois démarré. */
const DEFAULT_WORK_TIMEOUT_MS = 10 * 60 * 1000

/**
 * Retourne les événements postérieurs à un événement donné, identifié par son id.
 *
 * ON N'UTILISE PAS LES TIMESTAMPS. Jackson sérialise `Instant` avec un nombre
 * variable de décimales : `"...10.12Z"` est lexicalement SUPÉRIEUR à
 * `"...10.123456789Z"` alors qu'il lui est chronologiquement antérieur. Une
 * comparaison de chaînes sur ces valeurs est donc silencieusement fausse dès que
 * deux événements tombent dans la même seconde avec des précisions différentes.
 *
 * L'ordre du tableau retourné par le backend fait autorité (tri Cypher explicite),
 * et l'id d'un événement est stable. C'est le seul ancrage fiable.
 *
 * @param {object[]} events
 * @param {string|null} baselineId  Id du dernier événement connu avant l'action.
 * @returns {{ events: object[], anchored: boolean }}
 *   `anchored` vaut false si l'id de référence est introuvable (événement supprimé
 *   ou historique tronqué) — le résultat est alors la liste complète, ce qui est
 *   permissif : à signaler, jamais à ignorer.
 */
function sliceAfterId(events, baselineId) {
  if (!baselineId) return { events, anchored: true }
  const index = events.findIndex((e) => e.id === baselineId)
  if (index < 0) return { events, anchored: false }
  return { events: events.slice(index + 1), anchored: true }
}

/**
 * Cherche le premier `CaseStatusEvent` portant l'un des statuts demandés.
 *
 * @param {object[]} events
 * @param {string[]} statuses
 * @param {number} [fromIndex]
 * @returns {{ event: object, index: number }|null}
 */
function findStatusEvent(events, statuses, fromIndex = 0) {
  for (let i = fromIndex; i < events.length; i++) {
    const e = events[i]
    if (e.type === CASE_STATUS_EVENT && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

/**
 * Cherche le DERNIER `CaseStatusEvent` portant l'un des statuts demandés.
 *
 * Utilisé pour avancer `runningIndex` sur le RUNNING le plus récent (F7) :
 * un agent multi-tours produit plusieurs RUNNING successifs, et seul le plus
 * récent délimite correctement la fenêtre de quiescence finale.
 *
 * @param {object[]} events
 * @param {string[]} statuses
 * @returns {{ event: object, index: number }|null}
 */
function findLastStatusEvent(events, statuses) {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i]
    if (e.type === CASE_STATUS_EVENT && statuses.includes(e.status)) {
      return { event: e, index: i }
    }
  }
  return null
}

/**
 * Retourne les `QuestionEvent` sans `AnswerEvent` apparié.
 *
 * L'appariement se fait sur `AnswerEvent.questionId`, qui est un champ du modèle —
 * pas sur une comparaison de timestamps. Une question sans réponse au moment où le
 * case atteint la quiescence signifie que l'agent attend une entrée humaine.
 *
 * @param {object[]} allEvents  L'historique COMPLET du case, pas seulement le tour.
 * @returns {object[]}
 */
function findUnansweredQuestions(allEvents) {
  const answered = new Set(
    allEvents.filter((e) => e.type === 'AnswerEvent').map((e) => e.questionId)
  )
  return allEvents.filter((e) => e.type === 'QuestionEvent' && !answered.has(e.id))
}

/**
 * Exécute un tour d'agent et attend la quiescence du case.
 *
 * ## Séquence et publication du case actif (A5/F24)
 *
 * Le case ID est publié dans active-case.mjs DES SON ENTRÉE dans cette fonction,
 * avant tout appel réseau. C'est l'invariant A5 : une fois qu'un case existe et
 * peut exécuter, SIGTERM doit pouvoir le tuer.
 *
 * La publication se fait en deux temps :
 *
 *   setActiveCaseId(caseId)   ← immédiatement à l'entrée de la fonction
 *   [...tout le travail...]
 *   clearActiveCaseId()       ← dans le bloc finally, toujours exécuté
 *
 * Si postMessage échoue, le case existe déjà côté AgentOS mais n'a aucun message.
 * Il n'exécutera rien sans message, donc il n'y a pas d'urgence à le tuer.
 * MAIS si SIGTERM arrive pendant postMessage, le handler verra le caseId et
 * tentera de le tuer — ce qui est correct et sûr (tuer un case sans message
 * est idempotent côté AgentOS).
 * Sur échec de postMessage, on tente killQuietly avant de retourner l'erreur,
 * pour libérer la ressource côté AgentOS même hors SIGTERM.
 *
 * Séquence complète :
 *   1. setActiveCaseId(caseId)          ← A5 : publié avant tout appel réseau
 *   2. listEvents (ancrage)
 *   3. vérification quiescence
 *   4. postMessage
 *      └─ échec : killQuietly + return failure (clearActiveCaseId via finally)
 *   5. boucle de sondage
 *   6. [finally] clearActiveCaseId()
 *
 * SUR TIMEOUT, LE CASE EST TUÉ — dans les deux phases. Ce n'est pas une commodité :
 * l'orchestrateur partage son disque avec les agents (l'isolation des cases est
 * conversationnelle, pas matérielle). Un agent qui continue de tourner après que
 * l'orchestrateur a rendu son verdict peut modifier l'arbre pendant la phase
 * suivante — c'est l'incident des deux délégations concurrentes. Quand le budget
 * est dépassé, personne ne doit plus écrire.
 *
 * Conséquence assumée : un case tué n'est pas réutilisable pour une phase
 * ultérieure. Les workflows créent un case par phase.
 *
 * @param {string} caseId
 * @param {string} agentName  Nom de l'agent (sans le `@`).
 * @param {string} brief      Consigne à poster.
 * @param {{ startTimeoutMs?: number, workTimeoutMs?: number }} [options]
 * @returns {Promise<{
 *   status: 'finished' | 'pending_question' | 'case_busy' | 'start_timeout'
 *         | 'work_timeout' | 'killed' | 'case_error' | 'error',
 *   caseStatus: string|null,
 *   message: string,
 *   events: object[],
 *   agentsSelected: string[],
 *   agentTurns: number,
 *   toolCallCount: number,
 *   failedToolCalls: Record<string, number>,
 *   killedByBudget: boolean,
 *   anchored: boolean,
 *   llmModels: Array<{ agentName: string|null, llmProvider: string|null, llmModel: string|null }>,
 * }>}
 */
export async function runAgentTurn(
  caseId,
  agentName,
  brief,
  {
    startTimeoutMs = DEFAULT_START_TIMEOUT_MS,
    workTimeoutMs = DEFAULT_WORK_TIMEOUT_MS,
  } = {}
) {
  /** Résultat d'échec, avec des compteurs à zéro plutôt qu'absents. */
  const failure = (status, message, extra = {}) => ({
    status,
    caseStatus: null,
    message,
    events: [],
    agentsSelected: [],
    agentTurns: 0,
    toolCallCount: 0,
    failedToolCalls: {},
    killedByBudget: false,
    anchored: true,
    llmModels: [],
    ...extra,
  })

  // A5/F24 : publication immédiate du case actif.
  //
  // Le case existe déjà côté AgentOS (créé par createCase() dans le workflow).
  // Dès qu'il existe, SIGTERM doit pouvoir le tuer. On publie donc avant tout
  // appel réseau, y compris listEvents et postMessage.
  //
  // clearActiveCaseId() est appelé dans le bloc finally ci-dessous, quelle que
  // soit la sortie : succès, échec de postMessage, timeout, ou exception.
  setActiveCaseId(caseId)

  try {
    // --- 1 & 2 : ancrage et vérification de quiescence -----------------------
    let baselineId = null
    try {
      const existing = await listEvents(caseId)
      baselineId = existing.at(-1)?.id ?? null

      const lastStatus = existing.filter((e) => e.type === CASE_STATUS_EVENT).at(-1)
      if (lastStatus && !QUIESCENT_STATUSES.includes(lastStatus.status)) {
        return failure(
          'case_busy',
          `Le case est en statut ${lastStatus.status}. run() est auto-gardé côté serveur : ` +
            `poster maintenant produirait un lancement silencieusement abandonné.`
        )
      }
    } catch (err) {
      return failure('error', String(err))
    }

    // --- 3 : poster le message ----------------------------------------------
    //
    // Si postMessage échoue, le case existe mais n'a pas de message. Il
    // n'exécutera rien, mais on tente quand même de le tuer pour libérer
    // la ressource côté AgentOS. clearActiveCaseId() est appelé par finally.
    try {
      await postMessage(caseId, `@${agentName} ${brief}`)
    } catch (err) {
      await killQuietly(caseId)
      return failure('error', String(err))
    }

    // --- 4 & 5 : les deux attentes ------------------------------------------
    const startDeadline = Date.now() + startTimeoutMs
    let workDeadline = null
    let runningIndex = null
    let allEvents = []
    let anchored = true

    for (;;) {
      await sleep(POLL_INTERVAL_MS)

      try {
        allEvents = await listEvents(caseId)
      } catch (err) {
        return failure('error', String(err))
      }

      const sliced = sliceAfterId(allEvents, baselineId)
      const turnEvents = sliced.events
      anchored = sliced.anchored

      if (runningIndex === null) {
        // Phase d'attente du démarrage.
        const running = findStatusEvent(turnEvents, ['RUNNING'])
        if (running) {
          runningIndex = running.index
          workDeadline = Date.now() + workTimeoutMs
        } else if (Date.now() > startDeadline) {
          await killQuietly(caseId)
          return failure(
            'start_timeout',
            `Le case n'est pas passé à RUNNING en ${startTimeoutMs}ms.`,
            { killedByBudget: true, anchored }
          )
        } else {
          continue
        }
      }

      // F7 — avancer runningIndex sur le RUNNING le plus récent.
      //
      // Un agent peut enchaîner plusieurs tours (redirection, file de commandes) :
      // chaque transition produit un nouveau CaseStatusEvent RUNNING. Un IDLE
      // intermédiaire (fin d'un tour avant la redirection) est légitime mais pas
      // final. En avançant runningIndex à chaque sondage, on garantit que la
      // quiescence cherchée ci-dessous est celle qui suit le DERNIER RUNNING connu,
      // pas un IDLE intermédiaire.
      //
      // workDeadline n'est pas réinitialisé sur chaque nouveau RUNNING : le budget
      // total est celui alloué à l'ensemble du tour, pas à chaque agent individuel.
      const lastRunning = findLastStatusEvent(turnEvents, ['RUNNING'])
      if (lastRunning && lastRunning.index > runningIndex) {
        runningIndex = lastRunning.index
      }

      // Phase de travail : quiescence APRÈS le RUNNING le plus récent.
      // Un IDLE suivi d'un RUNNING ultérieur n'est pas retenu (voir F7 ci-dessus).
      const quiescent = findStatusEvent(turnEvents, QUIESCENT_STATUSES, runningIndex + 1)
      if (quiescent) {
        return buildTurnResult(turnEvents, allEvents, quiescent.event, anchored)
      }

      if (Date.now() > workDeadline) {
        await killQuietly(caseId)
        return {
          ...failure('work_timeout', `L'agent n'a pas atteint la quiescence en ${workTimeoutMs}ms.`),
          events: turnEvents,
          agentsSelected: collectAgentsSelected(turnEvents),
          agentTurns: countType(turnEvents, 'AgentFinishedEvent'),
          toolCallCount: turnEvents.filter((e) => e.type === 'ToolResponseEvent').length,
          failedToolCalls: buildFailedToolCalls(
            turnEvents.filter((e) => e.type === 'ToolResponseEvent')
          ),
          killedByBudget: true,
          anchored,
          llmModels: collectLlmModels(turnEvents),
        }
      }
    }
  } finally {
    // Libère le verrou quel que soit le chemin de sortie :
    // succès (return buildTurnResult), échec de postMessage (return failure),
    // timeout (return failure), ou exception non attrapée.
    clearActiveCaseId(caseId)
  }
}

// --------------------------------------------------------------------------
// Helpers privés
// --------------------------------------------------------------------------

/**
 * Construit le résultat d'un tour terminé, à partir du statut de quiescence atteint.
 *
 * @param {object[]} turnEvents  Événements du tour courant.
 * @param {object[]} allEvents   Historique complet (pour l'appariement question/réponse).
 * @param {object} quiescentEvent
 * @param {boolean} anchored
 */
function buildTurnResult(turnEvents, allEvents, quiescentEvent, anchored) {
  const toolResponses = turnEvents.filter((e) => e.type === 'ToolResponseEvent')

  const base = {
    caseStatus: quiescentEvent.status,
    message: extractLastAgentMessage(turnEvents),
    events: turnEvents,
    agentsSelected: collectAgentsSelected(turnEvents),
    agentTurns: countType(turnEvents, 'AgentFinishedEvent'),
    toolCallCount: toolResponses.length,
    failedToolCalls: buildFailedToolCalls(toolResponses),
    killedByBudget: false,
    anchored,
    llmModels: collectLlmModels(turnEvents),
  }

  if (quiescentEvent.status === 'KILLED') {
    return { ...base, status: 'killed' }
  }
  if (quiescentEvent.status === 'ERROR') {
    return { ...base, status: 'case_error' }
  }

  // IDLE : le tour est fini, mais l'agent attend peut-être une réponse humaine.
  const unanswered = findUnansweredQuestions(allEvents)
  if (unanswered.length > 0) {
    return {
      ...base,
      status: 'pending_question',
      message: unanswered.at(-1).question ?? base.message,
    }
  }

  return { ...base, status: 'finished' }
}

/** @param {number} ms */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** Tue un case sans propager l'erreur — on est déjà sur un chemin d'échec. */
async function killQuietly(caseId) {
  try {
    await killCase(caseId)
  } catch {
    // ignoré : le verdict d'échec prime sur le succès du kill
  }
}

/**
 * @param {object[]} events
 * @param {string} type
 * @returns {number}
 */
function countType(events, type) {
  return events.filter((e) => e.type === type).length
}

/**
 * Noms des agents effectivement sélectionnés pendant le tour, dans l'ordre, sans doublon.
 *
 * C'est le contrôle a posteriori de la substitution d'agent. Le préflight
 * ([preflightAgent]) vérifie la configuration ; celui-ci vérifie ce qui s'est
 * réellement passé. Les deux sont nécessaires : la configuration peut changer entre
 * le préflight et le POST, et surtout `selectAgent` ne renvoie aucune erreur quand il
 * bascule sur l'agent par défaut — seul l'`AgentSelectedEvent` dit qui a travaillé.
 *
 * Plusieurs entrées signifient une chaîne de redirections : l'agent demandé a passé
 * la main. Ce n'est pas une anomalie en soi, mais c'est un fait que le registre doit
 * porter — le travail n'a pas été fait par un seul rôle.
 *
 * @param {object[]} events
 * @returns {string[]}
 */
function collectAgentsSelected(events) {
  const names = events
    .filter((e) => e.type === 'AgentSelectedEvent')
    .map((e) => e.agentName)
    .filter(Boolean)
  return [...new Set(names)]
}

/**
 * Retourne le texte du dernier `MessageEvent` d'agent du tour.
 * Utilisé pour le brief du tour suivant, JAMAIS écrit dans le registre.
 *
 * @param {object[]} events
 * @returns {string}
 */
function extractLastAgentMessage(events) {
  const last = events
    .filter((e) => e.type === 'MessageEvent' && e.actor?.role === 'AGENT')
    .at(-1)
  if (!last) return ''
  return (last.content ?? [])
    .filter((p) => typeof p.content === 'string')
    .map((p) => p.content)
    .join('')
}

/**
 * Collecte les modèles LLM utilisés pendant le tour, dédupliqués.
 *
 * Les champs `llmProvider` et `llmModel` sont émis par AgentOS sur deux types
 * d'événements : `AgentRunningEvent` et `AgentFinishedEvent`. Les deux sont
 * collectés pour la raison suivante : si un tour est tué par dépassement de
 * budget, il n'y aura pas d'`AgentFinishedEvent`, mais l'`AgentRunningEvent`
 * aura déjà été émis. C'est précisément le cas où connaître le modèle a le
 * plus de valeur diagnostique.
 *
 * Politique sur les valeurs nulles : on n'invente pas de valeur par défaut.
 * Si les deux champs sont null, on n'ajoute pas d'entrée. Si un seul est
 * renseigné, on garde l'entrée avec l'autre à null. Un champ absent dit
 * « le back ne l'a pas fourni » ; une valeur inventée affirmerait quelque chose
 * de faux — exactement ce que cet orchestrateur existe pour ne pas faire.
 *
 * Déduplication sur la combinaison des trois champs.
 *
 * @param {object[]} events
 * @returns {Array<{ agentName: string, llmProvider: string|null, llmModel: string|null }>}
 */
function collectLlmModels(events) {
  const seen = new Set()
  const result = []

  for (const e of events) {
    if (e.type !== 'AgentRunningEvent' && e.type !== 'AgentFinishedEvent') continue

    // Si les deux champs de modèle sont absents/null, pas d'entrée.
    if (e.llmProvider == null && e.llmModel == null) continue

    const agentName = e.agentName ?? null
    const llmProvider = e.llmProvider ?? null
    const llmModel = e.llmModel ?? null

    const key = JSON.stringify({ agentName, llmProvider, llmModel })
    if (seen.has(key)) continue
    seen.add(key)

    result.push({ agentName, llmProvider, llmModel })
  }

  return result
}

/**
 * Regroupe les `ToolResponseEvent` échoués par nom d'outil.
 *
 * `success` est le verdict déterministe du backend : pour le plugin BASH il vaut
 * exactement `exitCode == 0`. C'est un fait, pas une narration — il a sa place
 * dans le registre.
 *
 * @param {object[]} toolResponseEvents
 * @returns {Record<string, number>}
 */
function buildFailedToolCalls(toolResponseEvents) {
  const result = {}
  for (const e of toolResponseEvents) {
    if (e.success === false) {
      const name = e.toolName ?? 'unknown'
      result[name] = (result[name] ?? 0) + 1
    }
  }
  return result
}
