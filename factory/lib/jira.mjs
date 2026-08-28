/**
 * Utilitaires Jira — factory/lib/jira.mjs
 *
 * Ce module regroupe les trois fonctions liées à Jira qui vivaient dans
 * factory/workflows/us-loop.mjs. Elles en ont été extraites pour deux raisons
 * indépendantes :
 *
 *   1. Le serveur du dashboard (dashboard/server.mjs) a besoin de fetchJiraTicket
 *      pour afficher le contenu d'un ticket dans le panneau de phase fetch-ticket.
 *      Or server.mjs ne peut pas importer un workflow : un workflow a des effets de
 *      bord au chargement (il lit process.env immédiatement) et introduit des
 *      dépendances transitives non souhaitées dans le process du dashboard.
 *
 *   2. Ces fonctions n'avaient aucun test parce qu'elles étaient enfouies dans un
 *      workflow que l'on ne peut tester qu'en run réel. Extraites ici, elles peuvent
 *      être couvertes par factory/tests/test-jira.mjs sans aucun appel réseau.
 *
 * Aucune dépendance externe — uniquement le fetch global et Buffer de Node.
 * C'est l'invariant de factory/ : l'instrument ne dépend pas de la santé de ce
 * qu'il mesure.
 */

/**
 * Extrait l'identifiant Jira depuis un identifiant brut ou une URL complète.
 *
 * Exemples :
 *   'PROJ-1234'                                         → 'PROJ-1234'
 *   'https://foo.atlassian.net/browse/PROJ-1234'        → 'PROJ-1234'
 *   'proj-1234'                                         → 'PROJ-1234' (normalisé)
 *   'pas-un-ticket'                                     → null
 *   ''                                                  → null
 *
 * @param {string} input
 * @returns {string|null}
 */
export function extractTicketId(input) {
  if (!input || typeof input !== 'string') return null
  const urlMatch = input.match(/\/browse\/([A-Z][A-Z0-9]+-\d+)/i)
  if (urlMatch) return urlMatch[1].toUpperCase()
  const idMatch = input.match(/^([A-Z][A-Z0-9]+-\d+)$/i)
  if (idMatch) return idMatch[1].toUpperCase()
  return null
}

/**
 * Extrait récursivement le texte brut d'un noeud Atlassian Document Format (ADF).
 *
 * L'API Jira v3 retourne les champs texte (description, acceptance criteria…)
 * dans un format ADF : un arbre JSON avec des noeuds typés. Cette fonction
 * aplatit cet arbre en texte brut en ajoutant des sauts de ligne après les
 * blocs structurels (paragraphe, titre, item de liste…).
 *
 * @param {object|null} node  Noeud ADF (ou null).
 * @returns {string}
 */
export function extractAdfText(node) {
  if (!node || typeof node !== 'object') return ''
  if (node.type === 'text' && typeof node.text === 'string') return node.text
  const children = node.content ?? []
  const parts = children.map(extractAdfText)
  // Ajouter un saut de ligne après les blocs de type paragraphe/heading/listItem
  const BLOCK_TYPES = new Set([
    'paragraph', 'heading', 'listItem', 'bulletList', 'orderedList',
    'blockquote', 'codeBlock', 'rule',
  ])
  return BLOCK_TYPES.has(node.type)
    ? parts.join('') + '\n'
    : parts.join('')
}

/**
 * Récupère un ticket Jira et retourne son contenu en markdown.
 *
 * Appelle GET /rest/api/3/issue/<ticketId> en Basic auth (email + token API).
 * Retourne un objet avec :
 *   - ticketContent : le markdown construit depuis summary + description + AC
 *   - summary       : le titre du ticket (fait factuel)
 *   - fieldCount    : nombre de champs non vides parmi les trois (fait factuel)
 *
 * En cas d'erreur HTTP, lève une Error avec le statut et les premiers caractères
 * du corps — suffisant pour diagnostiquer un 401, un 404 ou un 429.
 *
 * @param {string} ticketId       Identifiant Jira, ex. 'PROJ-1234'.
 * @param {string} jiraBaseUrl    ex. 'https://monentreprise.atlassian.net'
 * @param {string} jiraEmail
 * @param {string} jiraApiToken
 * @returns {Promise<{ ticketContent: string, summary: string, fieldCount: number }>}
 */
export async function fetchJiraTicket(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken) {
  const url = `${jiraBaseUrl.replace(/\/$/, '')}/rest/api/3/issue/${encodeURIComponent(ticketId)}`
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString('base64')

  const res = await fetch(url, {
    method: 'GET',
    headers: {
      Authorization: `Basic ${credentials}`,
      Accept: 'application/json',
    },
  })

  if (!res.ok) {
    const body = await res.text().catch(() => '')
    throw new Error(`Jira API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`)
  }

  const data = await res.json()
  const fields = data.fields ?? {}

  const summary = fields.summary ?? ''

  // Description : ADF (Jira v3) ou texte brut (fallback)
  let description = ''
  if (fields.description) {
    if (typeof fields.description === 'string') {
      description = fields.description
    } else {
      description = extractAdfText(fields.description).trim()
    }
  }

  // Acceptance criteria : champ custom courant (customfield_10016 ou similar)
  // On cherche les clés contenant 'acceptance' (insensible à la casse) en premier,
  // puis le champ standard 'customfield_10016' si présent.
  let acceptanceCriteria = ''
  for (const [key, value] of Object.entries(fields)) {
    if (!value) continue
    if (key.toLowerCase().includes('acceptance') || key === 'customfield_10016') {
      if (typeof value === 'string') {
        acceptanceCriteria = value
        break
      } else if (typeof value === 'object') {
        acceptanceCriteria = extractAdfText(value).trim()
        break
      }
    }
  }

  // Construire le markdown
  const sections = [`## Summary\n${summary}`]
  if (description) sections.push(`## Description\n${description}`)
  if (acceptanceCriteria) sections.push(`## Acceptance criteria\n${acceptanceCriteria}`)
  const ticketContent = sections.join('\n\n')

  const fieldCount = [summary, description, acceptanceCriteria].filter(Boolean).length

  return { ticketContent, summary, fieldCount }
}
