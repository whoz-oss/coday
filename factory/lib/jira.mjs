/**
 * Utilitaires Jira — factory/lib/jira.mjs
 *
 * Ce module regroupe les fonctions liées à Jira extraites de factory/workflows/us-loop.mjs.
 *
 * Exports :
 *   extractTicketId  — extrait et normalise l'identifiant Jira
 *   extractAdfText   — aplatit un noeud ADF en texte brut
 *   fetchJiraTicket  — récupère ticket + commentaires, construit le contenu markdown
 *
 * Aucune dépendance externe — uniquement le fetch global et Buffer de Node.
 */

/**
 * Budget de caractères pour les commentaires inclus dans ticketContent.
 * Au-delà de ce budget, les commentaires les plus anciens sont tronqués.
 */
const COMMENTS_CHAR_BUDGET = 8000

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
 * Récupère tous les commentaires d'un ticket Jira avec pagination.
 *
 * Appelle GET /rest/api/3/issue/{ticketId}/comment?orderBy=-created&maxResults=50
 * en itérant sur les pages jusqu'à épuisement.
 *
 * Retourne les commentaires ordonnés du plus récent au plus ancien (l'ordre
 * retourné par l'API avec orderBy=-created).
 *
 * @param {string} ticketId
 * @param {string} jiraBaseUrl
 * @param {string} jiraEmail
 * @param {string} jiraApiToken
 * @returns {Promise<Array<{author: string, created: string, body: string}>>}
 */
export async function fetchJiraComments(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken) {
  const credentials = Buffer.from(`${jiraEmail}:${jiraApiToken}`).toString('base64')
  const base = jiraBaseUrl.replace(/\/$/, '')
  const PAGE_SIZE = 50

  const allComments = []
  let startAt = 0

  while (true) {
    const url = `${base}/rest/api/3/issue/${encodeURIComponent(ticketId)}/comment` +
      `?orderBy=-created&maxResults=${PAGE_SIZE}&startAt=${startAt}`

    const res = await fetch(url, {
      method: 'GET',
      headers: {
        Authorization: `Basic ${credentials}`,
        Accept: 'application/json',
      },
    })

    if (!res.ok) {
      const body = await res.text().catch(() => '')
      throw new Error(`Jira comments API ${res.status} pour ${ticketId} : ${body.slice(0, 200)}`)
    }

    const data = await res.json()
    const comments = data.comments ?? []
    const total = data.total ?? 0

    for (const c of comments) {
      const authorName =
        c.author?.displayName ??
        c.author?.emailAddress ??
        c.author?.accountId ??
        'Unknown'
      const created = c.created ?? ''
      let body = ''
      if (c.body) {
        if (typeof c.body === 'string') {
          body = c.body
        } else {
          body = extractAdfText(c.body).trim()
        }
      }
      allComments.push({ author: authorName, created, body })
    }

    startAt += comments.length
    if (startAt >= total || comments.length === 0) break
  }

  // allComments is already ordered newest-first (orderBy=-created)
  return allComments
}

/**
 * Applique le budget de caractères aux commentaires (ordonnés newest-first).
 *
 * Inclut les commentaires du plus récent au plus ancien jusqu'à épuisement
 * du budget. Retourne les commentaires inclus et le nombre omis.
 *
 * @param {Array<{author: string, created: string, body: string}>} comments  Newest-first.
 * @param {number} budget
 * @returns {{ included: Array<{author: string, created: string, body: string}>, omitted: number }}
 */
export function applyCommentBudget(comments, budget) {
  let remaining = budget
  const included = []

  for (const c of comments) {
    const size = c.author.length + c.created.length + c.body.length + 50 // overhead per comment
    if (remaining <= 0) break
    included.push(c)
    remaining -= size
  }

  const omitted = comments.length - included.length
  return { included, omitted }
}

/**
 * Formate les commentaires en bloc markdown.
 *
 * @param {Array<{author: string, created: string, body: string}>} comments
 * @param {number} omitted
 * @returns {string}
 */
function formatCommentsSection(comments, omitted) {
  const parts = comments.map((c) => {
    const date = c.created ? new Date(c.created).toISOString().slice(0, 10) : ''
    return `**${c.author}** (${date}):\n${c.body}`
  })

  let section = parts.join('\n\n---\n\n')

  if (omitted > 0) {
    section += `\n\n*(${omitted} older comment${omitted === 1 ? '' : 's'} omitted — budget exceeded)*`
  }

  return section
}

/**
 * Récupère un ticket Jira (et ses commentaires) et retourne le contenu en markdown.
 *
 * Appelle GET /rest/api/3/issue/<ticketId> et GET /rest/api/3/issue/<ticketId>/comment
 * en Basic auth (email + token API).
 *
 * Retourne un objet avec :
 *   - ticketContent    : le markdown construit depuis summary + description + AC + comments
 *   - summary          : le titre du ticket (fait factuel)
 *   - fieldCount       : nombre de champs non vides parmi les trois (fait factuel)
 *   - commentCount     : nombre total de commentaires sur le ticket
 *   - commentsIncluded : nombre de commentaires inclus dans ticketContent
 *   - commentsTruncated: true si au moins un commentaire a été omis
 *
 * En cas d'erreur HTTP (ticket ou commentaires), lève une Error.
 *
 * @param {string} ticketId       Identifiant Jira, ex. 'PROJ-1234'.
 * @param {string} jiraBaseUrl    ex. 'https://monentreprise.atlassian.net'
 * @param {string} jiraEmail
 * @param {string} jiraApiToken
 * @returns {Promise<{
 *   ticketContent: string,
 *   summary: string,
 *   fieldCount: number,
 *   commentCount: number,
 *   commentsIncluded: number,
 *   commentsTruncated: boolean
 * }>}
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

  // Commentaires — erreur fatale si l'appel échoue (contrat du fetch-ticket)
  const allComments = await fetchJiraComments(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken)
  const commentCount = allComments.length

  const { included, omitted } = applyCommentBudget(allComments, COMMENTS_CHAR_BUDGET)
  const commentsIncluded = included.length
  const commentsTruncated = omitted > 0

  // Construire le markdown
  const sections = [`## Summary\n${summary}`]
  if (description) sections.push(`## Description\n${description}`)
  if (acceptanceCriteria) sections.push(`## Acceptance criteria\n${acceptanceCriteria}`)

  if (included.length > 0) {
    sections.push(`## Comments\n${formatCommentsSection(included, omitted)}`)
  }

  const ticketContent = sections.join('\n\n')

  const fieldCount = [summary, description, acceptanceCriteria].filter(Boolean).length

  return {
    ticketContent,
    summary,
    fieldCount,
    commentCount,
    commentsIncluded,
    commentsTruncated,
  }
}
